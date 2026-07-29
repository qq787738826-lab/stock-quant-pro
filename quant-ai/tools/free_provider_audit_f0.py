from __future__ import annotations

import argparse
from collections.abc import Callable, Iterable, Mapping, Sequence
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import dataclass
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import importlib
from importlib import metadata as importlib_metadata
import inspect
import io
import json
from pathlib import Path
import re
import socket
import tempfile
from typing import Any
import unicodedata


AUDIT_SCHEMA_VERSION = "FREE_PROVIDER_AUDIT_F0_SUMMARY_V1"
AUDIT_POLICY_VERSION = "FREE_PROVIDER_AUDIT_F0_POLICY_V1"
BAOSTOCK_EXPECTED_VERSION = "0.9.3"
BAOSTOCK_UPSTREAM_DOMAIN = "public-api.baostock.com"
FIXED_SYMBOLS = ("sh.600000", "sz.000001")
FIXED_START_DATE = "2025-06-03"
FIXED_END_DATE = "2025-06-10"
MAX_PROVIDER_LOGICAL_CALLS = 10
SOCKET_TIMEOUT_SECONDS = 30

HISTORY_FIELDS = (
    "date,code,open,high,low,close,preclose,volume,amount,"
    "adjustflag,turn,isST"
)

SENSITIVE_KEY_FRAGMENTS = {
    "authorization",
    "cookie",
    "credential",
    "email",
    "mail",
    "mobile",
    "password",
    "phone",
    "secret",
    "session",
    "token",
    "username",
    "userid",
}
MARKET_VALUE_KEYS = {
    "open",
    "high",
    "low",
    "close",
    "preclose",
    "volume",
    "amount",
    "turn",
    "turnoverrate",
    "foreadjustfactor",
    "backadjustfactor",
    "adjustfactor",
}
DATE_FIELD_NAMES = {
    "date",
    "calendardate",
    "dividoperatedate",
    "dividpreannouncementdate",
    "dividagmpummeetdate",
    "dividplanannouncedate",
}
AUTH_OR_ACCESS_PATTERNS = (
    "captcha",
    "verification code",
    "验证码",
    "403",
    "429",
    "authentication",
    "authorization required",
)
NETWORK_ERROR_PATTERNS = (
    "network",
    "socket",
    "timeout",
    "timed out",
    "connection",
    "网络",
)


class AuditPolicyError(ValueError):
    """Raised when an F0 probe would leave the frozen safety boundary."""


@dataclass(frozen=True)
class ProbeSpec:
    stable_call_id: str
    public_function: str
    fact_class: str
    symbol: str | None = None
    start_date: str | None = None
    end_date: str | None = None
    adjust_flag: str | None = None
    year: str | None = None
    year_type: str | None = None


class CallBudget:
    def __init__(self, maximum: int = MAX_PROVIDER_LOGICAL_CALLS) -> None:
        if maximum < 0 or maximum > MAX_PROVIDER_LOGICAL_CALLS:
            raise AuditPolicyError("F0_INVALID_CALL_BUDGET")
        self.maximum = maximum
        self.used = 0

    def consume(self) -> None:
        if self.used >= self.maximum:
            raise AuditPolicyError("F0_CALL_BUDGET_EXCEEDED")
        self.used += 1


PROBE_SPECS = (
    ProbeSpec(
        "F0-BAO-002",
        "query_history_k_data_plus",
        "RAW_DAILY_BAR",
        "sh.600000",
        FIXED_START_DATE,
        FIXED_END_DATE,
        "3",
    ),
    ProbeSpec(
        "F0-BAO-003",
        "query_history_k_data_plus",
        "RAW_DAILY_BAR",
        "sz.000001",
        FIXED_START_DATE,
        FIXED_END_DATE,
        "3",
    ),
    ProbeSpec(
        "F0-BAO-004",
        "query_history_k_data_plus",
        "ADJUSTED_DAILY_BAR",
        "sh.600000",
        FIXED_START_DATE,
        FIXED_END_DATE,
        "2",
    ),
    ProbeSpec(
        "F0-BAO-005",
        "query_history_k_data_plus",
        "ADJUSTED_DAILY_BAR",
        "sz.000001",
        FIXED_START_DATE,
        FIXED_END_DATE,
        "2",
    ),
    ProbeSpec(
        "F0-BAO-006",
        "query_trade_dates",
        "TRADING_CALENDAR",
        start_date=FIXED_START_DATE,
        end_date=FIXED_END_DATE,
    ),
    ProbeSpec(
        "F0-BAO-007",
        "query_dividend_data",
        "CORPORATE_ACTION",
        symbol="sh.600000",
        year="2025",
        year_type="operate",
    ),
    ProbeSpec(
        "F0-BAO-008",
        "query_adjust_factor",
        "ADJUSTMENT_FACTOR",
        "sh.600000",
        FIXED_START_DATE,
        FIXED_END_DATE,
    ),
    ProbeSpec(
        "F0-BAO-009",
        "query_adjust_factor",
        "ADJUSTMENT_FACTOR",
        "sz.000001",
        FIXED_START_DATE,
        FIXED_END_DATE,
    ),
)


