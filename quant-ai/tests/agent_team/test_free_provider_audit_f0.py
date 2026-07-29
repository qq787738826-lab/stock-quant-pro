from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from tools import free_provider_audit_f0 as audit


TOOL_PATH = (
    Path(__file__).resolve().parents[2]
    / "tools"
    / "free_provider_audit_f0.py"
)
FIXTURE_PATH = (
    Path(__file__).resolve().parents[1]
    / "fixtures"
    / "free_provider_audit_f0_expected.json"
)


class FakeResult:
    def __init__(
        self,
        fields: list[str],
        rows: list[list[str]],
        *,
        error_code: str = "0",
        error_msg: str = "success",
    ) -> None:
        self.fields = fields
        self._rows = rows
        self._index = -1
        self.error_code = error_code
        self.error_msg = error_msg

    def next(self) -> bool:
        self._index += 1
        return self._index < len(self._rows)

    def get_row_data(self) -> list[str]:
        return self._rows[self._index]


class FakeBaoStock:
    # Synthetic-only values; no row below was copied from a live provider.
    __version__ = "0.9.3"

    def __init__(
        self,
        *,
        empty: bool = False,
        fail_on: str | None = None,
        malformed: bool = False,
    ) -> None:
        self.empty = empty
        self.fail_on = fail_on
        self.malformed = malformed
        self.calls: list[str] = []
        self.logged_out = False

    def login(
        self,
        user_id: str = "DEFAULT_USER_SENTINEL",
        password: str = "DEFAULT_PASSWORD_SENTINEL",
    ) -> FakeResult:
        del user_id, password
        self.calls.append("login")
        return FakeResult([], [])

    def logout(self) -> FakeResult:
        self.calls.append("logout")
        self.logged_out = True
        return FakeResult([], [])

    def _result(
        self,
        name: str,
        fields: list[str],
        rows: list[list[str]],
    ) -> FakeResult:
        self.calls.append(name)
        if self.fail_on == name:
            return FakeResult(
                fields,
                [],
                error_code="10002001",
                error_msg="network receive error",
            )
        if self.empty:
            rows = []
        if self.malformed and rows:
            rows = [rows[0][:-1]]
        return FakeResult(fields, rows)

    def query_history_k_data_plus(self, *args, **kwargs) -> FakeResult:
        del args, kwargs
        return self._result(
            "query_history_k_data_plus",
            [
                "date",
                "code",
                "open",
                "high",
                "low",
                "close",
                "preclose",
                "volume",
                "amount",
                "adjustflag",
                "turn",
                "isST",
            ],
            [[
                "2025-06-03",
                "sh.600000",
                "10.11",
                "10.23",
                "10.01",
                "10.20",
                "10.08",
                "123456",
                "9876543.21",
                "3",
                "0.12",
                "0",
            ]],
        )

    def query_trade_dates(self, *args, **kwargs) -> FakeResult:
        del args, kwargs
        return self._result(
            "query_trade_dates",
            ["calendar_date", "is_trading_day"],
            [["2025-06-03", "1"]],
        )

    def query_dividend_data(self, *args, **kwargs) -> FakeResult:
        del args, kwargs
        return self._result(
            "query_dividend_data",
            ["code", "dividOperateDate", "dividCashPsBeforeTax"],
            [["sh.600000", "2025-06-06", "0.15"]],
        )

    def query_adjust_factor(self, *args, **kwargs) -> FakeResult:
        del args, kwargs
        return self._result(
            "query_adjust_factor",
            ["code", "dividOperateDate", "foreAdjustFactor"],
            [["sh.600000", "2025-06-06", "1.2345"]],
        )

    def query_daily_adjust_factor(self, *args, **kwargs) -> FakeResult:
        raise AssertionError("full-market function must never be called")


def fixed_clock() -> datetime:
    return datetime(2026, 7, 29, 1, 2, 3, 456789, tzinfo=timezone.utc)


