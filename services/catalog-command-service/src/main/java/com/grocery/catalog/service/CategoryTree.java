package com.grocery.catalog.service;

import com.grocery.catalog.entity.Category;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The whole category tree in memory (it is small: at most 3 levels, a few hundred nodes). Answers the
 * questions that need more than one row: paths, effective activity, subtrees, heights, sibling names.
 * Built from the managed entities of the current transaction, so changes made to them are visible
 * after {@link #reindex()}.
 */
public final class CategoryTree {

    static final Comparator<Category> DISPLAY_ORDER = Comparator.comparingInt(Category::getSortOrder)
            .thenComparing(Category::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Category::getId);

    private final Map<UUID, Category> byId = new HashMap<>();
    private final Map<UUID, List<Category>> childrenByParent = new HashMap<>();

    public CategoryTree(Collection<Category> categories) {
        categories.forEach(c -> byId.put(c.getId(), c));
        reindex();
    }

    /** Rebuilds the child lists after nodes were added, removed or moved. */
    public void reindex() {
        childrenByParent.clear();
        for (Category c : byId.values()) {
            childrenByParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
        }
        childrenByParent.values().forEach(list -> list.sort(DISPLAY_ORDER));
    }

    public void add(Category category) {
        byId.put(category.getId(), category);
        reindex();
    }

    public void remove(Category category) {
        byId.remove(category.getId());
        reindex();
    }

    public Category get(UUID id) {
        return id == null ? null : byId.get(id);
    }

    public boolean contains(UUID id) {
        return id != null && byId.containsKey(id);
    }

    public List<Category> roots() {
        return children(null);
    }

    public List<Category> children(UUID parentId) {
        return childrenByParent.getOrDefault(parentId, List.of());
    }

    /** Root → this category. Empty when the id is unknown. */
    public List<Category> path(UUID id) {
        List<Category> path = new ArrayList<>();
        Category current = get(id);
        while (current != null && path.size() <= byId.size()) {
            path.addFirst(current);
            current = get(current.getParentId());
        }
        return path;
    }

    /** The category's own flag AND every ancestor's flag. */
    public boolean effectiveActive(UUID id) {
        List<Category> path = path(id);
        return !path.isEmpty() && path.stream().allMatch(Category::isActive);
    }

    /** The category and all its descendants, parents before children. */
    public List<Category> subtree(UUID id) {
        List<Category> result = new ArrayList<>();
        Category root = get(id);
        if (root != null) {
            collect(root, result);
        }
        return result;
    }

    private void collect(Category node, List<Category> into) {
        into.add(node);
        children(node.getId()).forEach(child -> collect(child, into));
    }

    /** Levels in the subtree rooted at {@code id}: 1 for a leaf. */
    public int height(UUID id) {
        int max = 0;
        for (Category child : children(id)) {
            max = Math.max(max, height(child.getId()));
        }
        return max + 1;
    }

    /** True if {@code candidate} is {@code ancestor} itself or one of its descendants. */
    public boolean isSelfOrDescendant(UUID candidate, UUID ancestor) {
        return path(candidate).stream().anyMatch(c -> c.getId().equals(ancestor));
    }

    /** Case-insensitive name clash with another child of {@code parentId} (excluding {@code self}). */
    public boolean siblingNameTaken(UUID parentId, String name, UUID self) {
        return children(parentId).stream()
                .anyMatch(c -> !Objects.equals(c.getId(), self) && c.getName().equalsIgnoreCase(name));
    }

    /** Recomputes the depth of every node in the subtree from its parent's depth. */
    public void recomputeDepths(UUID id) {
        for (Category c : subtree(id)) {
            Category parent = get(c.getParentId());
            c.setDepth(parent == null ? 1 : parent.getDepth() + 1);
        }
    }

    public Collection<Category> all() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }
}
