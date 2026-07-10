import logging
import os
import time

import pandas as pd

from .config import Config

log = logging.getLogger(__name__)


class FeatureLogger:
    """트레이드 발생(BUY_READY/SELL_READY) 여부와 무관하게 매 캔들의
    피처+점수를 Parquet으로 기록한다. 나중에 별도 배치 잡이 이 레코드에
    미래 가격 흐름 기반 triple-barrier 라벨을 채워 넣어 메타모델 학습셋을
    만든다 (지금 이 파일에서는 라벨링 자체는 하지 않음 — 라벨은 미래
    데이터가 있어야 계산 가능하므로 별도 배치로 분리).
    """

    def __init__(self, config: Config):
        self._dir = config.feature_log_dir
        self._flush_every = config.flush_every_n_rows
        self._rows: list[dict] = []
        os.makedirs(self._dir, exist_ok=True)

    def log(self, row: dict) -> None:
        self._rows.append(row)
        if len(self._rows) >= self._flush_every:
            self.flush()

    def flush(self) -> None:
        if not self._rows:
            return
        df = pd.DataFrame(self._rows)
        path = os.path.join(self._dir, f"part-{int(time.time() * 1000)}.parquet")
        df.to_parquet(path, engine="pyarrow", index=False)
        log.info("Flushed %d feature rows to %s", len(self._rows), path)
        self._rows = []
