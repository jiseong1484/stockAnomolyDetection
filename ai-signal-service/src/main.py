import json
import logging
import signal

from .candle_buffer import CandleBuffer
from .config import load_config
from .feature_logger import FeatureLogger
from .indicators import compute_all
from .kafka_client import build_consumer, build_producer, publish_json
from .scorer import score

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("ai-signal-service")

_running = True


def _handle_signal(signum, frame):
    global _running
    log.info("Shutdown signal received (%s)", signum)
    _running = False


def _to_candle(payload: dict) -> tuple[str, str, dict]:
    ticker = payload["ticker"]
    interval = "1h" if payload["window_minutes"] == 60 else "15m"
    candle = {
        "close_time_ms": payload["close_time_ms"],
        "open": payload["open"],
        "high": payload["high"],
        "low": payload["low"],
        "close": payload["close"],
        "volume": payload["volume"],
    }
    return ticker, interval, candle


def main() -> None:
    signal.signal(signal.SIGINT, _handle_signal)
    signal.signal(signal.SIGTERM, _handle_signal)

    config = load_config()
    consumer = build_consumer(config.kafka_brokers, config.consumer_group, [config.candle_topic])
    producer = build_producer(config.kafka_brokers)
    buffer = CandleBuffer(config)
    feature_logger = FeatureLogger(config)

    log.info("ai-signal-service started — consuming %s, publishing %s",
              config.candle_topic, config.signal_topic)

    while _running:
        msg = consumer.poll(1.0)
        if msg is None:
            continue
        if msg.error():
            log.error("Kafka error: %s", msg.error())
            continue

        try:
            payload = json.loads(msg.value())
            ticker, interval, candle = _to_candle(payload)
        except Exception as e:  # noqa: BLE001 — 잘못된 메시지 하나 때문에 루프가 죽으면 안 됨
            log.error("Failed to parse candle payload: %s", e)
            continue

        buffer.append(ticker, interval, candle)
        df = buffer.get_dataframe(ticker, interval)
        if df is None:
            continue  # 지표 계산할 만큼 아직 안 쌓임 — 조용히 축적만 계속

        try:
            indicators = compute_all(df, config)
            result = score(indicators, config)
        except Exception as e:  # noqa: BLE001
            log.error("Scoring failed for %s/%s: %s", ticker, interval, e)
            continue

        signal_payload = {
            "ticker": ticker,
            "close": candle["close"],
            "bull_probability": result["bull_probability"],
            "bear_probability": result["bear_probability"],
            "direction": result["direction"],
            "candle_close_time": candle["close_time_ms"],
            "timestamp_ms": candle["close_time_ms"],
        }
        publish_json(producer, config.signal_topic, ticker, signal_payload)

        feature_logger.log({
            "ticker": ticker,
            "interval": interval,
            **candle,
            **indicators,
            **result,
        })

        if result["direction"] != "NEUTRAL":
            log.info("[signal] ticker=%s interval=%s dir=%s bull=%.3f",
                      ticker, interval, result["direction"], result["bull_probability"])

    log.info("Shutting down...")
    feature_logger.flush()
    producer.flush(5)
    consumer.close()


if __name__ == "__main__":
    main()
