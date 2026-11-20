package com.grocery.ai.audit;

import com.grocery.ai.audit.AiMonitoringService.ToolInvocationView;
import com.grocery.common.export.ExportSort;
import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportTable;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** The CSV / Excel / PDF export of the admin AI audit list (the tool calls). */
public final class AuditExports {

    private AuditExports() {
    }

    /**
     * Newest first like the list; a client may sort by time, user or status (the page's column keys {@code when},
     * {@code user}, {@code status}; never-null columns — the tool and the latency may be null).
     */
    public static final ExportSort<ToolInvocation> INVOCATION_SORT = ExportSort.<ToolInvocation>by(
                    Key.desc("createdAt", ToolInvocation::getCreatedAt), Key.desc("id", ToolInvocation::getId))
            .option("when", "createdAt", ToolInvocation::getCreatedAt)
            .option("user", "userId", ToolInvocation::getUserId)
            .option("status", "status", ToolInvocation::getStatus)
            .build();

    /**
     * {@code GET /api/admin/ai/tool-invocations/export}: the list's fields. The model's arguments are what the page
     * shows under the tool, but they carry people's own words (delivery instructions, return notes, reasons), which
     * can hold a phone number or an address: personal, so only support and operations admins get them
     * ({@link ExportTable#visibleTo}). The result is the short summary the page shows, never the full tool result.
     */
    public static final ExportTable<ToolInvocationView> INVOCATIONS = ExportTable.<ToolInvocationView>named(
                    "ai-tool-calls", "AI tool calls")
            .dateTime("Time", ToolInvocationView::createdAt)
            .text("Assistant", ToolInvocationView::agent)
            .text("Tool", ToolInvocationView::tool)
            .text("Arguments", view -> arguments(view.arguments())).personal()
            .text("User ID", ToolInvocationView::userId)
            .text("Roles", view -> roles(view.roles()))
            .label("Status", ToolInvocationView::status)
            .integer("HTTP status", ToolInvocationView::httpStatus)
            .label("Reason", ToolInvocationView::reason)
            .text("Result", ToolInvocationView::resultSummary)
            .integer("Latency (ms)", ToolInvocationView::latencyMs)
            .text("Approval ID", ToolInvocationView::approvalId)
            .build();

    /** The arguments as one line of JSON, like the page; none for an empty object. */
    private static String arguments(JsonNode arguments) {
        return arguments == null || arguments.isEmpty() ? null : arguments.toString();
    }

    /** "CUSTOMER", "SUPER_ADMIN" → "Customer, Super admin". */
    private static String roles(List<String> roles) {
        return roles == null || roles.isEmpty() ? null : roles.stream()
                .map(role -> role.replace('_', ' ').toLowerCase(Locale.ROOT))
                .map(role -> role.isEmpty() ? role : Character.toUpperCase(role.charAt(0)) + role.substring(1))
                .collect(Collectors.joining(", "));
    }
}
