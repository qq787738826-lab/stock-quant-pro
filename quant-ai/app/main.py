from __future__ import annotations

import json
import os
import queue
import threading
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable
from concurrent.futures import ThreadPoolExecutor, as_completed

import numpy as np
import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

app = FastAPI(title="Stock Quant AI & Data Service", version="1.2.0")


class AnalyzeRequest(BaseModel):
    symbol: str = Field(pattern=r"^\d{6}$")


class ScanBatchRequest(BaseModel):
    symbols: list[str] = Field(min_length=1, max_length=50)
    days: int = Field(default=120, ge=80, le=500)
    includeBars: bool = True
    maxWorkers: int = Field(default=4, ge=1, le=8)


_CACHE_TTL_SECONDS = 300
_HISTORY_CACHE: dict[str, tuple[datetime, pd.DataFrame, str]] = {}
_CACHE_LOCK = threading.Lock()
_DISK_CACHE_DIR = Path(__file__).resolve().parents[1] / "data" / "history-cache"
_UNIVERSE_CACHE_PATH = Path(__file__).resolve().parents[1] / "data" / "universe-cache.json"
_UNIVERSE_MEMORY_CACHE: tuple[datetime, list[dict[str, Any]]] | None = None
_UNIVERSE_LOCK = threading.Lock()


def _exchange_symbol(symbol: str) -> str:
    if symbol.startswith(("6", "9")):
        return f"sh{symbol}"
    if symbol.startswith(("0", "2", "3")):
        return f"sz{symbol}"
    if symbol.startswith(("4", "8")):
        return f"bj{symbol}"
    raise ValueError(f"无法识别股票市场：{symbol}")


def _call_with_timeout(
    function: Callable[[], pd.DataFrame],
    timeout_seconds: float,
    provider_name: str,
) -> pd.DataFrame:
    result_queue: queue.Queue[tuple[str, Any]] = queue.Queue(maxsize=1)

    def runner() -> None:
        try:
            result_queue.put(("ok", function()))
        except Exception as exc:
            result_queue.put(("error", exc))

    thread = threading.Thread(
        target=runner,
        name=f"market-provider-{provider_name}",
        daemon=True,
    )
    thread.start()
    thread.join(timeout_seconds)

    if thread.is_alive():
        raise TimeoutError(f"{provider_name}请求超过{timeout_seconds:.0f}秒")

    status, payload = result_queue.get_nowait()
    if status == "error":
        raise payload
    return payload


def _normalize_history(raw: pd.DataFrame, provider: str) -> pd.DataFrame:
    if raw is None or raw.empty:
        raise RuntimeError("数据源返回空结果")

    rename_map = {
        "日期": "tradeDate",
        "date": "tradeDate",
        "开盘": "open",
        "open": "open",
        "收盘": "close",
        "close": "close",
        "最高": "high",
        "high": "high",
        "最低": "low",
        "low": "low",
        "成交量": "volume",
        "volume": "volume",
        "成交额": "amount",
        "amount": "amount",
        "换手率": "turnoverRate",
        "turnover": "turnoverRate",
        "涨跌幅": "changePct",
    }
    df = raw.rename(columns=rename_map).copy()

    # 腾讯接口的 amount 字段单位为“手”，可作为成交量序列用于量比计算。
    if provider == "AKShare-Tencent" and "volume" not in df.columns and "amount" in df.columns:
        df["volume"] = df["amount"]
        df["amount"] = 0.0

    required = ["tradeDate", "open", "high", "low", "close", "volume"]
    missing = [column for column in required if column not in df.columns]
    if missing:
        raise RuntimeError(f"数据源缺少字段：{','.join(missing)}")

    df["tradeDate"] = pd.to_datetime(df["tradeDate"], errors="coerce")
    numeric_columns = [
        "open",
        "high",
        "low",
        "close",
        "volume",
        "amount",
        "turnoverRate",
        "changePct",
    ]
    for column in numeric_columns:
        if column not in df.columns:
            df[column] = 0.0
        df[column] = pd.to_numeric(df[column], errors="coerce")

    # 新浪 turnover 通常为小数比例，统一转换成百分数。
    if provider == "AKShare-Sina":
        non_null_turnover = df["turnoverRate"].dropna()
        if not non_null_turnover.empty and non_null_turnover.abs().max() <= 1.5:
            df["turnoverRate"] = df["turnoverRate"] * 100

    df = (
        df.dropna(subset=required)
        .sort_values("tradeDate")
        .drop_duplicates(subset=["tradeDate"], keep="last")
        .reset_index(drop=True)
    )

    if len(df) < 80:
        raise RuntimeError(f"有效K线不足，当前仅{len(df)}条")

    return df


