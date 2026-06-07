#include "aggregation/tick_aggregator.hpp"
#include "events/event_bus.hpp"
#include "events/scalping_handler.hpp"
#include "events/swing_handler.hpp"
#include "execution/order_gateway.hpp"
#include "kafka/kafka_consumer.hpp"
#include "kafka/kafka_producer.hpp"

#include <nlohmann/json.hpp>

#include <atomic>
#include <csignal>
#include <cstdio>
#include <cstring>

using namespace trading;
using json = nlohmann::json;

static std::atomic<bool> g_running{true};

static void on_signal(int) {
    g_running.store(false, std::memory_order_release);
}

// Publish a scalping anomaly signal to "urgent-signals" topic as JSON.
// Consumed by downstream alert/notification services.
static void publish_scalping_signal(KafkaProducer& producer,
                                    const AggregatorSignal& sig)
{
    auto handle = producer.acquire_buffer();
    if (!handle) {
        fprintf(stderr, "[engine] message pool exhausted — dropping scalping signal\n");
        return;
    }

    const std::string body = json{
        {"ticker",           std::string(sig.ticker)},
        {"zscore",           sig.zscore},
        {"price_change_pct", sig.price_change_pct},
        {"current_price",    sig.candle.close},
        {"timestamp_ms",     sig.timestamp_ms}
    }.dump();

    if (!handle->write(body.data(), body.size())) return;
    std::strncpy(handle->topic, "urgent-signals",  sizeof(handle->topic) - 1);
    std::strncpy(handle->key,   sig.ticker,         sizeof(handle->key)   - 1);
    handle->key_size = std::strlen(sig.ticker);

    producer.publish(std::move(handle));
}

// Publish a LOW/MEDIUM alert to "anomaly-alerts" topic as JSON.
// Consumed by api-server and pushed to the frontend via SSE.
static void publish_anomaly_alert(KafkaProducer& producer,
                                  const AggregatorSignal& sig)
{
    auto handle = producer.acquire_buffer();
    if (!handle) return;

    const char* severity = (sig.type == SignalType::AlertMedium) ? "MEDIUM" : "LOW";
    const std::string body = json{
        {"ticker",           std::string(sig.ticker)},
        {"zscore",           sig.zscore},
        {"price_change_pct", sig.price_change_pct},
        {"current_price",    sig.candle.close},
        {"severity",         severity},
        {"timestamp_ms",     sig.timestamp_ms}
    }.dump();

    if (!handle->write(body.data(), body.size())) return;
    std::strncpy(handle->topic, "anomaly-alerts", sizeof(handle->topic) - 1);
    std::strncpy(handle->key,   sig.ticker,        sizeof(handle->key)   - 1);
    handle->key_size = std::strlen(sig.ticker);

    producer.publish(std::move(handle));
}

// Publish a closed candle to "candle-data" topic as JSON.
// Consumed by the AI inference service to generate swing signals.
static void publish_candle(KafkaProducer& producer,
                           const AggregatorSignal& sig)
{
    auto handle = producer.acquire_buffer();
    if (!handle) {
        fprintf(stderr, "[engine] message pool exhausted — dropping candle\n");
        return;
    }

    const Candle& c = sig.candle;
    const std::string body = json{
        {"ticker",         std::string(c.ticker)},
        {"open",           c.open},
        {"high",           c.high},
        {"low",            c.low},
        {"close",          c.close},
        {"volume",         c.volume},
        {"open_time_ms",   c.open_time_ms},
        {"close_time_ms",  c.close_time_ms},
        {"window_minutes", c.window_minutes},
        {"tick_count",     c.tick_count}
    }.dump();

    if (!handle->write(body.data(), body.size())) return;
    std::strncpy(handle->topic, "candle-data",  sizeof(handle->topic) - 1);
    std::strncpy(handle->key,   c.ticker,        sizeof(handle->key)   - 1);
    handle->key_size = std::strlen(c.ticker);

    producer.publish(std::move(handle));
}

