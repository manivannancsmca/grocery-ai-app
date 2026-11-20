package com.grocery.review.entity;

/**
 * <pre>
 * submit / edit ──► PUBLISHED ──remove (admin)──► REMOVED
 *              └──► FLAGGED ───publish (admin)──► PUBLISHED
 *                          └───reject (admin)───► REJECTED
 * </pre>
 * Automatic moderation decides PUBLISHED or FLAGGED on every submission and edit (an edit re-moderates whatever the
 * status was). Only PUBLISHED reviews are public and count in the rating summary.
 */
public enum ReviewStatus {
    PUBLISHED,
    FLAGGED,
    REJECTED,
    REMOVED
}
