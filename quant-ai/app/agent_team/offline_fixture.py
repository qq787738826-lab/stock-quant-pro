from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
import re
from typing import Any, Iterable

from .backtest_canonical import canonical_hash, canonical_text


FIXTURE_SCHEMA_VERSION = "OFFLINE_PROVIDER_FIXTURE_V1"
PROVIDER_CONTRACT_VERSION = "MARKET_FACT_PROVIDER_CONTRACT_V1"
_SENSITIVE_TOKENS = {
    "authorization",
    "cookie",
    "token",
    "session",
    "password",
    "passwd",
    "secret",
    "username",
    "account",
    "apikey",
    "machinepath",
    "homedir",
    "personalinfo",
}
_SENSITIVE_VALUE = re.compile(
    r"(?i)(?:"
    r"(?:authorization|cookie|token|session|password|passwd|secret"
    r"|api[_-]?key)\s*[:=]"
    r"|https?://[^/\s:@]+:[^@\s]+@"
    r"|[a-z]:\\(?:users|documents and settings)\\"
    r"|/(?:home|users)/[^/\s]+/"
    r")",
)


@dataclass(frozen=True)
class SanitizedFixture:
    value: dict[str, Any]
    canonical: str
    sha256: str


def sanitize_fixture(
    value: Any,
    allowed_top_level_fields: Iterable[str],
) -> SanitizedFixture:
    if not isinstance(value, dict):
        raise ValueError("fixture input must be an object")
    allowed = set(allowed_top_level_fields)
    sanitized = {
        key: _sanitize_node(item)
        for key, item in value.items()
        if key in allowed and not _sensitive(key)
    }
    sanitized["fixtureSchemaVersion"] = FIXTURE_SCHEMA_VERSION
    sanitized["providerContractVersion"] = PROVIDER_CONTRACT_VERSION
    reject_sensitive(sanitized)
    canonical = canonical_text(sanitized)
    return SanitizedFixture(
        value=sanitized,
        canonical=canonical,
        sha256=canonical_hash(sanitized),
    )


def reject_sensitive(value: Any) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            if _sensitive(key):
                raise ValueError(f"sensitive fixture field remains: {key}")
            reject_sensitive(item)
    elif isinstance(value, list):
        for item in value:
            reject_sensitive(item)
    elif isinstance(value, str) and _SENSITIVE_VALUE.search(value):
        raise ValueError("sensitive fixture value remains")


def _sanitize_node(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: _sanitize_node(item)
            for key, item in value.items()
            if not _sensitive(key)
        }
    if isinstance(value, list):
        return [_sanitize_node(item) for item in value]
    if value is None or isinstance(value, (str, int, float, bool)):
        return deepcopy(value)
    raise ValueError(f"unsupported fixture value: {type(value).__name__}")


def _sensitive(name: str) -> bool:
    normalized = name.replace("-", "").replace("_", "").lower()
    return any(token in normalized for token in _SENSITIVE_TOKENS)
