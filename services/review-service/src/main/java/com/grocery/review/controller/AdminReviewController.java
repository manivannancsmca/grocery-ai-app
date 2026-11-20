package com.grocery.review.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import com.grocery.review.dto.ReviewDtos.ModerationRequest;
import com.grocery.review.dto.ReviewDtos.RepublishResult;
import com.grocery.review.dto.ReviewDtos.ReviewView;
import com.grocery.review.dto.ReviewExports;
import com.grocery.review.service.ModerationService;
import com.grocery.review.service.RatingSummaryService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Review moderation (contract §1). */
@RestController
@RequestMapping("/api/admin/reviews")
public class AdminReviewController {

    /** Who may decide reviews. */
    static final String MODERATORS = "hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT')";

    private final ModerationService moderation;
    private final RatingSummaryService summaries;
    private final Exporter exporter;

    public AdminReviewController(ModerationService moderation, RatingSummaryService summaries, Exporter exporter) {
        this.moderation = moderation;
        this.summaries = summaries;
        this.exporter = exporter;
    }

    /** Any admin; {@code status} defaults to FLAGGED. */
    @GetMapping
    @PreAuthorize(Roles.ANY_ADMIN)
    public PageResponse<ReviewView> list(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String targetType,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return moderation.list(status, targetType, q, page, size);
    }

    /**
     * The moderation list as a file ({@code format} = csv | xlsx | pdf): the same filters ({@code status} defaults to
     * FLAGGED), every page, the most recently submitted first. Any admin, like the list.
     */
    @GetMapping("/export")
    @PreAuthorize(Roles.ANY_ADMIN)
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String targetType,
                       @RequestParam(required = false) String q, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, ReviewExports.ADMIN_REVIEWS.visibleTo(user),
                moderation.listExport(status, targetType, q),
                Exporter.filters("Status", ModerationService.wantedStatus(status), "Type", targetType, "Search", q));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize(MODERATORS)
    public ReviewView publish(CurrentUser admin, @PathVariable UUID id) {
        return moderation.publish(admin, id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(MODERATORS)
    public ReviewView reject(CurrentUser admin, @PathVariable UUID id, @Valid @RequestBody ModerationRequest request) {
        return moderation.reject(admin, id, request.reason());
    }

    @PostMapping("/{id}/remove")
    @PreAuthorize(MODERATORS)
    public ReviewView remove(CurrentUser admin, @PathVariable UUID id, @Valid @RequestBody ModerationRequest request) {
        return moderation.remove(admin, id, request.reason());
    }

    /**
     * Not in the contract table: re-sends {@code RatingSummaryUpdated} for every published summary with its current
     * version, for rebuilding a consumer's copy (e.g. catalog-query's search index). Platform operators only.
     */
    @PostMapping("/summaries/republish")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public RepublishResult republishSummaries() {
        return new RepublishResult(summaries.republishAll());
    }
}
