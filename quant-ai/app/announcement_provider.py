from __future__ import annotations

import hashlib
import queue
import re
import threading
import time
from datetime import date, timedelta
from typing import Any, Literal
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import pandas as pd
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict, Field, model_validator


PROVIDER_CONTRACT_VERSION = "AKSHARE_CNINFO_PROVIDER_V1"
EXPECTED_AKSHARE_VERSION = "1.18.64"
MARKET = "沪深京"
REQUIRED_COLUMNS = ("代码", "简称", "公告标题", "公告时间", "公告链接")
MAX_LOGICAL_DAYS = 366
MAX_CHUNK_DAYS = 30
CALL_TIMEOUT_SECONDS = 30.0
MIN_CALL_INTERVAL_SECONDS = 2.0
MAX_TEMPORARY_RETRIES = 2

_TRACKING_QUERY_KEYS = frozenset({
    "from",
    "source",
    "spm",
    "track",
    "tracking",
})
_EXPLICIT_ID_KEYS = frozenset({
    "announcementid",
    "announcement_id",
    "announcementno",
    "announcement_no",
    "bulletinid",
    "bulletin_id",
})
_PROVIDER_CALL_LOCK = threading.Lock()
_LAST_CALL_MONOTONIC = 0.0


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class AnnouncementProviderRequest(StrictModel):
    symbol: str = Field(pattern=r"^[0-9]{6}$")
    market: Literal["沪深京"] = MARKET
    startDate: date
    endDate: date
    keyword: Literal[""] = ""
    category: Literal[""] = ""

    @model_validator(mode="after")
    def validate_range(self) -> AnnouncementProviderRequest:
        days = (self.endDate - self.startDate).days + 1
        if days < 1 or days > MAX_LOGICAL_DAYS:
            raise ValueError("公告查询日期范围必须为1至366个自然日")
        return self


class AnnouncementProviderRecord(StrictModel):
    symbol: str = Field(pattern=r"^[0-9]{6}$")
    securityName: str = Field(min_length=1, max_length=128)
    title: str = Field(min_length=1, max_length=1024)
    reportedPublishDate: date
    sourceUrl: str = Field(min_length=1)
    rawFields: dict[str, str | None]


class AnnouncementProviderError(StrictModel):
    code: str
    chunkStartDate: date
    chunkEndDate: date
    attempts: int = Field(ge=1, le=3)


class AnnouncementProviderResponse(StrictModel):
    providerContractVersion: Literal["AKSHARE_CNINFO_PROVIDER_V1"]
    akshareVersion: Literal["1.18.64"]
    requestedSymbol: str = Field(pattern=r"^[0-9]{6}$")
    requestedStartDate: date
    requestedEndDate: date
    complete: bool
    chunkCount: int = Field(ge=1)
    successfulChunkCount: int = Field(ge=0)
    records: list[AnnouncementProviderRecord]
    errors: list[AnnouncementProviderError]

    @model_validator(mode="after")
    def validate_counts(self) -> AnnouncementProviderResponse:
        if self.successfulChunkCount > self.chunkCount:
            raise ValueError("successfulChunkCount不能超过chunkCount")
        if self.complete != (
            self.successfulChunkCount == self.chunkCount and not self.errors
        ):
            raise ValueError("complete与分块结果不一致")
        return self


class ProviderAccessDenied(RuntimeError):
    pass


class ProviderSchemaChanged(RuntimeError):
    pass


class ProviderTemporaryFailure(RuntimeError):
    def __init__(self, attempts: int = MAX_TEMPORARY_RETRIES + 1) -> None:
        super().__init__("AKShare CNINFO provider temporarily unavailable")
        self.attempts = attempts


class ProviderCallTimedOut(RuntimeError):
    pass


router = APIRouter(
    prefix="/providers/akshare/cninfo",
    tags=["announcement-provider"],
)


