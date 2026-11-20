package com.grocery.catalogquery.service;

import com.grocery.common.web.BadRequestException;

/**
 * Validated paging for search endpoints: page ≥ 0, 1 ≤ size ≤ 50 (out-of-range values are clamped, like
 * everywhere else on the platform), and page × size ≤ 1,000 (deep paging is 400 INVALID_PARAMETER).
 */
public record SearchPage(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;
    public static final int MAX_RESULTS = 1_000;

    public static SearchPage of(Integer page, Integer size) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        if ((long) safePage * safeSize > MAX_RESULTS) {
            throw new BadRequestException("INVALID_PARAMETER",
                    "page × size may not exceed " + MAX_RESULTS + " results; refine the search instead");
        }
        return new SearchPage(safePage, safeSize);
    }

    public int from() {
        return page * size;
    }

    public int totalPages(long totalElements) {
        return (int) ((totalElements + size - 1) / size);
    }
}
