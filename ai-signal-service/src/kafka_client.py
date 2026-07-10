import json
import logging

from confluent_kafka import Consumer, Producer

log = logging.getLogger(__name__)


def build_consumer(brokers: str, group_id: str, topics: list[str]) -> Consumer:
    consumer = Consumer({
        "bootstrap.servers": brokers,
        "group.id": group_id,
        "auto.offset.reset": "latest",  # 서비스 기동 전 과거 메시지는 건너뜀 — 웜스타트는 InfluxDB가 담당
        "enable.auto.commit": True,
    })
    consumer.subscribe(topics)
    return consumer


def build_producer(brokers: str) -> Producer:
    return Producer({"bootstrap.servers": brokers})


def _delivery_report(err, msg):
    if err is not None:
        log.error("Kafka delivery failed: %s", err)


def publish_json(producer: Producer, topic: str, key: str, payload: dict) -> None:
    producer.produce(
        topic,
        key=key.encode("utf-8"),
        value=json.dumps(payload).encode("utf-8"),
        callback=_delivery_report,
    )
    producer.poll(0)
