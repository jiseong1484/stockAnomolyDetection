import math

from .config import Config


def _clip(x: float, lo: float = -1.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, x))


def score(indicators: dict, config: Config) -> dict:
    """규칙 기반 스코어링.

    가격 방향 지표(MACD/RSI/BB/VWAP)는 가중합해서 "방향성 점수"를 만들고,
    거래량 Z-score는 방향에 투표하지 않고 방향성 점수의 확신도를 증폭시키는
    용도로만 쓴다 (거래량 자체는 방향이 없는 지표라서).
    """
    macd_signal = _clip(indicators["macd_hist"] / config.macd_norm_divisor)
    rsi_signal = _clip((indicators["rsi"] - 50) / 50)
    bb_signal = _clip((indicators["bb_percent_b"] - 0.5) * 2)
    vwap_signal = _clip(indicators["vwap_deviation_pct"] / config.vwap_norm_divisor)

    price_signal = (
        config.weight_macd * macd_signal
        + config.weight_rsi * rsi_signal
        + config.weight_bb * bb_signal
        + config.weight_vwap * vwap_signal
    )

    vol_z_clipped = _clip(indicators["volume_zscore"], 0.0, config.volume_zscore_cap)
    boost_ratio = vol_z_clipped / config.volume_zscore_cap if config.volume_zscore_cap > 0 else 0.0
    volume_boost = 1.0 + config.volume_boost_weight * boost_ratio

    final_score = _clip(price_signal * volume_boost)

    bull_probability = 1.0 / (1.0 + math.exp(-final_score * config.sensitivity))
    bear_probability = 1.0 - bull_probability

    if bull_probability >= config.buy_threshold:
        direction = "BUY_READY"
    elif bull_probability <= config.sell_threshold:
        direction = "SELL_READY"
    else:
        direction = "NEUTRAL"

    return {
        "price_signal": price_signal,
        "volume_boost": volume_boost,
        "final_score": final_score,
        "bull_probability": bull_probability,
        "bear_probability": bear_probability,
        "direction": direction,
    }