class FreeProviderAuditF0Test(unittest.TestCase):

    def test_default_mode_keeps_network_disabled_and_does_not_import_provider(
        self,
    ) -> None:
        with mock.patch.object(
            audit.importlib,
            "import_module",
            side_effect=AssertionError("provider import attempted"),
        ):
            report = audit.build_offline_report()
        self.assertFalse(report["live"])
        self.assertFalse(report["networkAllowed"])
        self.assertEqual(
            0,
            report["callBudget"]["usedProviderLogicalCalls"],
        )
        self.assertTrue(all(
            item["status"] == "NOT_EXECUTED"
            for item in report["calls"]
        ))

    def test_live_mode_is_explicit_bounded_and_never_calls_full_market(
        self,
    ) -> None:
        provider = FakeBaoStock()
        report = audit.run_live_baostock(provider, clock=fixed_clock)
        self.assertTrue(report["live"])
        self.assertEqual(8, report["callBudget"]["usedProviderLogicalCalls"])
        self.assertEqual(
            8,
            report["transportCounts"]["providerLogicalCallCount"],
        )
        self.assertEqual(
            0,
            report["transportCounts"]["providerHttpRequestCount"],
        )
        self.assertEqual("login", provider.calls[0])
        self.assertEqual("logout", provider.calls[-1])
        self.assertTrue(provider.logged_out)
        self.assertNotIn("query_daily_adjust_factor", provider.calls)
        self.assertEqual(
            "F0_FULL_MARKET_CALL_NOT_ALLOWED",
            report["calls"][-1]["reasonCode"],
        )
        self.assertTrue(all(
            item.get("errorClass") is None
            for item in report["calls"][:-1]
        ))
        serialized = audit.canonical_json(report["publicApiEvidence"])
        self.assertNotIn("DEFAULT_USER_SENTINEL", serialized)
        self.assertNotIn("DEFAULT_PASSWORD_SENTINEL", serialized)
        login_signature = next(
            item["signature"]
            for item in report["publicApiEvidence"]["publicFunctions"]
            if item["name"] == "login"
        )
        self.assertEqual(
            ["user_id", "password"],
            [item["name"] for item in login_signature],
        )
        self.assertTrue(login_signature[-1]["hasDefault"])

    def test_budget_symbol_and_date_guards(self) -> None:
        budget = audit.CallBudget(1)
        budget.consume()
        with self.assertRaisesRegex(
            audit.AuditPolicyError,
            "F0_CALL_BUDGET_EXCEEDED",
        ):
            budget.consume()
        with self.assertRaisesRegex(
            audit.AuditPolicyError,
            "F0_SYMBOL_NOT_ALLOWED",
        ):
            audit.validate_probe_scope(audit.ProbeSpec(
                "X",
                "query_history_k_data_plus",
                "RAW_DAILY_BAR",
                "sh.601398",
                audit.FIXED_START_DATE,
                audit.FIXED_END_DATE,
                "3",
            ))
        with self.assertRaisesRegex(
            audit.AuditPolicyError,
            "F0_DATE_RANGE_NOT_ALLOWED",
        ):
            audit.validate_probe_scope(audit.ProbeSpec(
                "X",
                "query_trade_dates",
                "TRADING_CALENDAR",
                start_date="2025-01-01",
                end_date=audit.FIXED_END_DATE,
            ))
        with self.assertRaisesRegex(
            audit.AuditPolicyError,
            "F0_FULL_MARKET_CALL_NOT_ALLOWED",
        ):
            audit.validate_probe_scope(audit.ProbeSpec(
                "X",
                "query_daily_adjust_factor",
                "ADJUSTMENT_FACTOR",
            ))

    def test_summary_contains_statistics_but_no_market_values(self) -> None:
        report = audit.run_live_baostock(
            FakeBaoStock(),
            clock=fixed_clock,
        )
        serialized = audit.canonical_json(report)
        for raw_value in (
            "10.11",
            "10.23",
            "10.01",
            "10.20",
            "10.08",
            "123456",
            "9876543.21",
            "1.2345",
        ):
            self.assertNotIn(raw_value, serialized)
        history = report["calls"][0]
        self.assertEqual(1, history["rowCount"])
        self.assertIn("open", history["fields"])
        self.assertTrue(any(
            item["field"] == "volume"
            for item in history["fieldStatistics"]
        ))
        self.assertFalse(history["rawResponsePersisted"])
        self.assertTrue(history["temporaryRawFileDeleted"])

    def test_sensitive_redaction_is_recursive(self) -> None:
        raw = {
            "Authorization": "Bearer secret",
            "payload": [{
                "Cookie": "session=secret",
                "nested": {
                    "username": "person",
                    "safe": "retained",
                },
            }],
        }
        sanitized = audit.redact_sensitive(raw)
        self.assertEqual("[REDACTED]", sanitized["Authorization"])
        self.assertEqual("[REDACTED]", sanitized["payload"][0]["Cookie"])
        self.assertEqual(
            "[REDACTED]",
            sanitized["payload"][0]["nested"]["username"],
        )
        self.assertEqual("retained", sanitized["payload"][0]["nested"]["safe"])

    def test_temporary_raw_files_are_deleted_exactly(self) -> None:
        with tempfile.TemporaryDirectory() as parent:
            parent_path = Path(parent)
            report = audit.run_live_baostock(
                FakeBaoStock(),
                clock=fixed_clock,
                temp_parent=parent_path,
            )
            self.assertEqual([], list(parent_path.iterdir()))
            self.assertEqual(0, report["rawResponseResidueCount"])

    def test_canonical_hash_vector_is_fixed_and_order_independent(self) -> None:
        fixture = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            fixture["canonical"],
            audit.canonical_json(fixture["input"]),
        )
        self.assertEqual(
            fixture["sha256"],
            audit.canonical_sha256(fixture["input"]),
        )
        reordered = {
            "nested": fixture["input"]["nested"],
            "number": fixture["input"]["number"],
            "schemaVersion": fixture["input"]["schemaVersion"],
        }
        self.assertEqual(
            fixture["sha256"],
            audit.canonical_sha256(reordered),
        )

    def test_empty_error_and_structure_change_have_stable_statuses(self) -> None:
        empty = audit.run_live_baostock(
            FakeBaoStock(empty=True),
            clock=fixed_clock,
        )
        self.assertTrue(all(
            item["status"] == "EMPTY"
            for item in empty["calls"][:-1]
        ))

        error = audit.run_live_baostock(
            FakeBaoStock(fail_on="query_trade_dates"),
            clock=fixed_clock,
        )
        calendar = next(
            item for item in error["calls"]
            if item["stableCallId"] == "F0-BAO-006"
        )
        self.assertEqual("ERROR", calendar["status"])
        self.assertEqual("NETWORK", calendar["errorClass"])

        malformed = audit.run_live_baostock(
            FakeBaoStock(malformed=True),
            clock=fixed_clock,
        )
        self.assertEqual(
            "STRUCTURE_CHANGED",
            malformed["calls"][0]["status"],
        )

    def test_tool_is_standalone_and_contains_no_production_or_secret_access(
        self,
    ) -> None:
        source = TOOL_PATH.read_text(encoding="utf-8").casefold()
        for forbidden in (
            "psycopg",
            "sqlalchemy",
            "import fastapi",
            "import dotenv",
            "os.environ",
            "import ifind",
            "app.agent_team",
        ):
            self.assertNotIn(forbidden, source)
        self.assertFalse(audit.build_offline_report()["databaseAccessed"])


if __name__ == "__main__":
    unittest.main()
