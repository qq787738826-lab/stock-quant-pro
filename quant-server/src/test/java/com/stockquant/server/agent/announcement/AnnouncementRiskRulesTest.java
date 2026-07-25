package com.stockquant.server.agent.announcement;

import com.stockquant.server.agent.announcement.AnnouncementRiskRules.EventFact;
import com.stockquant.server.agent.announcement.AnnouncementRiskRules.Evaluation;
import com.stockquant.server.agent.announcement.AnnouncementRiskRules.Group;
import com.stockquant.server.agent.model.AgentTypes.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnouncementRiskRulesTest {

    private static final LocalDate REQUEST_DATE = LocalDate.of(2025, 6, 30);

    @Test
    void freezesEveryKeywordClassExclusionAndCorrectionCombination() {
        for (Case value : cases()) {
            var match = AnnouncementRiskRules.classify(value.title());
            assertEquals(value.severity(), match.severity(), value.title());
            assertTrue(match.tags().contains(value.tag()), value.title());
            assertTrue(match.groups().contains(value.group()), value.title());
        }
        for (String excluded : List.of(
                "撤销退市风险警示",
                "撤销其他风险警示",
                "申请撤销风险警示",
                "解除股份质押",
                "股份解除冻结",
                "解除轮候冻结")) {
            var match = AnnouncementRiskRules.classify(excluded);
            assertFalse(match.tags().contains("DELISTING_TERMINATION"), excluded);
            assertFalse(match.tags().contains("OTHER_RISK_WARNING"), excluded);
            assertFalse(match.tags().contains("OWNERSHIP_ENFORCEMENT_HIGH"), excluded);
            assertFalse(match.tags().contains("OWNERSHIP_EXPOSURE_WARN"), excluded);
        }
        var correction = AnnouncementRiskRules.classify("关于年报补充更正的公告");
        assertEquals(Severity.HIGH, correction.severity());
        assertTrue(correction.tags().contains("FINANCIAL_REPORT_CORRECTION"));
    }

    @Test
    void appliesOneHighestSeverityDeductionPerEventAndStableOrdering() {
        Evaluation evaluation = AnnouncementRiskRules.evaluate(List.of(
                event("CNINFO:4", "减持计划", REQUEST_DATE.minusDays(149), "4"),
                event("CNINFO:2", "问询函", REQUEST_DATE.minusDays(10), "2"),
                event("CNINFO:3", "业绩预亏", REQUEST_DATE.minusDays(60), "3"),
                event("CNINFO:1", "立案调查暨重大诉讼", REQUEST_DATE, "1")
        ), REQUEST_DATE);
        assertEquals(36, evaluation.score());
        assertEquals(List.of(
                        "CNINFO:1", "CNINFO:3", "CNINFO:2", "CNINFO:4"),
                evaluation.riskEvents().stream()
                        .map(value -> value.event().sourceAnnouncementId())
                        .toList());
        assertEquals(List.of(40, 13, 8, 3),
                evaluation.riskEvents().stream()
                        .map(AnnouncementRiskRules.RiskEvent::deduction)
                        .toList());
        assertEquals(40, evaluation.riskEvents().get(0).deduction(),
                "multiple tags on one announcement must deduct only once");
    }

    @Test
    void normalizesUnicodeWhitespacePunctuationAndLatinCase() {
        assertEquals(
                "ABC, 重大诉讼",
                AnnouncementRiskRules.normalizeTitle("  ａｂｃ，  重大诉讼  "));
    }

    private static EventFact event(
            String sourceId,
            String title,
            LocalDate date,
            String versionSeed
    ) {
        return new EventFact(
                sourceId,
                "CNINFO_ID",
                "000001",
                "平安银行",
                title,
                date,
                "https://static.cninfo.com.cn/" + versionSeed + ".pdf",
                Instant.parse("2025-07-01T01:00:00Z"),
                Instant.parse("2025-07-01T01:00:00Z"),
                versionSeed.repeat(64).substring(0, 64),
                Integer.toHexString(versionSeed.hashCode()).repeat(64).substring(0, 64));
    }

    private static List<Case> cases() {
        List<Case> values = new ArrayList<>();
        addCases(values, Severity.CRITICAL, "DELISTING_TERMINATION",
                Group.REGULATORY_DELISTING,
                "终止上市", "可能被终止上市", "退市风险警示",
                "暂停上市", "进入退市整理期");
        addCases(values, Severity.HIGH, "OTHER_RISK_WARNING",
                Group.REGULATORY_DELISTING,
                "实施其他风险警示", "被实施风险警示");
        addCases(values, Severity.CRITICAL, "REGULATORY_ENFORCEMENT_CRITICAL",
                Group.REGULATORY_DELISTING,
                "立案调查", "涉嫌违法违规", "行政处罚决定书", "公开谴责", "纪律处分");
        addCases(values, Severity.HIGH, "REGULATORY_ENFORCEMENT_HIGH",
                Group.REGULATORY_DELISTING,
                "监管措施", "警示函", "责令改正");
        addCases(values, Severity.WARN, "REGULATORY_INQUIRY",
                Group.REGULATORY_DELISTING,
                "问询函", "关注函", "监管工作函");

        addCases(values, Severity.CRITICAL, "FINANCIAL_DEBT_CRITICAL",
                Group.FINANCIAL_LITIGATION,
                "无法表示意见", "否定意见", "破产重整", "预重整", "债务违约",
                "债务逾期", "不能清偿到期债务");
        addCases(values, Severity.HIGH, "FINANCIAL_PERFORMANCE_HIGH",
                Group.FINANCIAL_LITIGATION,
                "业绩预亏", "首亏", "续亏", "业绩大幅下降", "业绩下修",
                "会计差错更正", "财务数据更正", "保留意见", "非标准审计意见");
        addCases(values, Severity.HIGH, "LITIGATION_OPERATION_HIGH",
                Group.FINANCIAL_LITIGATION,
                "重大诉讼", "重大仲裁", "被申请破产", "主要银行账户被冻结",
                "停产", "重大安全事故", "重大合同终止", "核心业务暂停");
        addCases(values, Severity.WARN, "LITIGATION_PROGRESS",
                Group.FINANCIAL_LITIGATION,
                "诉讼进展", "仲裁进展", "风险提示公告");

        addCases(values, Severity.HIGH, "OWNERSHIP_ENFORCEMENT_HIGH",
                Group.OWNERSHIP_OPERATION,
                "被动减持", "司法拍卖", "股份冻结", "轮候冻结", "质押违约", "平仓风险");
        addCases(values, Severity.WARN, "OWNERSHIP_EXPOSURE_WARN",
                Group.OWNERSHIP_OPERATION,
                "减持计划", "减持进展", "股份质押");
        addCases(values, Severity.HIGH, "FUND_OCCUPATION_HIGH",
                Group.OWNERSHIP_OPERATION,
                "违规担保", "资金占用", "关联方占用");
        addCases(values, Severity.WARN, "EXTERNAL_GUARANTEE_WARN",
                Group.OWNERSHIP_OPERATION,
                "对外担保");
        addCases(values, Severity.WARN, "CORRECTION_CLARIFICATION_WARN",
                Group.RESEARCH_LIMITATIONS,
                "更正公告", "补充公告", "澄清公告", "致歉公告");
        return List.copyOf(values);
    }

    private static void addCases(
            List<Case> values,
            Severity severity,
            String tag,
            Group group,
            String... titles
    ) {
        for (String title : titles) {
            values.add(new Case(title, severity, tag, group));
        }
    }

    private record Case(
            String title,
            Severity severity,
            String tag,
            Group group
    ) {
    }
}
