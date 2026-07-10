import logging
from collections import deque

import pandas as pd
from influxdb_client import InfluxDBClient

from .config import Config

log = logging.getLogger(__name__)


class CandleBuffer:
    """종목+interval 별 최근 N개 캔들을 인메모리로 들고 있는다.

    지표 계산(MACD/RSI/BB)에는 여러 개의 과거 캔들이 필요한데, Kafka는
    닫힌 캔들을 하나씩만 보내주므로 서비스가 재시작해도 바로 지표를 낼 수
    있도록 최초 1회 InfluxDB에서 최근 캔들을 읽어와 버퍼를 채운다(웜스타트).
    InfluxDB에 아직 데이터가 없으면(축적 초기) 그냥 빈 채로 시작 — 실시간
    입력만으로 min_len_for_signal 만큼 쌓이면 자연히 신호를 내기 시작한다.
    """

    def __init__(self, config: Config):
        self.config = config
        self._buffers: dict[tuple[str, str], deque] = {}
        self._warm_started: set[tuple[str, str]] = set()
        try:
            self._influx = InfluxDBClient(
                url=config.influxdb_url, token=config.influxdb_token, org=config.influxdb_org
            )
            self._query_api = self._influx.query_api()
        except Exception as e:  # noqa: BLE001 — 웜스타트는 best-effort
            log.warning("InfluxDB client init failed, warm-start disabled: %s", e)
            self._query_api = None

    def _warm_start(self, ticker: str, interval: str) -> None:
        key = (ticker, interval)
        if key in self._warm_started or self._query_api is None:
            return
        self._warm_started.add(key)

        flux = f'''
        from(bucket: "{self.config.influxdb_bucket}")
          |> range(start: -30d)
          |> filter(fn: (r) => r["_measurement"] == "stock_prices")
          |> filter(fn: (r) => r["ticker"] == "{ticker}")
          |> filter(fn: (r) => r["interval"] == "{interval}")
          |> filter(fn: (r) => r["_field"] == "open" or r["_field"] == "high" or r["_field"] == "low" or r["_field"] == "close" or r["_field"] == "volume")
          |> pivot(rowKey:["_time"], columnKey: ["_field"], valueColumn: "_value")
          |> sort(columns: ["_time"], desc: true)
          |> limit(n: {self.config.buffer_max_len})
        '''
        try:
            tables = self._query_api.query(flux, org=self.config.influxdb_org)
            rows = []
            for table in tables:
                for record in table.records:
                    rows.append({
                        "close_time_ms": int(record.get_time().timestamp() * 1000),
                        "open": record.values.get("open"),
                        "high": record.values.get("high"),
                        "low": record.values.get("low"),
                        "close": record.values.get("close"),
                        "volume": record.values.get("volume"),
                    })
            rows.reverse()  # 오래된 것부터 정렬
            self._buffers[key] = deque(rows, maxlen=self.config.buffer_max_len)
            log.info("Warm-started %s/%s with %d candles from InfluxDB", ticker, interval, len(rows))
        except Exception as e:  # noqa: BLE001
            log.warning("Warm-start query failed for %s/%s: %s", ticker, interval, e)

    def append(self, ticker: str, interval: str, candle: dict) -> None:
        key = (ticker, interval)
        if key not in self._buffers:
            self._warm_start(ticker, interval)
        if key not in self._buffers:
            self._buffers[key] = deque(maxlen=self.config.buffer_max_len)
        self._buffers[key].append(candle)

    def get_dataframe(self, ticker: str, interval: str) -> pd.DataFrame | None:
        buf = self._buffers.get((ticker, interval))
        if buf is None or len(buf) < self.config.buffer_min_len_for_signal:
            return None
        return pd.DataFrame(list(buf))
