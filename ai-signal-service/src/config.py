import os
from dataclasses import dataclass, field

import yaml

_CONFIG_PATH = os.path.join(os.path.dirname(__file__), "..", "config.yaml")


@dataclass
class Config:
    raw: dict = field(default_factory=dict)

    # env vars win over config.yaml so docker-compose can inject broker/influx endpoints
    kafka_brokers: str = "localhost:9092"
    candle_topic: str = "candle-data"
    signal_topic: str = "ai-signals"
    consumer_group: str = "ai-signal-service"

    influxdb_url: str = "http://localhost:8086"
    influxdb_token: str = ""
    influxdb_org: str = "stock-org"
    influxdb_bucket: str = "stock-bucket"

    buffer_max_len: int = 60
    buffer_min_len_for_signal: int = 30

    macd_fast: int = 12
    macd_slow: int = 26
    macd_signal: int = 9
    macd_norm_divisor: float = 500.0
    rsi_period: int = 14
    bb_period: int = 20
    bb_stddev: float = 2.0
    vwap_norm_divisor: float = 3.0
    volume_zscore_period: int = 20
    volume_zscore_cap: float = 5.0

    weight_macd: float = 0.30
    weight_rsi: float = 0.25
    weight_bb: float = 0.25
    weight_vwap: float = 0.20
    volume_boost_weight: float = 0.5
    sensitivity: float = 4.0
    buy_threshold: float = 0.65
    sell_threshold: float = 0.35

    feature_log_dir: str = "/data/swing_samples"
    flush_every_n_rows: int = 50


def load_config(path: str = _CONFIG_PATH) -> Config:
    with open(path, "r", encoding="utf-8") as f:
        raw = yaml.safe_load(f)

    cfg = Config(raw=raw)

    k = raw.get("kafka", {})
    cfg.candle_topic = k.get("candle_topic", cfg.candle_topic)
    cfg.signal_topic = k.get("signal_topic", cfg.signal_topic)
    cfg.consumer_group = k.get("consumer_group", cfg.consumer_group)

    i = raw.get("influxdb", {})
    cfg.influxdb_org = i.get("org", cfg.influxdb_org)
    cfg.influxdb_bucket = i.get("bucket", cfg.influxdb_bucket)

    b = raw.get("buffer", {})
    cfg.buffer_max_len = b.get("max_len", cfg.buffer_max_len)
    cfg.buffer_min_len_for_signal = b.get("min_len_for_signal", cfg.buffer_min_len_for_signal)

    ind = raw.get("indicators", {})
    cfg.macd_fast = ind.get("macd_fast", cfg.macd_fast)
    cfg.macd_slow = ind.get("macd_slow", cfg.macd_slow)
    cfg.macd_signal = ind.get("macd_signal", cfg.macd_signal)
    cfg.macd_norm_divisor = ind.get("macd_norm_divisor", cfg.macd_norm_divisor)
    cfg.rsi_period = ind.get("rsi_period", cfg.rsi_period)
    cfg.bb_period = ind.get("bb_period", cfg.bb_period)
    cfg.bb_stddev = ind.get("bb_stddev", cfg.bb_stddev)
    cfg.vwap_norm_divisor = ind.get("vwap_norm_divisor", cfg.vwap_norm_divisor)
    cfg.volume_zscore_period = ind.get("volume_zscore_period", cfg.volume_zscore_period)
    cfg.volume_zscore_cap = ind.get("volume_zscore_cap", cfg.volume_zscore_cap)

    s = raw.get("scoring", {})
    cfg.weight_macd = s.get("weight_macd", cfg.weight_macd)
    cfg.weight_rsi = s.get("weight_rsi", cfg.weight_rsi)
    cfg.weight_bb = s.get("weight_bb", cfg.weight_bb)
    cfg.weight_vwap = s.get("weight_vwap", cfg.weight_vwap)
    cfg.volume_boost_weight = s.get("volume_boost_weight", cfg.volume_boost_weight)
    cfg.sensitivity = s.get("sensitivity", cfg.sensitivity)
    cfg.buy_threshold = s.get("buy_threshold", cfg.buy_threshold)
    cfg.sell_threshold = s.get("sell_threshold", cfg.sell_threshold)

    log = raw.get("logging", {})
    cfg.feature_log_dir = log.get("feature_log_dir", cfg.feature_log_dir)
    cfg.flush_every_n_rows = log.get("flush_every_n_rows", cfg.flush_every_n_rows)

    # 환경변수가 최종 우선순위 (docker-compose에서 주입)
    cfg.kafka_brokers = os.environ.get("KAFKA_BROKERS", cfg.kafka_brokers)
    cfg.influxdb_url = os.environ.get("INFLUXDB_URL", cfg.influxdb_url)
    cfg.influxdb_token = os.environ.get("INFLUXDB_TOKEN", cfg.influxdb_token)

    return cfg
