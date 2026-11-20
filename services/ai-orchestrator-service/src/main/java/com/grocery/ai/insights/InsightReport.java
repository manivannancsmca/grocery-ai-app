package com.grocery.ai.insights;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code InsightReport} (contract §6): what the model returns through Spring AI structured output, or what the rules
 * build when it cannot. {@code date}, {@code generatedBy} and {@code generatedAt} are always set by the server.
 */
public record InsightReport(LocalDate date, String headline, List<Kpi> kpis, List<String> observations,
                            List<String> risks, List<String> suggestedActions, GeneratedBy generatedBy,
                            Instant generatedAt) {

    public record Kpi(String name, String value, String note) {
    }

    public record GeneratedBy(String provider, String model) {
    }

    /** The part the model writes ({@code .entity(Draft.class)}). */
    public record Draft(String headline, List<Kpi> kpis, List<String> observations, List<String> risks,
                        List<String> suggestedActions) {
    }
}
