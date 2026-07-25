package com.stockquant.server.agent.announcement;

import com.stockquant.server.agent.model.AgentTypes.Severity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AnnouncementRiskRules {

    private static final Set<String> DELISTING_EXCLUSIONS = Set.of(
            "撤销退市风险警示", "撤销其他风险警示", "申请撤销风险警示");
    private static final Set<String> OWNERSHIP_EXCLUSIONS = Set.of(
            "解除股份质押", "股份解除冻结", "解除轮候冻结");
    private static final Set<String> CORRECTION_CONTEXT = Set.of(
            "年报", "半年报", "季报", "业绩", "财务");
    private static final List<Rule> RULES = rules();

    private AnnouncementRiskRules() {
    }

    public static Evaluation evaluate(
            List<EventFact> events,
            LocalDate requestTradeDate
    ) {
        List<RiskEvent> risks = new ArrayList<>();
        int deductions = 0;
        for (EventFact event : events) {
            Match match = classify(event.title());
            if (match.severity() == Severity.INFO) {
                continue;
            }
            long ageDays = ChronoUnit.DAYS.between(
                    event.reportedPublishDate(), requestTradeDate);
            if (ageDays < 0 || ageDays >= AnnouncementContracts.LOOKBACK_DAYS) {
                throw new IllegalArgumentException("公告时效超出固定180日窗口");
            }
            int deduction = BigDecimal.valueOf(baseDeduction(match.severity()))
                    .multiply(recencyFactor(ageDays))
                    .setScale(0, RoundingMode.HALF_UP)
                    .intValueExact();
            deductions += deduction;
            risks.add(new RiskEvent(event, match, ageDays, deduction));
        }
        risks.sort(Comparator
                .comparingInt((RiskEvent value) -> severityRank(
                        value.match().severity())).reversed()
                .thenComparing(
                        value -> value.event().reportedPublishDate(),
                        Comparator.reverseOrder())
                .thenComparing(value -> value.event().sourceAnnouncementId())
                .thenComparing(value -> value.event().observationVersion()));
        return new Evaluation(
                Math.max(0, 100 - deductions),
                List.copyOf(risks));
    }

    public static Match classify(String rawTitle) {
        String title = normalizeTitle(rawTitle);
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        LinkedHashSet<Group> groups = new LinkedHashSet<>();
        Severity severity = Severity.INFO;
        for (Rule rule : RULES) {
            if (title.contains(rule.keyword())
                    && rule.exclusions().stream().noneMatch(title::contains)) {
                tags.add(rule.tag());
                groups.add(rule.group());
                severity = max(severity, rule.severity());
            }
        }
        boolean correction = title.contains("更正") || title.contains("补充更正");
        if (correction && CORRECTION_CONTEXT.stream().anyMatch(title::contains)) {
            tags.add("FINANCIAL_REPORT_CORRECTION");
            groups.add(Group.FINANCIAL_LITIGATION);
            severity = max(severity, Severity.HIGH);
        }
        return new Match(severity, List.copyOf(tags), Set.copyOf(groups), title);
    }

    public static String normalizeTitle(String value) {
        if (value == null) {
            throw new IllegalArgumentException("公告标题不能为空");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder punctuation = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            punctuation.append(switch (character) {
                case '，', '、' -> ',';
                case '。' -> '.';
                case '；' -> ';';
                case '：' -> ':';
                case '！' -> '!';
                case '？' -> '?';
                case '【' -> '[';
                case '】' -> ']';
                case '（' -> '(';
                case '）' -> ')';
                case '“', '”' -> '"';
                case '‘', '’' -> '\'';
                default -> character;
            });
        }
        String result = punctuation.toString().trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
        if (result.isEmpty()) {
            throw new IllegalArgumentException("公告标题不能为空");
        }
        return result;
    }

    public static Severity groupSeverity(
            List<RiskEvent> events,
            Group group
    ) {
        Severity severity = Severity.INFO;
        for (RiskEvent event : events) {
            if (event.match().groups().contains(group)) {
                severity = max(severity, event.match().severity());
            }
        }
        return severity;
    }

    public static int groupCount(List<RiskEvent> events, Group group) {
        return (int) events.stream()
                .filter(value -> value.match().groups().contains(group))
                .count();
    }

    private static BigDecimal recencyFactor(long ageDays) {
        if (ageDays <= 7) return new BigDecimal("1.00");
        if (ageDays <= 30) return new BigDecimal("0.75");
        if (ageDays <= 90) return new BigDecimal("0.50");
        return new BigDecimal("0.25");
    }

    private static int baseDeduction(Severity severity) {
        return switch (severity) {
            case CRITICAL -> 40;
            case HIGH -> 25;
            case WARN -> 10;
            case INFO -> 0;
        };
    }

    private static Severity max(Severity left, Severity right) {
        return severityRank(left) >= severityRank(right) ? left : right;
    }

    private static int severityRank(Severity severity) {
        return switch (severity) {
            case INFO -> 0;
            case WARN -> 1;
            case HIGH -> 2;
            case CRITICAL -> 3;
        };
    }

    private static List<Rule> rules() {
        List<Rule> values = new ArrayList<>();
        add(values, Group.REGULATORY_DELISTING, Severity.CRITICAL,
                DELISTING_EXCLUSIONS, "DELISTING_TERMINATION",
                "终止上市", "可能被终止上市", "退市风险警示", "暂停上市", "进入退市整理期");
        add(values, Group.REGULATORY_DELISTING, Severity.HIGH,
                DELISTING_EXCLUSIONS, "OTHER_RISK_WARNING",
                "实施其他风险警示", "被实施风险警示");
        add(values, Group.REGULATORY_DELISTING, Severity.CRITICAL,
                Set.of(), "REGULATORY_ENFORCEMENT_CRITICAL",
                "立案调查", "涉嫌违法违规", "行政处罚决定书", "公开谴责", "纪律处分");
        add(values, Group.REGULATORY_DELISTING, Severity.HIGH,
                Set.of(), "REGULATORY_ENFORCEMENT_HIGH",
                "监管措施", "警示函", "责令改正");
        add(values, Group.REGULATORY_DELISTING, Severity.WARN,
                Set.of(), "REGULATORY_INQUIRY",
                "问询函", "关注函", "监管工作函");

        add(values, Group.FINANCIAL_LITIGATION, Severity.CRITICAL,
                Set.of(), "FINANCIAL_DEBT_CRITICAL",
                "无法表示意见", "否定意见", "破产重整", "预重整", "债务违约",
                "债务逾期", "不能清偿到期债务");
        add(values, Group.FINANCIAL_LITIGATION, Severity.HIGH,
                Set.of(), "FINANCIAL_PERFORMANCE_HIGH",
                "业绩预亏", "首亏", "续亏", "业绩大幅下降", "业绩下修",
                "会计差错更正", "财务数据更正", "保留意见", "非标准审计意见");
        add(values, Group.FINANCIAL_LITIGATION, Severity.HIGH,
                Set.of(), "LITIGATION_OPERATION_HIGH",
                "重大诉讼", "重大仲裁", "被申请破产", "主要银行账户被冻结",
                "停产", "重大安全事故", "重大合同终止", "核心业务暂停");
        add(values, Group.FINANCIAL_LITIGATION, Severity.WARN,
                Set.of(), "LITIGATION_PROGRESS",
                "诉讼进展", "仲裁进展", "风险提示公告");

        add(values, Group.OWNERSHIP_OPERATION, Severity.HIGH,
                OWNERSHIP_EXCLUSIONS, "OWNERSHIP_ENFORCEMENT_HIGH",
                "被动减持", "司法拍卖", "股份冻结", "轮候冻结", "质押违约", "平仓风险");
        add(values, Group.OWNERSHIP_OPERATION, Severity.WARN,
                OWNERSHIP_EXCLUSIONS, "OWNERSHIP_EXPOSURE_WARN",
                "减持计划", "减持进展", "股份质押");
        add(values, Group.OWNERSHIP_OPERATION, Severity.HIGH,
                Set.of(), "FUND_OCCUPATION_HIGH",
                "违规担保", "资金占用", "关联方占用");
        add(values, Group.OWNERSHIP_OPERATION, Severity.WARN,
                Set.of(), "EXTERNAL_GUARANTEE_WARN", "对外担保");

        add(values, Group.RESEARCH_LIMITATIONS, Severity.WARN,
                Set.of(), "CORRECTION_CLARIFICATION_WARN",
                "更正公告", "补充公告", "澄清公告", "致歉公告");
        return List.copyOf(values);
    }

    private static void add(
            List<Rule> values,
            Group group,
            Severity severity,
            Set<String> exclusions,
            String tag,
            String... keywords
    ) {
        for (String keyword : keywords) {
            values.add(new Rule(tag, group, severity, keyword, exclusions));
        }
    }

    public enum Group {
        REGULATORY_DELISTING,
        FINANCIAL_LITIGATION,
        OWNERSHIP_OPERATION,
        RESEARCH_LIMITATIONS
    }

    private record Rule(
            String tag,
            Group group,
            Severity severity,
            String keyword,
            Set<String> exclusions
    ) {
    }

    public record EventFact(
            String sourceAnnouncementId,
            String sourceIdentityStrength,
            String symbol,
            String securityName,
            String title,
            LocalDate reportedPublishDate,
            String sourceUrl,
            java.time.Instant firstObservedAt,
            java.time.Instant knownAt,
            String canonicalContentHash,
            String observationVersion
    ) {
    }

    public record Match(
            Severity severity,
            List<String> tags,
            Set<Group> groups,
            String normalizedTitle
    ) {
    }

    public record RiskEvent(
            EventFact event,
            Match match,
            long ageDays,
            int deduction
    ) {
    }

    public record Evaluation(int score, List<RiskEvent> riskEvents) {
    }
}