def _fetch_tencent(symbol: str, start: str, end: str) -> pd.DataFrame:
    import akshare as ak

    raw = ak.stock_zh_a_hist_tx(
        symbol=_exchange_symbol(symbol),
        start_date=start,
        end_date=end,
        adjust="qfq",
    )
    return _normalize_history(raw, "AKShare-Tencent")


def _fetch_sina(symbol: str, start: str, end: str) -> pd.DataFrame:
    import akshare as ak

    raw = ak.stock_zh_a_daily(
        symbol=_exchange_symbol(symbol),
        start_date=start,
        end_date=end,
        adjust="qfq",
    )
    return _normalize_history(raw, "AKShare-Sina")


def _fetch_eastmoney(symbol: str, start: str, end: str) -> pd.DataFrame:
    import akshare as ak

    raw = ak.stock_zh_a_hist(
        symbol=symbol,
        period="daily",
        start_date=start,
        end_date=end,
        adjust="qfq",
    )
    return _normalize_history(raw, "AKShare-Eastmoney")


def _cache_paths(symbol: str) -> tuple[Path, Path]:
    return (
        _DISK_CACHE_DIR / f"{symbol}.csv",
        _DISK_CACHE_DIR / f"{symbol}.json",
    )


def _save_disk_cache(symbol: str, df: pd.DataFrame, source: str) -> None:
    _DISK_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    csv_path, metadata_path = _cache_paths(symbol)
    df.to_csv(csv_path, index=False, encoding="utf-8-sig")
    metadata_path.write_text(
        json.dumps(
            {
                "symbol": symbol,
                "source": source,
                "savedAt": datetime.now(timezone.utc).isoformat(),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )


def _load_disk_cache(symbol: str) -> tuple[pd.DataFrame, str] | None:
    csv_path, metadata_path = _cache_paths(symbol)
    if not csv_path.exists():
        return None

    try:
        df = pd.read_csv(csv_path)
        df = _normalize_history(df, "Local-Cache")
        source = "Local-Cache"
        if metadata_path.exists():
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            source = f"Local-Cache({metadata.get('source', 'unknown')})"
        return df, source
    except Exception:
        return None


def _load_history(symbol: str, days: int = 260) -> tuple[pd.DataFrame, str]:
    now = datetime.now(timezone.utc)

    with _CACHE_LOCK:
        cached = _HISTORY_CACHE.get(symbol)
        if cached and (now - cached[0]).total_seconds() < _CACHE_TTL_SECONDS:
            return cached[1].tail(days).copy(), cached[2]

    start = (date.today() - timedelta(days=int(days * 1.9) + 160)).strftime("%Y%m%d")
    end = date.today().strftime("%Y%m%d")

    providers: list[tuple[str, float, Callable[[], pd.DataFrame]]] = [
        (
            "AKShare-Tencent",
            8.0,
            lambda: _fetch_tencent(symbol, start, end),
        ),
        (
            "AKShare-Sina",
            6.0,
            lambda: _fetch_sina(symbol, start, end),
        ),
        (
            "AKShare-Eastmoney",
            5.0,
            lambda: _fetch_eastmoney(symbol, start, end),
        ),
    ]

    failures: list[str] = []
    for source, timeout_seconds, loader in providers:
        try:
            df = _call_with_timeout(loader, timeout_seconds, source)
            with _CACHE_LOCK:
                _HISTORY_CACHE[symbol] = (now, df.copy(), source)
            _save_disk_cache(symbol, df, source)
            return df.tail(days).copy(), source
        except Exception as exc:
            failures.append(f"{source}: {exc}")

    disk_cache = _load_disk_cache(symbol)
    if disk_cache is not None:
        df, source = disk_cache
        with _CACHE_LOCK:
            _HISTORY_CACHE[symbol] = (now, df.copy(), source)
        return df.tail(days).copy(), source

    detail = "；".join(failures)
    raise HTTPException(
        503,
        f"全部行情源均不可用且没有本地缓存：{detail}",
    )


def _rsi(close: pd.Series, period: int = 14) -> pd.Series:
    diff = close.diff()
    gain = diff.clip(lower=0)
    loss = -diff.clip(upper=0)
    avg_gain = gain.ewm(alpha=1 / period, min_periods=period, adjust=False).mean()
    avg_loss = loss.ewm(alpha=1 / period, min_periods=period, adjust=False).mean()
    rs = avg_gain / avg_loss.replace(0, np.nan)
    result = 100 - (100 / (1 + rs))
    return result.fillna(100)


def _atr(df: pd.DataFrame, period: int = 14) -> pd.Series:
    previous_close = df["close"].shift(1)
    true_range = pd.concat(
        [
            df["high"] - df["low"],
            (df["high"] - previous_close).abs(),
            (df["low"] - previous_close).abs(),
        ],
        axis=1,
    ).max(axis=1)
    return true_range.ewm(alpha=1 / period, min_periods=period, adjust=False).mean()


def _pct(value: float) -> str:
    return f"{value:+.2f}%"


def _round(value: Any, digits: int = 2) -> float:
    if value is None or pd.isna(value):
        return 0.0
    return round(float(value), digits)


def _dynamic_analysis(symbol: str, df: pd.DataFrame, data_source: str) -> dict[str, Any]:
    work = df.copy()
    close = work["close"]
    volume = work["volume"]

    for period in (5, 10, 20, 60):
        work[f"ma{period}"] = close.rolling(period).mean()

    work["rsi14"] = _rsi(close, 14)
    work["atr14"] = _atr(work, 14)

    ema12 = close.ewm(span=12, adjust=False).mean()
    ema26 = close.ewm(span=26, adjust=False).mean()
    work["macd"] = ema12 - ema26
    work["macdSignal"] = work["macd"].ewm(span=9, adjust=False).mean()
    work["macdHist"] = work["macd"] - work["macdSignal"]

    latest = work.iloc[-1]
    previous = work.iloc[-2]
    latest_close = float(latest["close"])

    ma5 = float(latest["ma5"])
    ma10 = float(latest["ma10"])
    ma20 = float(latest["ma20"])
    ma60 = float(latest["ma60"])
    rsi14 = float(latest["rsi14"])
    atr14 = float(latest["atr14"])
    atr_pct = atr14 / latest_close * 100 if latest_close else 0.0

    return5 = (latest_close / float(work.iloc[-6]["close"]) - 1) * 100
    return20 = (latest_close / float(work.iloc[-21]["close"]) - 1) * 100
    return60 = (latest_close / float(work.iloc[-61]["close"]) - 1) * 100

    average_volume20 = float(volume.iloc[-21:-1].mean())
    volume_ratio20 = float(latest["volume"]) / average_volume20 if average_volume20 else 0.0

    previous_high20 = float(close.iloc[-21:-1].max())
    high20 = float(close.iloc[-20:].max())
    low20 = float(close.iloc[-20:].min())
    breakout20 = latest_close >= previous_high20
    distance_high20 = (latest_close / high20 - 1) * 100 if high20 else 0.0
    drawdown20 = (latest_close / high20 - 1) * 100 if high20 else 0.0
    range_position20 = (
        (latest_close - low20) / (high20 - low20) * 100
        if high20 > low20
        else 50.0
    )

    daily_returns = close.pct_change()
    volatility20 = float(daily_returns.iloc[-20:].std(ddof=0) * 100)
    macd_hist = float(latest["macdHist"])
    previous_macd_hist = float(previous["macdHist"])
    turnover_rate = float(latest.get("turnoverRate", 0.0) or 0.0)

    score = 50
    bullish: list[str] = []
    bearish: list[str] = []

    # Trend structure
    if latest_close > ma20 > ma60:
        score += 15
        bullish.append(f"价格位于20日均线上方，且MA20高于MA60，中期趋势偏强")
    elif latest_close > ma20:
        score += 7
        bullish.append("价格站上20日均线，短线趋势有所改善")
    elif latest_close < ma20 < ma60:
        score -= 15
        bearish.append("价格低于20日均线且MA20低于MA60，趋势结构偏弱")
    else:
        score -= 5
        bearish.append("价格尚未形成清晰的中期多头排列")

    if ma5 > ma10 > ma20:
        score += 8
        bullish.append("MA5、MA10、MA20形成短期多头排列")
    elif ma5 < ma10 < ma20:
        score -= 8
        bearish.append("短期均线呈空头排列")

    # Momentum
    if 50 <= rsi14 <= 70:
        score += 8
        bullish.append(f"RSI14为{rsi14:.1f}，动量处于相对健康区间")
    elif rsi14 > 78:
        score -= 8
        bearish.append(f"RSI14为{rsi14:.1f}，短线存在过热和回撤风险")
    elif rsi14 < 35:
        score -= 5
        bearish.append(f"RSI14为{rsi14:.1f}，当前动量偏弱")
    else:
        bullish.append(f"RSI14为{rsi14:.1f}，暂未出现极端超买")

    if 0 < return20 <= 15:
        score += 7
        bullish.append(f"近20日收益{_pct(return20)}，上涨节奏相对温和")
    elif return20 > 25:
        score -= 7
        bearish.append(f"近20日累计上涨{_pct(return20)}，短线追高风险增加")
    elif return20 < -12:
        score -= 8
        bearish.append(f"近20日收益{_pct(return20)}，阶段走势明显偏弱")

    # Volume and breakout
    if 1.20 <= volume_ratio20 <= 2.50:
        score += 8
        bullish.append(f"当日量比约{volume_ratio20:.2f}，成交量温和放大")
    elif volume_ratio20 > 3.50:
        score -= 5
        bearish.append(f"当日量比约{volume_ratio20:.2f}，放量过快需防冲高回落")
    elif volume_ratio20 < 0.65:
        score -= 3
        bearish.append(f"当日量比约{volume_ratio20:.2f}，市场参与度偏低")

    if breakout20:
        score += 10
        bullish.append("收盘价突破前20个交易日收盘高点")
    elif distance_high20 >= -2.0:
        score += 3
        bullish.append("价格接近20日高位，处于突破观察区")
    elif drawdown20 <= -10:
        score -= 6
        bearish.append(f"较20日高点回撤{abs(drawdown20):.2f}%，修复压力仍在")

    # MACD and volatility
    if macd_hist > 0 and macd_hist >= previous_macd_hist:
        score += 6
        bullish.append("MACD柱线位于零轴上方且继续增强")
    elif macd_hist < 0 and macd_hist < previous_macd_hist:
        score -= 6
        bearish.append("MACD柱线为负且继续走弱")

    if 1.2 <= atr_pct <= 5.5:
        score += 3
        bullish.append(f"ATR占价格{atr_pct:.2f}%，波动水平适合短线观察")
    elif atr_pct > 7:
        score -= 10
        bearish.append(f"ATR占价格{atr_pct:.2f}%，短线波动风险较高")

    if volatility20 > 4.5:
        score -= 7
        bearish.append(f"近20日单日波动率约{volatility20:.2f}%，价格稳定性较弱")

    if 1 <= turnover_rate <= 10:
        score += 2
    elif turnover_rate > 18:
        score -= 4
        bearish.append(f"最新换手率{turnover_rate:.2f}%，筹码交换较为剧烈")

    score = max(0, min(100, int(round(score))))

    if score >= 75:
        signal_level = "STRONG"
    elif score >= 60:
        signal_level = "WATCH"
    elif score >= 45:
        signal_level = "NEUTRAL"
    else:
        signal_level = "WEAK"

    if atr_pct >= 7 or volatility20 >= 4.5 or return20 <= -15:
        risk_level = "HIGH"
    elif atr_pct <= 3.5 and volatility20 <= 2.8 and score >= 65:
        risk_level = "LOW"
    else:
        risk_level = "MEDIUM"

    if not bullish:
        bullish.append("当前尚未出现明确的短线优势信号")
    if not bearish:
        bearish.append("指标暂未出现明显恶化，但仍需核对大盘与公告风险")

    latest_date = pd.Timestamp(latest["tradeDate"]).date().isoformat()
    trend_text = "高于" if latest_close >= ma20 else "低于"
    summary = (
        f"截至{latest_date}，收盘价{latest_close:.2f}元，近5日{_pct(return5)}、"
        f"近20日{_pct(return20)}。当前价格{trend_text}20日均线，"
        f"RSI14为{rsi14:.1f}，量比约{volume_ratio20:.2f}，"
        f"动态规则评分为{score}分。"
    )

    return {
        "symbol": symbol,
        "mode": "LOCAL_RULES_DYNAMIC",
        "dataSource": data_source,
        "tradeDate": latest_date,
        "score": score,
        "signalLevel": signal_level,
        "summary": summary,
        "bullish": bullish,
        "bearish": bearish,
        "riskLevel": risk_level,
        "metrics": {
            "latestClose": _round(latest_close),
            "return5Pct": _round(return5),
            "return20Pct": _round(return20),
            "return60Pct": _round(return60),
            "ma5": _round(ma5),
            "ma10": _round(ma10),
            "ma20": _round(ma20),
            "ma60": _round(ma60),
            "rsi14": _round(rsi14),
            "volumeRatio20": _round(volume_ratio20),
            "atr14Pct": _round(atr_pct),
            "volatility20Pct": _round(volatility20),
            "drawdown20Pct": _round(drawdown20),
            "rangePosition20Pct": _round(range_position20),
            "turnoverRate": _round(turnover_rate),
            "macdHistogram": _round(macd_hist, 4),
            "breakout20": bool(breakout20),
        },
        "disclaimer": "基于历史行情和规则模型生成，仅供投研参考，不构成投资建议。",
    }



def _is_main_board(symbol: str) -> bool:
    return symbol.startswith(("000", "001", "002", "003", "600", "601", "603", "605"))


def _exchange_name(symbol: str) -> str:
    if symbol.startswith(("600", "601", "603", "605")):
        return "SH"
    if symbol.startswith(("000", "001", "002", "003")):
        return "SZ"
    if symbol.startswith(("688", "689")):
        return "SH"
    if symbol.startswith(("300", "301")):
        return "SZ"
    return "BJ"


def _normalize_universe(raw: pd.DataFrame) -> list[dict[str, Any]]:
    if raw is None or raw.empty:
        raise RuntimeError("股票列表数据源返回空结果")

    code_column = next((c for c in ("code", "证券代码", "A股代码", "代码") if c in raw.columns), None)
    name_column = next((c for c in ("name", "证券简称", "A股简称", "名称") if c in raw.columns), None)
    if code_column is None or name_column is None:
        raise RuntimeError(f"股票列表字段无法识别：{list(raw.columns)}")

    records: list[dict[str, Any]] = []
    for _, row in raw.iterrows():
        symbol = str(row.get(code_column, "")).strip()
        if symbol.endswith(".0"):
            symbol = symbol[:-2]
        symbol = symbol.zfill(6)
        name = str(row.get(name_column, "")).strip()
        if not symbol.isdigit() or len(symbol) != 6 or not _is_main_board(symbol):
            continue
        is_st = "ST" in name.upper() or "退" in name
        records.append({
            "symbol": symbol,
            "name": name or symbol,
            "exchange": _exchange_name(symbol),
            "board": "MAIN",
            "isSt": is_st,
            "isActive": "退" not in name,
            "dataSource": "AKShare-CodeName",
        })

    records.sort(key=lambda item: item["symbol"])
    if not records:
        raise RuntimeError("过滤后没有沪深主板股票")
    return records


def _save_universe_cache(records: list[dict[str, Any]]) -> None:
    _UNIVERSE_CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
    _UNIVERSE_CACHE_PATH.write_text(
        json.dumps({
            "savedAt": datetime.now(timezone.utc).isoformat(),
            "records": records,
        }, ensure_ascii=False),
        encoding="utf-8",
    )


def _load_universe_cache() -> list[dict[str, Any]] | None:
    if not _UNIVERSE_CACHE_PATH.exists():
        return None
    try:
        payload = json.loads(_UNIVERSE_CACHE_PATH.read_text(encoding="utf-8"))
        records = payload.get("records", [])
        return records if records else None
    except Exception:
        return None


def _load_universe(include_st: bool = False) -> tuple[list[dict[str, Any]], str]:
    global _UNIVERSE_MEMORY_CACHE
    now = datetime.now(timezone.utc)

    with _UNIVERSE_LOCK:
        if _UNIVERSE_MEMORY_CACHE is not None:
            cached_at, cached_records = _UNIVERSE_MEMORY_CACHE
            if (now - cached_at).total_seconds() < 3600:
                rows = cached_records if include_st else [r for r in cached_records if not r["isSt"]]
                return rows, "Memory-Cache"

    try:
        import akshare as ak
        raw = _call_with_timeout(ak.stock_info_a_code_name, 30.0, "AKShare-CodeName")
        records = _normalize_universe(raw)
        _save_universe_cache(records)
        with _UNIVERSE_LOCK:
            _UNIVERSE_MEMORY_CACHE = (now, records)
        rows = records if include_st else [r for r in records if not r["isSt"]]
        return rows, "AKShare-CodeName"
    except Exception as exc:
        records = _load_universe_cache()
        if records:
            with _UNIVERSE_LOCK:
                _UNIVERSE_MEMORY_CACHE = (now, records)
            rows = records if include_st else [r for r in records if not r["isSt"]]
            return rows, f"Local-Cache({exc.__class__.__name__})"
        raise HTTPException(503, f"股票列表获取失败且没有本地缓存：{exc}") from exc


def _bars_to_records(symbol: str, df: pd.DataFrame, source: str, days: int) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for _, row in df.tail(days).iterrows():
        records.append({
            "symbol": symbol,
            "dataSource": source,
            "tradeDate": pd.Timestamp(row["tradeDate"]).date().isoformat(),
            "open": float(row["open"]),
            "high": float(row["high"]),
            "low": float(row["low"]),
            "close": float(row["close"]),
            "volume": int(row["volume"]),
            "amount": float(row.get("amount", 0) or 0),
            "turnoverRate": float(row.get("turnoverRate", 0) or 0),
        })
    return records


def _scan_one_symbol(symbol: str, days: int, include_bars: bool) -> dict[str, Any]:
    if not symbol.isdigit() or len(symbol) != 6 or not _is_main_board(symbol):
        raise ValueError("仅支持沪深主板6位股票代码")
    history_days = max(days, 260)
    df, source = _load_history(symbol, history_days)
    result = _dynamic_analysis(symbol, df, source)
    if include_bars:
        result["bars"] = _bars_to_records(symbol, df, source, days)
    return result


@app.get("/market/universe")
def universe(includeSt: bool = False) -> dict[str, Any]:
    records, source = _load_universe(includeSt)
    return {
        "source": source,
        "count": len(records),
        "items": records,
    }


@app.post("/market/analyze-batch")
def analyze_batch(req: ScanBatchRequest) -> dict[str, Any]:
    symbols = list(dict.fromkeys(req.symbols))
    items: list[dict[str, Any]] = []
    failures: dict[str, str] = {}

    with ThreadPoolExecutor(max_workers=min(req.maxWorkers, len(symbols))) as executor:
        futures = {
            executor.submit(_scan_one_symbol, symbol, req.days, req.includeBars): symbol
            for symbol in symbols
        }
        for future in as_completed(futures):
            symbol = futures[future]
            try:
                items.append(future.result())
            except Exception as exc:
                detail = exc.detail if isinstance(exc, HTTPException) else str(exc)
                failures[symbol] = detail

    items.sort(key=lambda item: (-int(item.get("score", 0)), item.get("symbol", "")))
    return {
        "requested": len(symbols),
        "success": len(items),
        "failed": len(failures),
        "items": items,
        "failures": failures,
    }


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "UP",
        "provider": "AKShare-MultiSource",
        "providers": ["Tencent", "Sina", "Eastmoney", "Local-Cache"],
        "aiProvider": os.getenv("AI_PROVIDER", "local"),
        "version": "1.2.0",
    }


@app.get("/market/history/{symbol}")
def history(symbol: str, days: int = 120) -> list[dict[str, Any]]:
    if not symbol.isdigit() or len(symbol) != 6:
        raise HTTPException(400, "股票代码必须是6位数字")

    days = max(30, min(days, 3000))
    df, source = _load_history(symbol, days)

    return _bars_to_records(symbol, df, source, days)


@app.post("/ai/analyze")
def analyze(req: AnalyzeRequest) -> dict[str, Any]:
    provider = os.getenv("AI_PROVIDER", "local").lower()

    df, data_source = _load_history(req.symbol, 260)
    result = _dynamic_analysis(req.symbol, df, data_source)

    if provider != "local":
        result["mode"] = f"{provider.upper()}_PENDING"
        result["summary"] = (
            result["summary"]
            + " 当前已配置非本地提供方，但在线模型调用尚未启用，仍返回动态规则结果。"
        )

    return result
