package com.stockquant.server.researchselection;

/** Stable, user-facing failure categories derived only from safe reason codes. */
public final class ResearchSelectionFailureCategory {
    private ResearchSelectionFailureCategory() {
    }

    public static String from(String reason) {
        if (reason == null || !reason.matches("[A-Z][A-Z0-9_]{3,127}")) {
            return "UNKNOWN";
        }
        if (reason.contains("BUDGET")) return "BUDGET";
        if (reason.contains("DATABASE") || reason.contains("PERSIST")) {
            return "DATABASE";
        }
        if (reason.contains("DATA") || reason.contains("CALENDAR")
                || reason.contains("FACT") || reason.contains("WINDOW")) {
            return "DATA";
        }
        if (reason.contains("MODEL") || reason.contains("BAILIAN")
                || reason.contains("LLM")) return "MODEL";
        if (reason.contains("TUSHARE") || reason.contains("PROVIDER")
                || reason.contains("HTTP")) return "PROVIDER";
        if (reason.contains("BUILD") || reason.contains("GIT")
                || reason.contains("ARTIFACT") || reason.contains("JAR")) {
            return "BUILD";
        }
        if (reason.contains("BROKER") || reason.contains("REQUEST")
                || reason.contains("DISPATCH")) return "BROKER";
        return "UNKNOWN";
    }
}
