package com.grocery.catalog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Admin category API bodies (docs/api/phase-3-catalog-and-search.md §4). */
public final class CategoryDtos {

    private CategoryDtos() {
    }

    public record CategoryRequest(
            @NotBlank @Size(max = 80) String name,
            UUID parentId,                                   // null = top level
            @Size(max = 500) String description,
            @Size(max = 500)
            @Pattern(regexp = "^$|" + ProductDtos.HTTP_URL, flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = "must be an http(s) URL") String imageUrl,
            @Min(0) @Max(9999) Integer sortOrder) {          // null = 0
    }

    /** One node of the admin tree (inactive categories included), children sorted by sortOrder, then name. */
    public record AdminCategoryNode(UUID id, UUID parentId, String name, String description, String imageUrl,
                                    int sortOrder, boolean active, int depth, long productCount,
                                    List<AdminCategoryNode> children) {
    }
}
