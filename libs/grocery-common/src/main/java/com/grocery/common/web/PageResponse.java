package com.grocery.common.web;

import java.util.List;
import java.util.function.Function;

/**
 * Stable JSON shape for paginated responses:
 * {@code { "content": [...], "page": 0, "size": 20, "totalElements": 42, "totalPages": 3 }}.
 * Build it from a Spring Data page with {@code com.grocery.common.data.Pages}.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(content.stream().map(mapper).toList(), page, size, totalElements, totalPages);
    }
}
