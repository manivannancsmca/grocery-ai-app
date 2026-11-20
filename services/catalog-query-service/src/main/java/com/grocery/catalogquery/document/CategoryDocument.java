package com.grocery.catalogquery.document;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * {@code categories} index document (id = categoryId, external version = categoryVersion).
 *
 * @param path      root → this category (display only, not indexed)
 * @param pathNames the names of {@code path}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryDocument(
        String categoryId,
        String parentId,
        String name,
        String description,
        String imageUrl,
        Integer sortOrder,
        Integer depth,
        List<IdName> path,
        List<String> pathNames,
        Boolean active,
        Boolean effectiveActive,
        Long categoryVersion) {
}