def _normalized_key(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.casefold())


def _is_sensitive_key(value: str) -> bool:
    normalized = _normalized_key(value)
    return any(fragment in normalized for fragment in SENSITIVE_KEY_FRAGMENTS)


def redact_sensitive(value: Any) -> Any:
    if isinstance(value, Mapping):
        sanitized: dict[str, Any] = {}
        for raw_key, raw_value in value.items():
            key = str(raw_key)
            if _is_sensitive_key(key):
                sanitized[key] = "[REDACTED]"
            else:
                sanitized[key] = redact_sensitive(raw_value)
        return sanitized
    if isinstance(value, list):
        return [redact_sensitive(item) for item in value]
    if isinstance(value, tuple):
        return [redact_sensitive(item) for item in value]
    return value


def canonical_json(value: Any) -> str:
    normalized = _normalize_canonical(value)
    return json.dumps(
        normalized,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def _normalize_canonical(value: Any) -> Any:
    if value is None or isinstance(value, (bool, int)):
        return value
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise AuditPolicyError("F0_NON_FINITE_DECIMAL")
        normalized = value.normalize()
        if normalized == 0:
            return "0"
        return format(normalized, "f")
    if isinstance(value, float):
        if value != value or value in (float("inf"), float("-inf")):
            raise AuditPolicyError("F0_NON_FINITE_FLOAT")
        return _normalize_canonical(Decimal(str(value)))
    if isinstance(value, str):
        return unicodedata.normalize("NFC", value)
    if isinstance(value, Mapping):
        return {
            unicodedata.normalize("NFC", str(key)): _normalize_canonical(item)
            for key, item in value.items()
        }
    if isinstance(value, (list, tuple)):
        return [_normalize_canonical(item) for item in value]
    raise AuditPolicyError(f"F0_UNSUPPORTED_CANONICAL_TYPE:{type(value).__name__}")


def validate_probe_scope(spec: ProbeSpec) -> None:
    if spec.symbol is not None and spec.symbol not in FIXED_SYMBOLS:
        raise AuditPolicyError("F0_SYMBOL_NOT_ALLOWED")
    if spec.start_date is not None and spec.start_date != FIXED_START_DATE:
        raise AuditPolicyError("F0_DATE_RANGE_NOT_ALLOWED")
    if spec.end_date is not None and spec.end_date != FIXED_END_DATE:
        raise AuditPolicyError("F0_DATE_RANGE_NOT_ALLOWED")
    if spec.public_function == "query_daily_adjust_factor":
        raise AuditPolicyError("F0_FULL_MARKET_CALL_NOT_ALLOWED")
    if spec.public_function == "query_dividend_data":
        if spec.symbol != "sh.600000" or spec.year != "2025":
            raise AuditPolicyError("F0_CORPORATE_ACTION_SCOPE_NOT_ALLOWED")
        if spec.year_type != "operate":
            raise AuditPolicyError("F0_CORPORATE_ACTION_SCOPE_NOT_ALLOWED")


def _utc_now(clock: Callable[[], datetime] | None = None) -> datetime:
    current = clock() if clock is not None else datetime.now(timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)
    return current.astimezone(timezone.utc)


def _format_time(value: datetime) -> str:
    return value.isoformat(timespec="microseconds").replace("+00:00", "Z")


def _safe_error_class(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    folded = text.casefold()
    if any(pattern in folded for pattern in AUTH_OR_ACCESS_PATTERNS):
        return "ACCESS_CONTROL"
    if any(pattern in folded for pattern in NETWORK_ERROR_PATTERNS):
        return "NETWORK"
    return "PROVIDER_ERROR"


def _collect_result_rows(
    result: Any,
) -> tuple[list[str], list[list[Any]], str, Any]:
    fields = [str(field) for field in getattr(result, "fields", [])]
    rows: list[list[Any]] = []
    while getattr(result, "error_code", None) == "0" and result.next():
        row = list(result.get_row_data())
        if len(row) != len(fields):
            raise AuditPolicyError("F0_PROVIDER_STRUCTURE_CHANGED")
        rows.append(row)
    terminal_error_code = str(
        getattr(result, "error_code", "") or ""
    )
    terminal_error_message = getattr(result, "error_msg", None)
    return (
        fields,
        rows,
        terminal_error_code,
        terminal_error_message,
    )


def _decimal_scale(value: Any) -> int | None:
    if value is None or str(value).strip() == "":
        return None
    try:
        decimal = Decimal(str(value).strip())
    except (InvalidOperation, ValueError):
        return None
    if not decimal.is_finite():
        return None
    return max(0, -decimal.as_tuple().exponent)


def _inferred_type(values: Sequence[Any]) -> str:
    present = [str(value).strip() for value in values
               if value is not None and str(value).strip() != ""]
    if not present:
        return "EMPTY"
    if all(_parse_date(value) is not None for value in present):
        return "DATE"
    if all(re.fullmatch(r"[+-]?\d+", value) for value in present):
        return "INTEGER"
    if all(_decimal_scale(value) is not None for value in present):
        return "DECIMAL"
    return "TEXT"


def _parse_date(value: str) -> date | None:
    try:
        return date.fromisoformat(value)
    except ValueError:
        return None


def _safe_field_name(value: str) -> str:
    if _is_sensitive_key(value):
        return "[REDACTED_FIELD]"
    return value


def _field_statistics(
    fields: Sequence[str],
    rows: Sequence[Sequence[Any]],
) -> list[dict[str, Any]]:
    statistics: list[dict[str, Any]] = []
    for index, field in enumerate(fields):
        values = [row[index] for row in rows]
        present = [
            value for value in values
            if value is not None and str(value).strip() != ""
        ]
        scales = [
            scale for scale in (_decimal_scale(value) for value in present)
            if scale is not None
        ]
        explicit_zero = any(
            _decimal_scale(value) is not None
            and Decimal(str(value).strip()) == 0
            for value in present
        )
        statistics.append({
            "field": _safe_field_name(field),
            "inferredType": _inferred_type(values),
            "nullCount": len(values) - len(present),
            "explicitZeroPresent": explicit_zero,
            "decimalScaleMin": min(scales) if scales else None,
            "decimalScaleMax": max(scales) if scales else None,
        })
    return statistics


def _date_range(
    fields: Sequence[str],
    rows: Sequence[Sequence[Any]],
) -> dict[str, str] | None:
    for index, field in enumerate(fields):
        if _normalized_key(field) not in DATE_FIELD_NAMES:
            continue
        parsed = [
            parsed_date
            for row in rows
            if (parsed_date := _parse_date(str(row[index]).strip())) is not None
        ]
        if parsed:
            return {
                "field": _safe_field_name(field),
                "start": min(parsed).isoformat(),
                "end": max(parsed).isoformat(),
            }
    return None


def _duplicate_natural_key_count(
    fields: Sequence[str],
    rows: Sequence[Sequence[Any]],
) -> int:
    normalized = [_normalized_key(field) for field in fields]
    key_indexes = [
        index for index, name in enumerate(normalized)
        if name in {
            "date",
            "code",
            "calendardate",
            "dividoperatedate",
            "dividpreannouncementdate",
        }
    ]
    if not key_indexes:
        return 0
    seen: set[tuple[str, ...]] = set()
    duplicates = 0
    for row in rows:
        key = tuple(str(row[index]) for index in key_indexes)
        if key in seen:
            duplicates += 1
        else:
            seen.add(key)
    return duplicates


def _assert_summary_has_no_market_values(value: Any) -> None:
    if isinstance(value, Mapping):
        for key, item in value.items():
            if _normalized_key(str(key)) in MARKET_VALUE_KEYS:
                raise AuditPolicyError("F0_MARKET_VALUE_IN_SUMMARY")
            _assert_summary_has_no_market_values(item)
    elif isinstance(value, list):
        for item in value:
            _assert_summary_has_no_market_values(item)


def _call_provider(provider: Any, spec: ProbeSpec) -> Any:
    function = getattr(provider, spec.public_function)
    if spec.public_function == "query_history_k_data_plus":
        return function(
            spec.symbol,
            HISTORY_FIELDS,
            start_date=spec.start_date,
            end_date=spec.end_date,
            frequency="d",
            adjustflag=spec.adjust_flag,
        )
    if spec.public_function == "query_trade_dates":
        return function(start_date=spec.start_date, end_date=spec.end_date)
    if spec.public_function == "query_dividend_data":
        return function(
            code=spec.symbol,
            year=spec.year,
            yearType=spec.year_type,
        )
    if spec.public_function == "query_adjust_factor":
        return function(
            code=spec.symbol,
            start_date=spec.start_date,
            end_date=spec.end_date,
        )
    raise AuditPolicyError("F0_FUNCTION_NOT_ALLOWED")


def _probe_one(
    provider: Any,
    spec: ProbeSpec,
    budget: CallBudget,
    raw_directory: Path,
    clock: Callable[[], datetime] | None,
) -> tuple[dict[str, Any], str | None]:
    validate_probe_scope(spec)
    budget.consume()
    started = _utc_now(clock)
    result: Any = None
    status = "ERROR"
    error_code: str | None = None
    error_class: str | None = None
    fields: list[str] = []
    rows: list[list[Any]] = []
    try:
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            result = _call_provider(provider, spec)
        (
            fields,
            rows,
            error_code,
            terminal_error_message,
        ) = _collect_result_rows(result)
        if error_code == "0":
            status = "SUCCESS" if rows else "EMPTY"
            error_class = None
        else:
            status = "PARTIAL" if rows else "ERROR"
            error_class = (
                _safe_error_class(terminal_error_message)
                or "PROVIDER_ERROR"
            )
    except (TimeoutError, socket.timeout):
        status = "TIMEOUT"
        error_class = "NETWORK"
    except AuditPolicyError as exc:
        status = (
            "STRUCTURE_CHANGED"
            if str(exc) == "F0_PROVIDER_STRUCTURE_CHANGED"
            else "ERROR"
        )
        error_class = str(exc)
    except Exception as exc:  # provider boundary must produce stable output
        status = "ERROR"
        error_class = _safe_error_class(type(exc).__name__)

    finished = _utc_now(clock)
    raw_payload = {
        "stableCallId": spec.stable_call_id,
        "errorCode": error_code,
        "fields": fields,
        "rows": rows,
    }
    raw_path = raw_directory / f"{spec.stable_call_id}.json"
    raw_path.write_text(
        canonical_json(raw_payload),
        encoding="utf-8",
    )
    raw_hash = hashlib.sha256(raw_path.read_bytes()).hexdigest()
    raw_path.unlink()

    summary = {
        "stableCallId": spec.stable_call_id,
        "providerCandidate": "BAOSTOCK",
        "clientVersion": BAOSTOCK_EXPECTED_VERSION,
        "upstreamProvider": "BaoStock data service",
        "upstreamDomain": BAOSTOCK_UPSTREAM_DOMAIN,
        "transport": "TCP_SOCKET",
        "publicFunction": spec.public_function,
        "factClass": spec.fact_class,
        "requestSymbol": spec.symbol,
        "requestStartDate": spec.start_date,
        "requestEndDate": spec.end_date,
        "capturedAt": _format_time(started),
        "finishedAt": _format_time(finished),
        "status": status,
        "errorCode": error_code,
        "errorClass": error_class,
        "rowCount": len(rows),
        "fields": [_safe_field_name(field) for field in fields],
        "fieldStatistics": _field_statistics(fields, rows),
        "duplicateNaturalKeyCount": _duplicate_natural_key_count(
            fields,
            rows,
        ),
        "dateRange": _date_range(fields, rows),
        "responseComplete": status in {"SUCCESS", "EMPTY"},
        "rawResponseSha256": raw_hash,
        "rawResponsePersisted": False,
        "temporaryRawFileDeleted": not raw_path.exists(),
    }
    _assert_summary_has_no_market_values(summary)
    return summary, error_class


def _safe_public_signature(value: Any) -> list[dict[str, Any]]:
    return [
        {
            "name": parameter.name,
            "kind": parameter.kind.name,
            "hasDefault": parameter.default is not inspect.Parameter.empty,
        }
        for parameter in inspect.signature(value).parameters.values()
    ]


def _public_api_evidence(provider: Any) -> dict[str, Any]:
    expected = (
        "login",
        "logout",
        "query_history_k_data_plus",
        "query_trade_dates",
        "query_dividend_data",
        "query_adjust_factor",
        "query_daily_adjust_factor",
    )
    functions: list[dict[str, Any]] = []
    for name in expected:
        value = getattr(provider, name, None)
        functions.append({
            "name": name,
            "exported": callable(value),
            "signature": (
                _safe_public_signature(value) if callable(value) else None
            ),
        })
    return {
        "stableCallId": "F0-BAO-001",
        "status": "SUCCESS",
        "networkRequestCount": 0,
        "publicFunctions": functions,
    }


def _not_executed_full_market_probe() -> dict[str, Any]:
    return {
        "stableCallId": "F0-BAO-010",
        "providerCandidate": "BAOSTOCK",
        "publicFunction": "query_daily_adjust_factor",
        "factClass": "ADJUSTMENT_FACTOR",
        "status": "NOT_EXECUTED",
        "reasonCode": "F0_FULL_MARKET_CALL_NOT_ALLOWED",
        "networkRequestCount": 0,
    }


def run_live_baostock(
    provider: Any,
    *,
    clock: Callable[[], datetime] | None = None,
    maximum_calls: int = MAX_PROVIDER_LOGICAL_CALLS,
    temp_parent: Path | None = None,
) -> dict[str, Any]:
    try:
        package_version = importlib_metadata.version("baostock")
    except importlib_metadata.PackageNotFoundError:
        package_version = str(getattr(provider, "__version__", ""))
    if package_version != BAOSTOCK_EXPECTED_VERSION:
        raise AuditPolicyError("F0_BAOSTOCK_VERSION_MISMATCH")
    for spec in PROBE_SPECS:
        validate_probe_scope(spec)
        if not callable(getattr(provider, spec.public_function, None)):
            raise AuditPolicyError(
                f"F0_PUBLIC_FUNCTION_NOT_EXPOSED:{spec.public_function}"
            )
    if not callable(getattr(provider, "login", None)):
        raise AuditPolicyError("F0_PUBLIC_FUNCTION_NOT_EXPOSED:login")
    if not callable(getattr(provider, "logout", None)):
        raise AuditPolicyError("F0_PUBLIC_FUNCTION_NOT_EXPOSED:logout")

    budget = CallBudget(maximum_calls)
    summaries: list[dict[str, Any]] = []
    stop_condition: str | None = None
    consecutive_network_errors = 0
    login_succeeded = False
    login_logout_operation_count = 0
    old_timeout = socket.getdefaulttimeout()
    try:
        socket.setdefaulttimeout(SOCKET_TIMEOUT_SECONDS)
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            login_result = provider.login()
        login_logout_operation_count += 1
        login_code = str(getattr(login_result, "error_code", "") or "")
        login_class = _safe_error_class(
            getattr(login_result, "error_msg", None)
        )
        if login_code != "0":
            stop_condition = login_class or "BAOSTOCK_LOGIN_FAILED"
        else:
            login_succeeded = True
            with tempfile.TemporaryDirectory(
                prefix="stock-quant-free-provider-f0-",
                dir=str(temp_parent) if temp_parent is not None else None,
            ) as raw_dir_name:
                raw_directory = Path(raw_dir_name)
                for spec in PROBE_SPECS:
                    summary, error_class = _probe_one(
                        provider,
                        spec,
                        budget,
                        raw_directory,
                        clock,
                    )
                    summaries.append(summary)
                    if error_class == "NETWORK":
                        consecutive_network_errors += 1
                    else:
                        consecutive_network_errors = 0
                    if error_class == "ACCESS_CONTROL":
                        stop_condition = "F0_ACCESS_CONTROL_STOP"
                        break
                    if consecutive_network_errors >= 2:
                        stop_condition = "F0_CONSECUTIVE_NETWORK_ERRORS"
                        break
                if any(raw_directory.iterdir()):
                    raise AuditPolicyError("F0_TEMP_RAW_RESPONSE_NOT_CLEANED")
    finally:
        if login_succeeded:
            try:
                with redirect_stdout(io.StringIO()), redirect_stderr(
                    io.StringIO()
                ):
                    provider.logout()
                login_logout_operation_count += 1
            except Exception:
                if stop_condition is None:
                    stop_condition = "BAOSTOCK_LOGOUT_FAILED"
        socket.setdefaulttimeout(old_timeout)

    report = {
        "schemaVersion": AUDIT_SCHEMA_VERSION,
        "policyVersion": AUDIT_POLICY_VERSION,
        "live": True,
        "networkAllowed": True,
        "providerCandidate": "BAOSTOCK",
        "clientVersion": BAOSTOCK_EXPECTED_VERSION,
        "upstreamDomain": BAOSTOCK_UPSTREAM_DOMAIN,
        "allowedSymbols": list(FIXED_SYMBOLS),
        "allowedDateRange": {
            "start": FIXED_START_DATE,
            "end": FIXED_END_DATE,
        },
        "callBudget": {
            "maximumProviderLogicalCalls": maximum_calls,
            "usedProviderLogicalCalls": budget.used,
        },
        "transportCounts": {
            "providerLogicalCallCount": budget.used,
            "loginLogoutOperationCount": login_logout_operation_count,
            "providerProtocolRequestCount": None,
            "providerProtocolRequestCountStatus": "UNVERIFIED",
            "providerHttpRequestCount": 0,
        },
        "publicApiEvidence": _public_api_evidence(provider),
        "calls": summaries + [_not_executed_full_market_probe()],
        "stopConditionTriggered": stop_condition is not None,
        "stopCondition": stop_condition,
        "rawResponseResidueCount": 0,
        "databaseAccessed": False,
        "productionApplicationImported": False,
        "ifindCalled": False,
    }
    _assert_summary_has_no_market_values(report)
    sanitized = redact_sensitive(report)
    sanitized["summarySha256"] = canonical_sha256(sanitized)
    return sanitized


def build_offline_report() -> dict[str, Any]:
    calls = [
        {
            "stableCallId": spec.stable_call_id,
            "publicFunction": spec.public_function,
            "factClass": spec.fact_class,
            "symbol": spec.symbol,
            "startDate": spec.start_date,
            "endDate": spec.end_date,
            "status": "NOT_EXECUTED",
            "reasonCode": "F0_LIVE_MODE_NOT_ENABLED",
        }
        for spec in PROBE_SPECS
    ]
    calls.append(_not_executed_full_market_probe())
    report = {
        "schemaVersion": AUDIT_SCHEMA_VERSION,
        "policyVersion": AUDIT_POLICY_VERSION,
        "live": False,
        "networkAllowed": False,
        "providerCandidate": "BAOSTOCK",
        "clientVersion": BAOSTOCK_EXPECTED_VERSION,
        "upstreamDomain": BAOSTOCK_UPSTREAM_DOMAIN,
        "allowedSymbols": list(FIXED_SYMBOLS),
        "allowedDateRange": {
            "start": FIXED_START_DATE,
            "end": FIXED_END_DATE,
        },
        "callBudget": {
            "maximumProviderLogicalCalls": MAX_PROVIDER_LOGICAL_CALLS,
            "usedProviderLogicalCalls": 0,
        },
        "transportCounts": {
            "providerLogicalCallCount": 0,
            "loginLogoutOperationCount": 0,
            "providerProtocolRequestCount": None,
            "providerProtocolRequestCountStatus": "UNVERIFIED",
            "providerHttpRequestCount": 0,
        },
        "calls": calls,
        "stopConditionTriggered": False,
        "stopCondition": None,
        "rawResponseResidueCount": 0,
        "databaseAccessed": False,
        "productionApplicationImported": False,
        "ifindCalled": False,
    }
    report["summarySha256"] = canonical_sha256(report)
    return report


def _write_or_print(report: Mapping[str, Any], output: Path | None) -> None:
    text = json.dumps(
        report,
        ensure_ascii=False,
        indent=2,
        sort_keys=True,
    ) + "\n"
    if output is None:
        print(text, end="")
        return
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8")


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Run the bounded, standalone F0 free-provider audit. "
            "Network is disabled unless --live is explicit."
        ),
    )
    parser.add_argument(
        "--live",
        action="store_true",
        help="Enable the frozen BaoStock live probe.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        help="Optional safe-summary JSON path; raw responses are never kept.",
    )
    args = parser.parse_args(list(argv) if argv is not None else None)
    if not args.live:
        _write_or_print(build_offline_report(), args.output)
        return 0
    provider = importlib.import_module("baostock")
    report = run_live_baostock(provider)
    _write_or_print(report, args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
