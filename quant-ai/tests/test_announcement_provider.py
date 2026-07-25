from __future__ import annotations

import sys
import unittest
from datetime import date
from types import SimpleNamespace
from unittest.mock import patch

import pandas as pd
from fastapi import HTTPException
from pydantic import ValidationError

from app import announcement_provider as provider


class AnnouncementProviderTest(unittest.TestCase):

    def test_normalizes_real_cninfo_columns_and_stable_sort(self) -> None:
        frame = pd.DataFrame([
            {
                "代码": "000001",
                "简称": "平安银行",
                "公告标题": "B公告",
                "公告时间": "2023-06-20",
                "公告链接":
                    "HTTPS://STATIC.CNINFO.COM.CN/finalpage/2023-06-20/1212345679.PDF#x",
            },
            {
                "代码": 1,
                "简称": "平安银行",
                "公告标题": "A公告",
                "公告时间": "2023-06-19",
                "公告链接":
                    "https://static.cninfo.com.cn/finalpage/2023-06-19/1212345678.PDF",
            },
        ], columns=provider.REQUIRED_COLUMNS)
        records = provider._normalize_frame(frame, "000001")
        sorted_records = provider._deduplicate_and_sort(records)
        self.assertEqual(["A公告", "B公告"], [item.title for item in sorted_records])
        self.assertEqual(
            provider.REQUIRED_COLUMNS,
            tuple(sorted_records[0].rawFields),
        )

    def test_empty_frame_is_valid(self) -> None:
        frame = pd.DataFrame(columns=provider.REQUIRED_COLUMNS)
        self.assertEqual([], provider._normalize_frame(frame, "000001"))

    def test_pinned_empty_result_key_error_is_valid_but_other_key_error_fails(
        self,
    ) -> None:
        empty_error = KeyError(
            "None of [Index(['代码', '简称', '公告标题', '公告时间', "
            "'announcementId', 'orgId'], dtype='object')] "
            "are in the [columns]"
        )
        fake = SimpleNamespace(
            stock_zh_a_disclosure_report_cninfo=lambda **_: None
        )
        with patch.object(
            provider, "_timed_provider_call", side_effect=empty_error
        ):
            frame, attempts = provider._fetch_chunk_with_retries(
                fake,
                "000001",
                date(2023, 9, 17),
                date(2023, 10, 16),
            )
        self.assertEqual(1, attempts)
        self.assertEqual(provider.REQUIRED_COLUMNS, tuple(frame.columns))
        self.assertTrue(frame.empty)

        with patch.object(
            provider,
            "_timed_provider_call",
            side_effect=KeyError("unexpected provider field"),
        ):
            with self.assertRaises(provider.ProviderSchemaChanged):
                provider._fetch_chunk_with_retries(
                    fake,
                    "000001",
                    date(2023, 9, 17),
                    date(2023, 10, 16),
                )

    def test_exact_duplicate_is_removed_but_identity_conflict_fails(self) -> None:
        record = provider.AnnouncementProviderRecord(
            symbol="000001",
            securityName="平安银行",
            title="公告",
            reportedPublishDate=date(2023, 6, 19),
            sourceUrl="https://static.cninfo.com.cn/finalpage/2023-06-19/1212345678.PDF",
            rawFields={column: None for column in provider.REQUIRED_COLUMNS},
        )
        self.assertEqual(1, len(provider._deduplicate_and_sort([record, record])))
        conflict = record.model_copy(update={"title": "冲突公告"})
        with self.assertRaises(provider.ProviderSchemaChanged):
            provider._deduplicate_and_sort([record, conflict])

    def test_source_identity_prefers_cninfo_id_and_normalizes_url(self) -> None:
        identity, strength, normalized = provider._source_identity(
            "HTTPS://Static.CNINFO.COM.CN:443/a.pdf"
            "?utm_source=x&announcementId=ABC123&b=2&a=1#f"
        )
        self.assertEqual("CNINFO:ABC123", identity)
        self.assertEqual("CNINFO_ID", strength)
        self.assertEqual(
            "https://static.cninfo.com.cn/a.pdf"
            "?a=1&announcementId=ABC123&b=2",
            normalized,
        )
        derived, strength, _ = provider._source_identity(
            "https://cninfo.com.cn/notice/no-id.pdf"
        )
        self.assertRegex(derived, r"^CNINFO_URL_SHA256:[0-9a-f]{64}$")
        self.assertEqual("URL_DERIVED", strength)
        self.assertEqual(
            "http://static.cninfo.com.cn/a.pdf",
            provider._normalize_url(
                "HTTP://STATIC.CNINFO.COM.CN:80/a.pdf"
            ),
        )

    def test_rejects_invalid_url_symbol_range_and_schema(self) -> None:
        for source_url in (
            "ftp://static.cninfo.com.cn/a.pdf",
            "https://example.com/a.pdf",
            "https://cninfo.com.cn.evil.example/a.pdf",
            "https://evil-cninfo.com.cn/a.pdf",
            "https://static.cninfo.com.cn:8443/a.pdf",
            "http://static.cninfo.com.cn:443/a.pdf",
            "https://user@static.cninfo.com.cn/a.pdf",
        ):
            with self.subTest(source_url=source_url):
                with self.assertRaises(provider.ProviderSchemaChanged):
                    provider._normalize_url(source_url)
        with self.assertRaises(ValidationError):
            provider.AnnouncementProviderRequest(
                symbol="1",
                startDate=date(2023, 1, 1),
                endDate=date(2023, 1, 2),
            )
        with self.assertRaises(ValidationError):
            provider.AnnouncementProviderRequest(
                symbol="000001",
                startDate=date(2023, 1, 1),
                endDate=date(2024, 1, 2),
            )
        with self.assertRaises(provider.ProviderSchemaChanged):
            provider._validate_columns(pd.DataFrame(columns=["bad"]))

    def test_chunks_are_non_overlapping_and_at_most_thirty_days(self) -> None:
        chunks = provider._chunks(date(2023, 1, 1), date(2023, 3, 5))
        self.assertEqual(3, len(chunks))
        for index, (start, end) in enumerate(chunks):
            self.assertLessEqual((end - start).days + 1, 30)
            if index:
                self.assertEqual(chunks[index - 1][1].toordinal() + 1, start.toordinal())

    def test_temporary_failures_retry_but_access_denial_does_not(self) -> None:
        calls = 0

        def temporary_then_success(_function):
            nonlocal calls
            calls += 1
            if calls < 3:
                raise TimeoutError
            return pd.DataFrame(columns=provider.REQUIRED_COLUMNS)

        fake = SimpleNamespace(stock_zh_a_disclosure_report_cninfo=lambda **_: None)
        with patch.object(provider, "_timed_provider_call", temporary_then_success), \
                patch.object(provider.time, "sleep"):
            _, attempts = provider._fetch_chunk_with_retries(
                fake, "000001", date(2023, 1, 1), date(2023, 1, 2)
            )
        self.assertEqual(3, attempts)

        denied = RuntimeError("HTTP 429 Too Many Requests")
        with patch.object(provider, "_timed_provider_call", side_effect=denied):
            with self.assertRaises(provider.ProviderAccessDenied):
                provider._fetch_chunk_with_retries(
                    fake, "000001", date(2023, 1, 1), date(2023, 1, 2)
                )

        with patch.object(
            provider,
            "_timed_provider_call",
            side_effect=provider.ProviderCallTimedOut,
        ) as timed_out:
            with self.assertRaises(provider.ProviderTemporaryFailure) as timeout:
                provider._fetch_chunk_with_retries(
                    fake, "000001", date(2023, 1, 1), date(2023, 1, 2)
                )
        timed_out.assert_called_once()
        self.assertEqual(1, timeout.exception.attempts)

    def test_endpoint_returns_complete_empty_and_partial_results(self) -> None:
        request = provider.AnnouncementProviderRequest(
            symbol="000001",
            startDate=date(2023, 1, 1),
            endDate=date(2023, 1, 2),
        )
        fake = SimpleNamespace(__version__=provider.EXPECTED_AKSHARE_VERSION)
        with patch.dict(sys.modules, {"akshare": fake}), \
                patch.object(
                    provider,
                    "_fetch_chunk_with_retries",
                    return_value=(
                        pd.DataFrame(columns=provider.REQUIRED_COLUMNS),
                        1,
                    ),
                ):
            response = provider.fetch_announcements(request)
        self.assertTrue(response.complete)
        self.assertEqual([], response.records)

        with patch.dict(sys.modules, {"akshare": fake}), \
                patch.object(
                    provider,
                    "_fetch_chunk_with_retries",
                    side_effect=provider.ProviderTemporaryFailure,
                ):
            response = provider.fetch_announcements(request)
        self.assertFalse(response.complete)
        self.assertEqual(0, response.successfulChunkCount)
        self.assertEqual("AKSHARE_PROVIDER_TEMPORARY_FAILURE", response.errors[0].code)

    def test_access_denial_and_version_drift_fail_closed(self) -> None:
        request = provider.AnnouncementProviderRequest(
            symbol="000001",
            startDate=date(2023, 1, 1),
            endDate=date(2023, 1, 2),
        )
        wrong = SimpleNamespace(__version__="0.0.0")
        with patch.dict(sys.modules, {"akshare": wrong}):
            with self.assertRaises(HTTPException) as failure:
                provider.fetch_announcements(request)
        self.assertEqual(503, failure.exception.status_code)


if __name__ == "__main__":
    unittest.main()
