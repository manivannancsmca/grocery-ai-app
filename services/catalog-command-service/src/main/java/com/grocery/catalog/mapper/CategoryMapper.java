package com.grocery.catalog.mapper;

import com.grocery.catalog.dto.CategoryDtos.AdminCategoryNode;
import com.grocery.catalog.entity.Category;
import com.grocery.catalog.service.CategoryTree;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CategoryMapper {

    private CategoryMapper() {
    }

    /** The whole forest, roots first, each level sorted by sortOrder, then name. */
    public static List<AdminCategoryNode> toForest(CategoryTree tree, Map<UUID, Long> productCounts) {
        return tree.roots().stream().map(root -> toNode(root, tree, productCounts)).toList();
    }

    /** A node with its whole subtree. */
    public static AdminCategoryNode toNode(Category c, CategoryTree tree, Map<UUID, Long> productCounts) {
        return new AdminCategoryNode(c.getId(), c.getParentId(), c.getName(), c.getDescription(), c.getImageUrl(),
                c.getSortOrder(), c.isActive(), c.getDepth(), productCounts.getOrDefault(c.getId(), 0L),
                tree.children(c.getId()).stream().map(child -> toNode(child, tree, productCounts)).toList());
    }
}
