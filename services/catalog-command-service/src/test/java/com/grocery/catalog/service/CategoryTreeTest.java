package com.grocery.catalog.service;

import com.grocery.catalog.entity.Category;
import com.grocery.catalog.mapper.ProductMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryTreeTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private static Category node(String name, Category parent, int sortOrder) {
        return Category.create(UUID.randomUUID(), parent == null ? null : parent.getId(),
                parent == null ? 1 : parent.getDepth() + 1, name, null, null, sortOrder, NOW);
    }

    @Test
    void pathsSubtreesHeightsAndEffectiveActivity() {
        Category food = node("Food", null, 0);
        Category veg = node("Vegetables", food, 1);
        Category fruit = node("Fruits", food, 0);
        Category leafy = node("Leafy", veg, 0);
        CategoryTree tree = new CategoryTree(List.of(leafy, veg, food, fruit));

        assertThat(tree.path(leafy.getId())).containsExactly(food, veg, leafy);
        assertThat(tree.children(food.getId())).containsExactly(fruit, veg);           // sortOrder, then name
        assertThat(tree.subtree(food.getId())).containsExactly(food, fruit, veg, leafy);
        assertThat(tree.height(food.getId())).isEqualTo(3);
        assertThat(tree.height(leafy.getId())).isEqualTo(1);
        assertThat(tree.isSelfOrDescendant(leafy.getId(), food.getId())).isTrue();
        assertThat(tree.isSelfOrDescendant(food.getId(), leafy.getId())).isFalse();
        assertThat(tree.siblingNameTaken(food.getId(), "FRUITS", null)).isTrue();
        assertThat(tree.siblingNameTaken(food.getId(), "fruits", fruit.getId())).isFalse();

        assertThat(tree.effectiveActive(leafy.getId())).isTrue();
        veg.setActive(false, NOW);
        assertThat(tree.effectiveActive(leafy.getId())).isFalse();
        assertThat(tree.effectiveActive(fruit.getId())).isTrue();
        assertThat(tree.effectiveActive(UUID.randomUUID())).isFalse();
    }

    @Test
    void movingRecomputesDepths() {
        Category a = node("A", null, 0);
        Category b = node("B", null, 0);
        Category bc = node("C", b, 0);
        CategoryTree tree = new CategoryTree(List.of(a, b, bc));
        b.moveTo(a.getId(), NOW);
        tree.reindex();
        tree.recomputeDepths(b.getId());
        assertThat(b.getDepth()).isEqualTo(2);
        assertThat(bc.getDepth()).isEqualTo(3);
        assertThat(tree.roots()).containsExactly(a);
    }

    @Test
    void tagsAreTrimmedLowerCasedAndDeduplicated() {
        assertThat(ProductMapper.normalizeTags(Arrays.asList("Organic", " fresh ", "organic", "Farm  Fresh", " a")))
                .containsExactly("organic", "fresh", "farm fresh");
        assertThat(ProductMapper.normalizeTags(null)).isEmpty();
    }
}
