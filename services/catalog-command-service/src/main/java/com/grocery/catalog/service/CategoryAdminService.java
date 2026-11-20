package com.grocery.catalog.service;

import com.grocery.catalog.dto.CategoryDtos.AdminCategoryNode;
import com.grocery.catalog.dto.CategoryDtos.CategoryRequest;
import com.grocery.catalog.entity.Category;
import com.grocery.catalog.entity.Product;
import com.grocery.catalog.entity.ProductStatus;
import com.grocery.catalog.mapper.CategoryMapper;
import com.grocery.catalog.mapper.ProductMapper;
import com.grocery.catalog.repository.CategoryRepository;
import com.grocery.catalog.repository.ProductRepository;
import com.grocery.common.id.Ids;
import com.grocery.common.web.BusinessRuleException;
import com.grocery.common.web.ConflictException;
import com.grocery.common.web.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Admin management of the category tree (contract §4). Every command first locks the whole tree
 * ({@link CategoryRepository#lockAll()}), validates against it in memory, changes it, and then — per
 * the rule on {@link SnapshotPublisher} — publishes {@code CategoryChanged} for every category whose
 * snapshot changed and re-snapshots the products of the affected subtree (locking them first).
 */
@Service
public class CategoryAdminService {

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final SnapshotPublisher snapshots;
    private final Clock clock;

    public CategoryAdminService(CategoryRepository categories, ProductRepository products,
                                SnapshotPublisher snapshots, Clock clock) {
        this.categories = categories;
        this.products = products;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    /** The whole tree, inactive categories included, with non-archived product counts. */
    @Transactional(readOnly = true)
    public List<AdminCategoryNode> tree() {
        return CategoryMapper.toForest(new CategoryTree(categories.findAll()), productCounts());
    }

    @Transactional
    public AdminCategoryNode create(CategoryRequest request) {
        CategoryTree tree = new CategoryTree(categories.lockAll());
        Category parent = request.parentId() == null ? null : requireParent(tree, request.parentId());
        int depth = parent == null ? 1 : parent.getDepth() + 1;
        if (depth > Category.MAX_DEPTH) {
            throw tooDeep();
        }
        String name = request.name().trim();
        if (tree.siblingNameTaken(request.parentId(), name, null)) {
            throw nameTaken(name);
        }
        Category category = Category.create(Ids.newId(), request.parentId(), depth, name,
                ProductMapper.trim(request.description()), ProductMapper.trim(request.imageUrl()),
                sortOrder(request), clock.instant());
        tree.add(category);
        snapshots.categoryChanged(category, tree);
        categories.save(category);
        flush();
        return CategoryMapper.toNode(category, tree, Map.of());
    }

    /**
     * Edit and/or move. A rename or move changes the path of the whole subtree, so every descendant gets
     * a new CategoryChanged and every non-archived product below gets a new ProductUpserted.
     */
    @Transactional
    public AdminCategoryNode update(UUID id, CategoryRequest request) {
        CategoryTree tree = new CategoryTree(categories.lockAll());
        Category category = require(tree, id);
        UUID newParentId = request.parentId();
        boolean moved = !Objects.equals(newParentId, category.getParentId());
        if (moved) {
            if (newParentId != null) {
                requireParent(tree, newParentId);
                if (tree.isSelfOrDescendant(newParentId, id)) {
                    throw new ConflictException("CATEGORY_CYCLE",
                            "A category cannot be moved under itself or one of its sub-categories");
                }
            }
            int newDepth = newParentId == null ? 1 : tree.get(newParentId).getDepth() + 1;
            if (newDepth + tree.height(id) - 1 > Category.MAX_DEPTH) {
                throw tooDeep();
            }
        }
        String name = request.name().trim();
        if (tree.siblingNameTaken(newParentId, name, id)) {
            throw nameTaken(name);
        }
        boolean renamed = !name.equals(category.getName());
        Instant now = clock.instant();
        category.update(name, ProductMapper.trim(request.description()), ProductMapper.trim(request.imageUrl()),
                sortOrder(request), now);
        if (moved) {
            category.moveTo(newParentId, now);
        }
        tree.reindex();
        tree.recomputeDepths(id);
        flush();

        if (moved || renamed) {
            publishSubtree(id, tree);
        } else {
            snapshots.categoryChanged(category, tree);
        }
        return CategoryMapper.toNode(category, tree, productCounts());
    }

    /** Hides the category, its sub-categories and their products from customers. */
    @Transactional
    public AdminCategoryNode deactivate(UUID id) {
        return setActive(id, false);
    }

    @Transactional
    public AdminCategoryNode activate(UUID id) {
        return setActive(id, true);
    }

    private AdminCategoryNode setActive(UUID id, boolean active) {
        CategoryTree tree = new CategoryTree(categories.lockAll());
        Category category = require(tree, id);
        if (category.isActive() != active) {
            category.setActive(active, clock.instant());
            flush();
            publishSubtree(id, tree);        // effectiveActive of every descendant may change
        }
        return CategoryMapper.toNode(category, tree, productCounts());
    }

    /** Only empty categories: no sub-categories and no non-archived products. */
    @Transactional
    public void delete(UUID id) {
        CategoryTree tree = new CategoryTree(categories.lockAll());
        Category category = require(tree, id);
        if (!tree.children(id).isEmpty()) {
            throw new ConflictException("CATEGORY_IN_USE", "The category still has sub-categories");
        }
        if (products.countLiveInCategory(id, ProductStatus.ARCHIVED) > 0) {
            throw new ConflictException("CATEGORY_IN_USE", "The category still has products");
        }
        snapshots.categoryDeleted(category, tree);
        categories.delete(category);
        categories.flush();     // archived products keep their row; their category_id becomes NULL
    }

    /** CategoryChanged for the category and all descendants, then ProductUpserted for their products. */
    private void publishSubtree(UUID id, CategoryTree tree) {
        List<Category> subtree = tree.subtree(id);
        List<Product> affected = products.lockLiveByCategoryIds(
                subtree.stream().map(Category::getId).toList(), ProductStatus.ARCHIVED);
        subtree.forEach(c -> snapshots.categoryChanged(c, tree));
        snapshots.productsChanged(affected, tree);
    }

    private Map<UUID, Long> productCounts() {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : products.countLiveByCategory(ProductStatus.ARCHIVED)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** Flushes category changes; a sibling-name race that slipped past the in-memory check hits the unique key. */
    private void flush() {
        try {
            categories.flush();
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolation(e, "uk_category_sibling_name")) {
                throw new ConflictException("CATEGORY_NAME_TAKEN",
                        "Another category with this name already exists at this level");
            }
            throw e;
        }
    }

    private static Category require(CategoryTree tree, UUID id) {
        Category category = tree.get(id);
        if (category == null) {
            throw notFound();
        }
        return category;
    }

    private static Category requireParent(CategoryTree tree, UUID parentId) {
        return require(tree, parentId);
    }

    private static int sortOrder(CategoryRequest request) {
        return request.sortOrder() == null ? 0 : request.sortOrder();
    }

    private static NotFoundException notFound() {
        return new NotFoundException("CATEGORY_NOT_FOUND", "Category not found");
    }

    private static BusinessRuleException tooDeep() {
        return new BusinessRuleException("CATEGORY_TOO_DEEP",
                "Categories can be at most " + Category.MAX_DEPTH + " levels deep");
    }

    private static ConflictException nameTaken(String name) {
        return new ConflictException("CATEGORY_NAME_TAKEN",
                "A category named '" + name + "' already exists at this level");
    }
}
