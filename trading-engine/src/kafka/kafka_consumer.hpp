#pragma once

#include <librdkafka/rdkafkacpp.h>

#include <functional>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include <atomic>
#include <thread>

namespace trading {

using MessageHandler = std::function<void(const char* payload, std::size_t size,
                                          const std::string& topic,
                                          int64_t timestamp_ms)>;

// Thin wrapper around librdkafka consumer.
// Spawns a background poll thread; calls handler for each received message.
// Deserialization (Protobuf decode) happens inside the handler, NOT here,
// so the consumer wrapper stays format-agnostic.
class KafkaConsumer {
public:
    KafkaConsumer(const std::string&              brokers,
                  const std::string&              group_id,
                  const std::vector<std::string>& topics,
                  MessageHandler                  handler)
        : handler_(std::move(handler))
    {
        std::string err;
        auto* conf  = RdKafka::Conf::create(RdKafka::Conf::CONF_GLOBAL);

        auto set = [&](const char* key, const std::string& val) {
            if (conf->set(key, val, err) != RdKafka::Conf::CONF_OK)
                throw std::runtime_error(std::string("Kafka conf: ") + err);
        };

        set("bootstrap.servers",         brokers);
        set("group.id",                  group_id);
        set("auto.offset.reset",         "latest");
        set("enable.auto.commit",        "true");
        set("auto.commit.interval.ms",   "1000");
        set("fetch.min.bytes",           "1");       // minimize latency
        set("fetch.wait.max.ms",         "10");

        consumer_.reset(RdKafka::KafkaConsumer::create(conf, err));
        if (!consumer_) {
            delete conf;  // create() frees conf only on success; free it on failure
            throw std::runtime_error("Failed to create Kafka consumer: " + err);
        }
        // conf is owned and freed by the consumer on success — do NOT delete here

        const RdKafka::ErrorCode ec = consumer_->subscribe(topics);
        if (ec != RdKafka::ERR_NO_ERROR)
            throw std::runtime_error("Subscribe failed: " + RdKafka::err2str(ec));
    }

    ~KafkaConsumer() { stop(); }

    void start() {
        running_.store(true, std::memory_order_release);
        poll_thread_ = std::thread(&KafkaConsumer::poll_loop, this);
    }

    void stop() {
        running_.store(false, std::memory_order_release);
        if (poll_thread_.joinable()) poll_thread_.join();
        consumer_->close();
    }

private:
    void poll_loop() {
        while (running_.load(std::memory_order_acquire)) {
            std::unique_ptr<RdKafka::Message> msg(consumer_->consume(10 /* timeout_ms */));

            switch (msg->err()) {
            case RdKafka::ERR_NO_ERROR:
                handler_(
                    static_cast<const char*>(msg->payload()),
                    msg->len(),
                    msg->topic_name(),
                    msg->timestamp().timestamp
                );
                break;

            case RdKafka::ERR__TIMED_OUT:
                break; // normal — no message in window

            case RdKafka::ERR__PARTITION_EOF:
                break; // caught up with head of partition

            default:
                fprintf(stderr, "[kafka] consume error: %s\n",
                        msg->errstr().c_str());
                break;
            }
        }
    }

    MessageHandler                           handler_;
    std::unique_ptr<RdKafka::KafkaConsumer>  consumer_;
    std::thread                              poll_thread_;
    std::atomic<bool>                        running_{false};
};

} // namespace trading
