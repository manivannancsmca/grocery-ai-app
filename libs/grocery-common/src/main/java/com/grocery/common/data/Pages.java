package com.grocery.common.data;

import com.grocery.common.web.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.function.Function;

/** Pagination helpers: safe page requests (size capped) and Spring Data page → {@link PageResponse}. */
public final class Pages {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private Pages() {
    }

    /** Clamps client input: page ≥ 0, 1 ≤ size ≤ 100. */
    public static Pageable request(Integer page, Integer size, Sort sort) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return PageRequest.of(safePage, safeSize, sort);
    }

    public static <E, R> PageResponse<R> toResponse(Page<E> page, Function<E, R> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
