package com.grocery.ai.insights;

import com.grocery.ai.gateway.CallContext;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** The admin dashboard's "AI insights" card (contract §6). */
@RestController
public class InsightsController {

    private final InsightsService insights;

    public InsightsController(InsightsService insights) {
        this.insights = insights;
    }

    public record InsightsRequest(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    }

    @PostMapping("/api/ai/agents/admin-bi/insights")
    @PreAuthorize(Roles.ANY_ADMIN)
    public InsightReport insights(@RequestBody(required = false) InsightsRequest request, CurrentUser admin) {
        return insights.report(admin, CallContext.current(), request == null ? null : request.date());
    }
}
