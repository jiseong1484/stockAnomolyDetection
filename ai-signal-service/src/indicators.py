import pandas as pd

from .config import Config


def _macd_hist(close: pd.Series, fast: int, slow: int, signal: int) -> float:
    ema_fast = close.ewm(span=fast, adjust=False).mean()
    ema_slow = close.ewm(span=slow, adjust=False).mean()
    macd_line = ema_fast - ema_slow
    signal_line = macd_line.ewm(span=signal, adjust=False).mean()
    return float((macd_line - signal_line).iloc[-1])


def _rsi(close: pd.Series, period: int) -> float:
    delta = close.diff()
    gain = delta.clip(lower=0)
    loss = -delta.clip(upper=0)
    avg_gain = gain.ewm(alpha=1 / period, adjust=False).mean()
    avg_loss = loss.ewm(alpha=1 / period, adjust=False).mean().replace(0, 1e-9)
    rs = avg_gain / avg_loss
    rsi = 100 - (100 / (1 + rs))
    return float(rsi.iloc[-1])


def _bb_percent_b(close: pd.Series, period: int, stddev: float) -> float:
    sma = close.rolling(period).mean()
    std = close.rolling(period).std()
    upper = sma + stddev * std
    lower = sma - stddev * std
    band_range = float((upper - lower).iloc[-1])
    if band_range <= 1e-9:
        return 0.5
    return float((close.iloc[-1] - lower.iloc[-1]) / band_range)


def _vwap_deviation_pct(df: pd.DataFrame) -> float:
    # 세션(장중) 리셋 없이 버퍼 윈도우 전체에 대한 누적 VWAP — v1 근사치.
    # 나중에 15m/1h 캔들에 실제 장 시작 시각 태그가 붙으면 일별 리셋으로 정교화 가능.
    typical = (df["high"] + df["low"] + df["close"]) / 3
    cum_vol = df["volume"].cumsum().replace(0, 1e-9)
    cum_tp_vol = (typical * df["volume"]).cumsum()
    vwap = cum_tp_vol / cum_vol
    last_close = float(df["close"].iloc[-1])
    last_vwap = float(vwap.iloc[-1])
    if last_vwap <= 1e-9:
        return 0.0
    return (last_close - last_vwap) / last_vwap * 100.0


def _volume_zscore(volume: pd.Series, period: int) -> float:
    window = volume.iloc[-period:]
    mean = window.mean()
    std = window.std()
    if std <= 1e-9:
        return 0.0
    return float((volume.iloc[-1] - mean) / std)


def compute_all(df: pd.DataFrame, config: Config) -> dict:
    """df는 시간순 정렬된 open/high/low/close/volume 컬럼을 가진 DataFrame."""
    return {
        "macd_hist": _macd_hist(df["close"], config.macd_fast, config.macd_slow, config.macd_signal),
        "rsi": _rsi(df["close"], config.rsi_period),
        "bb_percent_b": _bb_percent_b(df["close"], config.bb_period, config.bb_stddev),
        "vwap_deviation_pct": _vwap_deviation_pct(df),
        "volume_zscore": _volume_zscore(df["volume"], config.volume_zscore_period),
    }
