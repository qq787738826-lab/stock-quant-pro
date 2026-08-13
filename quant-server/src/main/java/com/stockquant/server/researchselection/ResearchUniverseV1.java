package com.stockquant.server.researchselection;

import com.stockquant.core.research.StrategyResearchModels.Security;

import java.util.List;
import java.util.Map;

/**
 * Small, versioned沪深主板 universe used by V1.0.1 selection and new shadow
 * runs.  It is deliberately code-defined: changing membership creates a new
 * version instead of rewriting historical research lineage.
 */
public final class ResearchUniverseV1 {
    public static final String VERSION = "RESEARCH_UNIVERSE_V1";

    private static final List<Constituent> CONSTITUENTS = List.of(
            item("600000", "SSE", "浦发银行", "银行"),
            item("600009", "SSE", "上海机场", "交通运输"),
            item("600019", "SSE", "宝钢股份", "钢铁"),
            item("600028", "SSE", "中国石化", "石油石化"),
            item("600030", "SSE", "中信证券", "非银金融"),
            item("600036", "SSE", "招商银行", "银行"),
            item("600048", "SSE", "保利发展", "房地产"),
            item("600050", "SSE", "中国联通", "通信"),
            item("600104", "SSE", "上汽集团", "汽车"),
            item("600276", "SSE", "恒瑞医药", "医药生物"),
            item("600309", "SSE", "万华化学", "基础化工"),
            item("600519", "SSE", "贵州茅台", "食品饮料"),
            item("600690", "SSE", "海尔智家", "家用电器"),
            item("600887", "SSE", "伊利股份", "食品饮料"),
            item("601088", "SSE", "中国神华", "煤炭"),
            item("601318", "SSE", "中国平安", "非银金融"),
            item("601398", "SSE", "工商银行", "银行"),
            item("601668", "SSE", "中国建筑", "建筑装饰"),
            item("601857", "SSE", "中国石油", "石油石化"),
            item("601899", "SSE", "紫金矿业", "有色金属"),
            item("000001", "SZSE", "平安银行", "银行"),
            item("000333", "SZSE", "美的集团", "家用电器"),
            item("000538", "SZSE", "云南白药", "医药生物"),
            item("000651", "SZSE", "格力电器", "家用电器"),
            item("000858", "SZSE", "五粮液", "食品饮料")
    );

    private static final Map<String, Constituent> BY_CODE = CONSTITUENTS
            .stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    value -> value.security().canonicalCode(), value -> value));

    private ResearchUniverseV1() {
    }

    public static List<Constituent> constituents() {
        return CONSTITUENTS;
    }

    public static List<Security> securities() {
        return CONSTITUENTS.stream().map(Constituent::security).toList();
    }

    public static Constituent require(Security security) {
        Constituent value = BY_CODE.get(security.canonicalCode());
        if (value == null) {
            throw new IllegalArgumentException(
                    "RESEARCH_UNIVERSE_V1_SECURITY_NOT_FOUND");
        }
        return value;
    }

    public static Security benchmark() {
        return new Security("600000", "SSE");
    }

    private static Constituent item(
            String symbol,
            String exchange,
            String name,
            String industry
    ) {
        return new Constituent(new Security(symbol, exchange), name, industry);
    }

    public record Constituent(
            Security security,
            String name,
            String industry
    ) {
        public Constituent {
            if (security == null || name == null || name.isBlank()
                    || industry == null || industry.isBlank()) {
                throw new IllegalArgumentException(
                        "RESEARCH_UNIVERSE_V1_CONSTITUENT_INVALID");
            }
        }
    }
}
