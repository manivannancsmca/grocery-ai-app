package com.grocery.review.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.review.dto.ReviewDtos.ReplyRequest;
import com.grocery.review.dto.ReviewDtos.ReviewView;
import com.grocery.review.dto.ReviewExports;
import com.grocery.review.service.StoreReviewService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** A vendor's or restaurant's reviews and its replies (contract §1). */
@RestController
@RequestMapping("/api/store/reviews")
@PreAuthorize("hasAnyRole('VENDOR','RESTAURANT_OWNER')")
public class StoreReviewController {

    private final StoreReviewService store;
    private final Exporter exporter;

    public StoreReviewController(StoreReviewService store, Exporter exporter) {
        this.store = store;
        this.exporter = exporter;
    }

    @GetMapping
    public PageResponse<ReviewView> list(CurrentUser user, @RequestParam(required = false) String targetType,
                                         @RequestParam(required = false) Integer rating,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return store.list(user, targetType, rating, page, size);
    }

    /**
     * The caller's store's reviews as a file ({@code format} = csv | xlsx | pdf): the same filters, every page, newest
     * first. The store comes from the JWT; reviewers appear by their public name only.
     */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String targetType,
                       @RequestParam(required = false) Integer rating, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, ReviewExports.STORE_REVIEWS,
                store.listExport(user, targetType, rating), Exporter.filters("Type", targetType, "Rating", rating));
    }

    /** 404 REVIEW_NOT_FOUND unless it is a published review of the caller's store or its products / dishes. */
    @PutMapping("/{id}/reply")
    public ReviewView reply(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody ReplyRequest request) {
        return store.reply(user, id, request.text());
    }
}
