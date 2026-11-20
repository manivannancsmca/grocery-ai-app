package com.grocery.review.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.review.dto.ReviewDtos.EditReviewRequest;
import com.grocery.review.dto.ReviewDtos.EligibleTarget;
import com.grocery.review.dto.ReviewDtos.PublicReview;
import com.grocery.review.dto.ReviewDtos.RatingSummaryView;
import com.grocery.review.dto.ReviewDtos.ReviewView;
import com.grocery.review.dto.ReviewDtos.SubmitReviewRequest;
import com.grocery.review.dto.ReviewExports;
import com.grocery.review.dto.ReviewMapper;
import com.grocery.review.entity.TargetType;
import com.grocery.review.service.RatingSummaryService;
import com.grocery.review.service.ReviewService;
import com.grocery.review.service.ReviewService.PublicSort;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Customers' reviews and the public reads (docs/api/phase-12-post-mvp-features.md §1). {@code GET /api/reviews} and
 * {@code GET /api/reviews/summary} need no token (see {@code ReviewSecurityConfig}).
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviews;
    private final RatingSummaryService summaries;
    private final ReviewMapper mapper;
    private final Exporter exporter;

    public ReviewController(ReviewService reviews, RatingSummaryService summaries, ReviewMapper mapper,
                            Exporter exporter) {
        this.reviews = reviews;
        this.summaries = summaries;
        this.mapper = mapper;
        this.exporter = exporter;
    }

    @GetMapping("/eligible")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<EligibleTarget> eligible(CurrentUser user, @RequestParam(required = false) UUID orderId) {
        return reviews.eligible(user, orderId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    public ReviewView submit(CurrentUser user, @Valid @RequestBody SubmitReviewRequest request) {
        return reviews.submit(user, request);
    }

    /** The author only (any other caller gets 404). */
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ReviewView edit(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody EditReviewRequest request) {
        return reviews.edit(user, id, request);
    }

    /** The author only (any other caller gets 404). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void delete(CurrentUser user, @PathVariable UUID id) {
        reviews.delete(user, id);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('CUSTOMER')")
    public PageResponse<ReviewView> mine(CurrentUser user, @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return reviews.mine(user, page, size);
    }

    /**
     * The caller's reviews as a file ({@code format} = csv | xlsx | pdf): every status, every page, newest first. Not
     * one of the public reads: a customer token is required, like {@code GET /api/reviews/mine}.
     */
    @GetMapping("/mine/export")
    @PreAuthorize("hasRole('CUSTOMER')")
    public void mineExport(CurrentUser user, @RequestParam(required = false) String format,
                           HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, ReviewExports.MY_REVIEWS, reviews.mineExport(user));
    }

    /** Public: the published reviews of one target; {@code sort} newest (default), highest or lowest. */
    @GetMapping
    public PageResponse<PublicReview> published(@RequestParam(required = false) String targetType,
                                                @RequestParam(required = false) UUID targetId,
                                                @RequestParam(required = false) String sort,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        PublicSort order = sort == null || sort.isBlank() ? PublicSort.NEWEST
                : BadRequestException.parseEnum(PublicSort.class, "sort", sort.strip().toUpperCase(Locale.ROOT));
        return reviews.published(target(targetType, targetId), targetId, order, page, size);
    }

    /** Public: average (2 decimals), count and distribution (index 0 = 1 star … index 4 = 5 stars). */
    @GetMapping("/summary")
    public RatingSummaryView summary(@RequestParam(required = false) String targetType,
                                     @RequestParam(required = false) UUID targetId) {
        return mapper.summary(summaries.summary(target(targetType, targetId), targetId));
    }

    /** Both parameters are required: 400 INVALID_PARAMETER otherwise. */
    private static TargetType target(String targetType, UUID targetId) {
        if (targetType == null || targetType.isBlank() || targetId == null) {
            throw new BadRequestException("INVALID_PARAMETER", "targetType and targetId are required");
        }
        return BadRequestException.parseEnum(TargetType.class, "targetType",
                targetType.strip().toUpperCase(Locale.ROOT));
    }
}
