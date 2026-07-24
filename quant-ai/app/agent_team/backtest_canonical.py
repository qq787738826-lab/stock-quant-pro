from __future__ import annotations

from decimal import Decimal, InvalidOperation
import hashlib
import json
import math
import unicodedata
from typing import Any


CANONICAL_CONTRACT_VERSION = "BACKTEST_CANONICAL_V1"


def canonical_text(value: Any) -> str:
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, str):
        return json.dumps(
            unicodedata.normalize("NFC", value),
            ensure_ascii=False,
            separators=(",", ":"),
        )
    if isinstance(value, dict):
        normalized: dict[str, Any] = {}
        for raw_key, item in value.items():
            if not isinstance(raw_key, str):
                raise ValueError("BACKTEST_CANONICAL_V1对象字段名必须是字符串")
            key = unicodedata.normalize("NFC", raw_key)
            if key in normalized:
                raise ValueError("BACKTEST_CANONICAL_V1字段NFC规范化后重复")
            normalized[key] = item
        return "{" + ",".join(
            f"{canonical_text(key)}:{canonical_text(normalized[key])}"
            for key in sorted(normalized)
        ) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(canonical_text(item) for item in value) + "]"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, Decimal):
        return _canonical_decimal(value)
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("BACKTEST_CANONICAL_V1禁止NaN和Infinity")
        return _canonical_decimal(Decimal(str(value)))
    raise ValueError(f"BACKTEST_CANONICAL_V1不支持类型：{type(value).__name__}")


def canonical_hash(value: Any) -> str:
    return hashlib.sha256(canonical_text(value).encode("utf-8")).hexdigest()


def decimal_value(value: Any) -> Decimal:
    if isinstance(value, bool) or value is None:
        raise ValueError("布尔值或null不是Decimal")
    if isinstance(value, Decimal):
        result = value
    elif isinstance(value, (int, float, str)):
        if isinstance(value, float) and not math.isfinite(value):
            raise ValueError("Decimal禁止NaN和Infinity")
        try:
            result = Decimal(str(value))
        except InvalidOperation as error:
            raise ValueError("非法Decimal") from error
    else:
        raise ValueError("非法Decimal类型")
    if not result.is_finite():
        raise ValueError("Decimal禁止NaN和Infinity")
    return result


def _canonical_decimal(value: Decimal) -> str:
    if not value.is_finite():
        raise ValueError("BACKTEST_CANONICAL_V1禁止NaN和Infinity")
    if value == 0:
        return "0"
    text = format(value.normalize(), "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text