int main() {
    std::signal(SIGINT,  on_signal);
    std::signal(SIGTERM, on_signal);

    const std::string brokers      = "localhost:9092";
    const std::string api_url      = "https://openapi.koreainvestment.com:9443";
    const std::string access_token = ""; // TODO: load from env

    // ── 1. Order executor ─────────────────────────────────────────────────────
    auto executor = make_order_executor(api_url, access_token);

    // ── 2. Strategy handlers ──────────────────────────────────────────────────
    ScalpingHandler scalping_handler(executor);
    SwingHandler    swing_handler(executor);

    // ── 3. Event bus — dedicated dispatch thread per event type ───────────────
    EventBus bus;
    bus.subscribe([&](const ScalpingEvent& e) { scalping_handler.on_event(e); });
    bus.subscribe([&](const SwingEvent&    e) { swing_handler.on_event(e);    });

    // ── 4. Kafka producer (must be declared before aggregator) ────────────────
    // The aggregator callback runs on the scalping drain thread and Kafka
    // consumer threads; producer must be fully constructed before start().
    KafkaProducer producer(brokers, "trading-engine-producer");

    // ── 5. Tick aggregator (heap-allocated) ──────────────────────────────────
    // MPSCQueue<Tick, 65536> alone is ~8 MB — stack-allocating TickAggregator
    // overflows the default 8 MB macOS stack. unique_ptr puts it on the heap.
    auto aggregator = std::make_unique<TickAggregator>([&](AggregatorSignal sig) {
        if (sig.type == SignalType::AlertLow || sig.type == SignalType::AlertMedium) {
            // LOW/MEDIUM alerts: publish to anomaly-alerts topic, no order execution
            publish_anomaly_alert(producer, sig);

        } else if (sig.type == SignalType::Scalping) {
            // HIGH signal: publish to urgent-signals AND anomaly-alerts, then execute order
            publish_scalping_signal(producer, sig);

            // Also send to anomaly-alerts as HIGH severity for the frontend
            AggregatorSignal high_alert = sig;
            high_alert.type = SignalType::AlertMedium; // reuse publish, override severity below
            const std::string body = json{
                {"ticker",           std::string(sig.ticker)},
                {"zscore",           sig.zscore},
                {"price_change_pct", sig.price_change_pct},
                {"current_price",    sig.candle.close},
                {"severity",         "HIGH"},
                {"timestamp_ms",     sig.timestamp_ms}
            }.dump();
            auto handle = producer.acquire_buffer();
            if (handle) {
                handle->write(body.data(), body.size());
                std::strncpy(handle->topic, "anomaly-alerts", sizeof(handle->topic) - 1);
                std::strncpy(handle->key, sig.ticker, sizeof(handle->key) - 1);
                handle->key_size = std::strlen(sig.ticker);
                producer.publish(std::move(handle));
            }

            ScalpingEvent event{};
            std::memcpy(event.ticker, sig.ticker, sizeof(event.ticker));
            event.zscore           = sig.zscore;
            event.price_change_pct = sig.price_change_pct;
            event.current_price    = sig.candle.close;
            event.timestamp_ms     = sig.timestamp_ms;
            bus.publish(event);

        } else {  // SignalType::Swing — candle closed
            publish_candle(producer, sig);

            SwingEvent event{};
            event.state              = SwingState::CandleClosed;
            std::memcpy(event.ticker, sig.ticker, sizeof(event.ticker));
            event.open               = sig.candle.open;
            event.high               = sig.candle.high;
            event.low                = sig.candle.low;
            event.close              = sig.candle.close;
            event.volume             = sig.candle.volume;
            event.candle_close_time_ms = sig.candle.close_time_ms;
            event.window_minutes     = sig.candle.window_minutes;
            event.timestamp_ms       = sig.timestamp_ms;
            bus.publish(event);
        }
    });
    aggregator->start();

    // ── 6. Kafka consumer: raw market data → aggregator ───────────────────────
    // api-server publishes StockTick as JSON:
    // {"ticker":"005930","price":"73000","volume":"12345","timestamp":...}
    // Note: price and volume are strings in StockTick (KIS returns strings).
    KafkaConsumer market_consumer(
        brokers, "trading-engine-market",
        {"raw-market-data"},
        [&](const char* payload, std::size_t size,
            const std::string& /*topic*/, int64_t /*ts*/)
        {
            try {
                const auto j = json::parse(payload, payload + size);

                Tick tick{};
                const auto& t = j.at("ticker").get_ref<const std::string&>();
                std::strncpy(tick.ticker, t.c_str(), sizeof(tick.ticker) - 1);
                tick.price        = std::stod(j.at("price").get<std::string>());
                tick.volume       = std::stod(j.at("volume").get<std::string>());
                tick.timestamp_ms = j.at("timestamp").get<int64_t>();

                aggregator->push_tick(tick);

                static std::atomic<long> tick_count{0};
                const long n = ++tick_count;
                if (n <= 5 || n % 50 == 0)
                    fprintf(stdout, "[engine] tick #%ld  ticker=%-8s  price=%.0f  volume=%.0f\n",
                            n, tick.ticker, tick.price, tick.volume);
            } catch (const std::exception& e) {
                fprintf(stderr, "[engine] tick parse error: %s\n", e.what());
            }
        }
    );

    // ── 7. Kafka consumer: AI signals → swing handler ─────────────────────────
    // AI service publishes JSON to "ai-signals":
    // {"ticker":"005930","close":73000.0,"bull_probability":0.75,
    //  "bear_probability":0.15,"direction":"BUY_READY",
    //  "candle_close_time":1234567890000,"timestamp_ms":1234567890123}
    KafkaConsumer ai_consumer(
        brokers, "trading-engine-ai",
        {"ai-signals"},
        [&](const char* payload, std::size_t size,
            const std::string& /*topic*/, int64_t /*ts*/)
        {
            try {
                const auto j = json::parse(payload, payload + size);

                SwingEvent event{};
                event.state = SwingState::AIConfirmed;
                const auto& t = j.at("ticker").get_ref<const std::string&>();
                std::strncpy(event.ticker, t.c_str(), sizeof(event.ticker) - 1);
                event.close                       = j.at("close").get<double>();
                event.ai_signal.bull_probability  = j.at("bull_probability").get<double>();
                event.ai_signal.bear_probability  = j.at("bear_probability").get<double>();
                event.ai_signal.direction         = j.at("direction").get<std::string>();
                event.ai_signal.candle_close_time = j.at("candle_close_time").get<int64_t>();
                event.timestamp_ms                = j.at("timestamp_ms").get<int64_t>();

                bus.publish(event);
            } catch (const std::exception& e) {
                fprintf(stderr, "[engine] AI signal parse error: %s\n", e.what());
            }
        }
    );

    market_consumer.start();
    ai_consumer.start();

    fprintf(stdout, "[engine] started — consuming raw-market-data and ai-signals\n");

    while (g_running.load(std::memory_order_acquire)) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    fprintf(stdout, "[engine] shutting down...\n");

    // 종료 순서: consumer 먼저 멈춰 새 틱 유입 차단 → aggregator 중단 → 나머지는 소멸자
    // Kafka가 다운된 상태에서도 poll_loop가 10ms timeout으로 빠르게 빠져나옴
    market_consumer.stop();
    ai_consumer.stop();
    aggregator->stop();

    fprintf(stdout, "[engine] stopped.\n");
    return 0;
}
