package com.grocery.catalogquery.service;

import com.grocery.catalogquery.document.ProductDocument;
import com.grocery.catalogquery.document.RestaurantDocument;
import com.grocery.catalogquery.event.EventPayloads.RatingSummaryUpdated;
import com.grocery.catalogquery.index.IndexDefinition;
import com.grocery.catalogquery.index.SearchDocumentWriter;
import com.grocery.common.kafka.InvalidEventException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * review.events → the rating part ({@code rating}, {@code ratingCount}, version {@code ratingVersion}) of the
 * {@code products} and {@code restaurants} documents (docs/api/phase-12-post-mvp-features.md §1).
 *
 * <p>{@code PRODUCT} targets go to products, {@code RESTAURANT} targets to restaurants; other target types (dishes,
 * vendors, delivery partners) are not part of the search model and are ignored. The part is written only when the
 * event's {@code summaryVersion} is newer than the stored {@code ratingVersion} (replays, duplicates and
 * out-of-order events are no-ops) and never touches the other parts. A summary for a document that does not exist
 * yet waits in a hidden placeholder, like an early price. A summary without ratings ({@code ratingCount} 0) removes
 * {@code rating}, so the target counts as unrated again.
 */
@Service
public class RatingIndexer {

    static final String PRODUCT = "PRODUCT";
    static final String RESTAURANT = "RESTAURANT";
    static final BigDecimal MAX_RATING = BigDecimal.valueOf(5);

    private static final Logger log = LoggerFactory.getLogger(RatingIndexer.class);

    private final SearchDocumentWriter writer;

    public RatingIndexer(SearchDocumentWriter writer) {
        this.writer = writer;
    }

    public void onRatingSummaryUpdated(RatingSummaryUpdated summary) {
        if (blank(summary.targetType()) || blank(summary.targetId()) || summary.summaryVersion() == null
                || summary.ratingCount() == null) {
            throw new InvalidEventException(
                    "RatingSummaryUpdated without targetType, targetId, ratingCount or summaryVersion");
        }
        if (!PRODUCT.equals(summary.targetType()) && !RESTAURANT.equals(summary.targetType())) {
            log.debug("RatingSummaryUpdated for {} {}: not indexed", summary.targetType(), summary.targetId());
            return;
        }
        String targetId = uuid(summary.targetId());
        int count = summary.ratingCount();
        if (count < 0) {
            throw new InvalidEventException("RatingSummaryUpdated for " + targetId + " has a negative ratingCount");
        }
        BigDecimal rating = null;
        if (count > 0) {
            if (summary.averageRating() == null || summary.averageRating().signum() < 0
                    || summary.averageRating().compareTo(MAX_RATING) > 0) {
                throw new InvalidEventException("RatingSummaryUpdated for " + targetId
                        + " needs an averageRating between 0 and 5");
            }
            rating = summary.averageRating().setScale(2, RoundingMode.HALF_UP);
        }
        long version = summary.summaryVersion();
        if (PRODUCT.equals(summary.targetType())) {
            writer.merge(IndexDefinition.PRODUCTS, targetId, ProductDocument.RATING_VERSION, version,
                    ProductDocument.RATING_FIELDS, new ProductDocument.RatingPart(targetId, rating, count, version));
        } else {
            writer.merge(IndexDefinition.RESTAURANTS, targetId, RestaurantDocument.RATING_VERSION, version,
                    RestaurantDocument.RATING_FIELDS,
                    new RestaurantDocument.RatingPart(targetId, rating, count, version));
        }
    }

    /** The id as the other parts store it (lower-case UUID text). */
    private static String uuid(String id) {
        try {
            return UUID.fromString(id.strip()).toString();
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException("RatingSummaryUpdated with an invalid targetId '" + id + "'", e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