@router.post("/announcements", response_model=AnnouncementProviderResponse)
def fetch_announcements(
    request: AnnouncementProviderRequest,
) -> AnnouncementProviderResponse:
    import akshare as ak

    if ak.__version__ != EXPECTED_AKSHARE_VERSION:
        raise HTTPException(
            status_code=503,
            detail="AKSHARE_PROVIDER_VERSION_MISMATCH",
        )

    chunks = _chunks(request.startDate, request.endDate)
    records: list[AnnouncementProviderRecord] = []
    errors: list[AnnouncementProviderError] = []
    successful = 0
    for start_date, end_date in chunks:
        try:
            frame, attempts = _fetch_chunk_with_retries(
                ak,
                request.symbol,
                start_date,
                end_date,
            )
            successful += 1
            records.extend(_normalize_frame(frame, request.symbol))
        except ProviderAccessDenied as error:
            raise HTTPException(
                status_code=502,
                detail="AKSHARE_PROVIDER_ACCESS_DENIED",
            ) from error
        except ProviderSchemaChanged as error:
            raise HTTPException(
                status_code=502,
                detail="AKSHARE_PROVIDER_SCHEMA_CHANGED",
            ) from error
        except ProviderTemporaryFailure as error:
            errors.append(AnnouncementProviderError(
                code="AKSHARE_PROVIDER_TEMPORARY_FAILURE",
                chunkStartDate=start_date,
                chunkEndDate=end_date,
                attempts=error.attempts,
            ))

    records = _deduplicate_and_sort(records)
    return AnnouncementProviderResponse(
        providerContractVersion=PROVIDER_CONTRACT_VERSION,
        akshareVersion=EXPECTED_AKSHARE_VERSION,
        requestedSymbol=request.symbol,
        requestedStartDate=request.startDate,
        requestedEndDate=request.endDate,
        complete=successful == len(chunks) and not errors,
        chunkCount=len(chunks),
        successfulChunkCount=successful,
        records=records,
        errors=errors,
    )


def _chunks(start_date: date, end_date: date) -> list[tuple[date, date]]:
    result: list[tuple[date, date]] = []
    current = start_date
    while current <= end_date:
        chunk_end = min(end_date, current + timedelta(days=MAX_CHUNK_DAYS - 1))
        result.append((current, chunk_end))
        current = chunk_end + timedelta(days=1)
    return result


def _fetch_chunk_with_retries(
    ak: Any,
    symbol: str,
    start_date: date,
    end_date: date,
) -> tuple[pd.DataFrame, int]:
    for attempt in range(1, MAX_TEMPORARY_RETRIES + 2):
        try:
            frame = _timed_provider_call(
                lambda: ak.stock_zh_a_disclosure_report_cninfo(
                    symbol=symbol,
                    market=MARKET,
                    keyword="",
                    category="",
                    start_date=start_date.strftime("%Y%m%d"),
                    end_date=end_date.strftime("%Y%m%d"),
                )
            )
            _validate_columns(frame)
            return frame, attempt
        except KeyError as error:
            # AKShare 1.18.64 selects its final columns from an empty DataFrame
            # when CNINFO reports totalAnnouncement=0. Recognize only that
            # pinned signature; every other KeyError is a schema change.
            if _is_pinned_empty_result_error(error):
                return pd.DataFrame(columns=REQUIRED_COLUMNS), attempt
            raise ProviderSchemaChanged(
                "provider empty-result/schema behavior changed"
            ) from error
        except (ProviderAccessDenied, ProviderSchemaChanged):
            raise
        except ProviderCallTimedOut as error:
            # The pinned AKShare API is synchronous and its worker thread cannot
            # be cancelled safely. Do not enqueue retries behind a timed-out
            # call that may still hold the single-provider lock.
            raise ProviderTemporaryFailure(attempt) from error
        except Exception as error:
            classification = _classify_provider_error(error)
            if classification == "ACCESS_DENIED":
                raise ProviderAccessDenied from error
            if classification == "SCHEMA_CHANGED":
                raise ProviderSchemaChanged from error
            if attempt > MAX_TEMPORARY_RETRIES:
                raise ProviderTemporaryFailure(attempt) from error
            time.sleep(float(2 ** (attempt - 1)))
    raise ProviderTemporaryFailure(MAX_TEMPORARY_RETRIES + 1)


def _timed_provider_call(function: Any) -> pd.DataFrame:
    result_queue: queue.Queue[tuple[str, Any]] = queue.Queue(maxsize=1)

    def runner() -> None:
        global _LAST_CALL_MONOTONIC
        with _PROVIDER_CALL_LOCK:
            delay = MIN_CALL_INTERVAL_SECONDS - (
                time.monotonic() - _LAST_CALL_MONOTONIC
            )
            if delay > 0:
                time.sleep(delay)
            try:
                result_queue.put(("ok", function()))
            except Exception as error:  # pragma: no cover - provider-specific
                result_queue.put(("error", error))
            finally:
                _LAST_CALL_MONOTONIC = time.monotonic()

    thread = threading.Thread(
        target=runner,
        name="akshare-cninfo-announcement-provider",
        daemon=True,
    )
    thread.start()
    thread.join(CALL_TIMEOUT_SECONDS)
    if thread.is_alive():
        raise ProviderCallTimedOut("AKShare CNINFO provider timed out")
    status, payload = result_queue.get_nowait()
    if status == "error":
        raise payload
    if not isinstance(payload, pd.DataFrame):
        raise ProviderSchemaChanged("provider response is not a DataFrame")
    return payload


def _validate_columns(frame: pd.DataFrame) -> None:
    if not isinstance(frame, pd.DataFrame):
        raise ProviderSchemaChanged("provider response is not a DataFrame")
    if tuple(str(column) for column in frame.columns) != REQUIRED_COLUMNS:
        raise ProviderSchemaChanged("provider columns changed")


def _normalize_frame(
    frame: pd.DataFrame,
    requested_symbol: str,
) -> list[AnnouncementProviderRecord]:
    _validate_columns(frame)
    result: list[AnnouncementProviderRecord] = []
    for _, row in frame.iterrows():
        symbol = _normalize_symbol(row["代码"])
        if symbol != requested_symbol:
            raise ProviderSchemaChanged("provider returned a different symbol")
        security_name = _required_text(row["简称"], "security name")
        title = _required_text(row["公告标题"], "title")
        reported_date = _reported_date(row["公告时间"])
        source_url = _required_text(row["公告链接"], "source URL")
        _source_identity(source_url)
        result.append(AnnouncementProviderRecord(
            symbol=symbol,
            securityName=security_name,
            title=title,
            reportedPublishDate=reported_date,
            sourceUrl=source_url,
            rawFields={
                "代码": symbol,
                "简称": security_name,
                "公告标题": title,
                "公告时间": reported_date.isoformat(),
                "公告链接": source_url,
            },
        ))
    return result


def _deduplicate_and_sort(
    records: list[AnnouncementProviderRecord],
) -> list[AnnouncementProviderRecord]:
    by_identity: dict[str, AnnouncementProviderRecord] = {}
    fingerprints: dict[str, tuple[Any, ...]] = {}
    for record in records:
        identity, _, _ = _source_identity(record.sourceUrl)
        fingerprint = (
            record.symbol,
            record.securityName,
            record.title,
            record.reportedPublishDate,
            _normalize_url(record.sourceUrl),
        )
        existing = fingerprints.get(identity)
        if existing is not None and existing != fingerprint:
            raise ProviderSchemaChanged(
                "same source announcement identity has conflicting records"
            )
        fingerprints[identity] = fingerprint
        by_identity.setdefault(identity, record)
    return sorted(
        by_identity.values(),
        key=lambda record: (
            record.reportedPublishDate,
            _source_identity(record.sourceUrl)[0],
            record.title,
        ),
    )


def _source_identity(source_url: str) -> tuple[str, str, str]:
    normalized = _normalize_url(source_url)
    parsed = urlsplit(normalized)
    query = {key.lower(): value for key, value in parse_qsl(parsed.query)}
    for key in sorted(_EXPLICIT_ID_KEYS):
        value = query.get(key)
        if value and re.fullmatch(r"[A-Za-z0-9._-]+", value):
            return f"CNINFO:{value}", "CNINFO_ID", normalized
    filename = parsed.path.rsplit("/", 1)[-1]
    basename = filename.rsplit(".", 1)[0]
    if re.fullmatch(r"[A-Za-z0-9_-]*[0-9][A-Za-z0-9._-]{5,}", basename):
        return f"CNINFO:{basename}", "CNINFO_ID", normalized
    digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
    return f"CNINFO_URL_SHA256:{digest}", "URL_DERIVED", normalized


def _normalize_url(source_url: str) -> str:
    if not isinstance(source_url, str):
        raise ProviderSchemaChanged("announcement URL must be a string")
    try:
        parsed = urlsplit(source_url.strip())
        scheme = parsed.scheme.lower()
        host = (parsed.hostname or "").lower()
        port = parsed.port
    except ValueError as error:
        raise ProviderSchemaChanged("invalid announcement URL") from error
    if scheme not in {"http", "https"} or not host:
        raise ProviderSchemaChanged("announcement URL must be HTTP or HTTPS")
    if parsed.username is not None or parsed.password is not None:
        raise ProviderSchemaChanged(
            "announcement URL must not contain user information"
        )
    if host != "cninfo.com.cn" and not host.endswith(".cninfo.com.cn"):
        raise ProviderSchemaChanged("announcement URL must use a CNINFO host")
    if port is not None and not (
        scheme == "http" and port == 80 or scheme == "https" and port == 443
    ):
        raise ProviderSchemaChanged(
            "announcement URL must not use a non-default port"
        )
    netloc = host
    query_items = []
    for key, value in parse_qsl(parsed.query, keep_blank_values=True):
        normalized_key = key.lower()
        if normalized_key.startswith("utm_") or normalized_key in _TRACKING_QUERY_KEYS:
            continue
        query_items.append((key, value))
    query_items.sort(key=lambda item: (item[0], item[1]))
    return urlunsplit((
        scheme,
        netloc,
        parsed.path or "/",
        urlencode(query_items, doseq=True),
        "",
    ))


def _normalize_symbol(value: Any) -> str:
    if value is None:
        raise ProviderSchemaChanged("symbol is missing")
    if isinstance(value, bool):
        raise ProviderSchemaChanged("symbol is invalid")
    text = str(value).strip()
    if text.endswith(".0") and text[:-2].isdigit():
        text = text[:-2]
    if not text.isdigit() or len(text) > 6:
        raise ProviderSchemaChanged("symbol is invalid")
    return text.zfill(6)


def _required_text(value: Any, field: str) -> str:
    if value is None or pd.isna(value):
        raise ProviderSchemaChanged(f"{field} is missing")
    text = str(value).strip()
    if not text:
        raise ProviderSchemaChanged(f"{field} is blank")
    return text


def _reported_date(value: Any) -> date:
    try:
        parsed = pd.to_datetime(value, errors="raise")
    except Exception as error:
        raise ProviderSchemaChanged("reported publish date is invalid") from error
    if pd.isna(parsed):
        raise ProviderSchemaChanged("reported publish date is missing")
    return parsed.date()


def _classify_provider_error(error: Exception) -> str:
    if isinstance(error, ProviderSchemaChanged):
        return "SCHEMA_CHANGED"
    status_code = getattr(getattr(error, "response", None), "status_code", None)
    if status_code in {403, 429}:
        return "ACCESS_DENIED"
    message = str(error).lower()
    if any(token in message for token in (
        "403",
        "429",
        "captcha",
        "验证码",
        "access denied",
        "too many requests",
    )):
        return "ACCESS_DENIED"
    return "TEMPORARY"


def _is_pinned_empty_result_error(error: KeyError) -> bool:
    if len(error.args) != 1 or not isinstance(error.args[0], str):
        return False
    message = error.args[0]
    return (
        "None of [Index([" in message
        and "are in the [columns]" in message
        and all(field in message for field in (
            "代码",
            "简称",
            "公告标题",
            "公告时间",
            "announcementId",
            "orgId",
        ))
    )
