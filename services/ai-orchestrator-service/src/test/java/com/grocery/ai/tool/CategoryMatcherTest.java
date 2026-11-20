package com.grocery.ai.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Category names as people type them, matched against the catalog's category tree. */
class CategoryMatcherTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<CategoryMatcher.Category> TREE = CategoryMatcher.flatten(JSON.readTree("""
            [{"id":"00000000-0000-7000-8000-000000000001","name":"Fruits","productCount":12,"children":[
               {"id":"00000000-0000-7000-8000-000000000011","name":"Berries","productCount":3,"children":[]}]},
             {"id":"00000000-0000-7000-8000-000000000002","name":"Vegetables","productCount":30,"children":[]},
             {"id":"00000000-0000-7000-8000-000000000003","name":"Dairy Products","productCount":20,"children":[]},
             {"id":"00000000-0000-7000-8000-000000000004","name":"Rice & Grains","productCount":8,"children":[]}]
            """));

    @ParameterizedTest
    @CsvSource({
            "Fruits, Fruits",
            "fruit, Fruits",
            "FRUITS., Fruits",
            "berry, Berries",
            "dairy, Dairy Products",
            "vegitables, Vegetables",
            "vegetable, Vegetables",
            "rice and grains, Rice & Grains",
            "rice, Rice & Grains"
    })
    void looseNamesFindTheCategory(String typed, String expected) {
        assertThat(CategoryMatcher.match(TREE, typed)).get().extracting(CategoryMatcher.Category::name)
                .isEqualTo(expected);
    }

    @Test
    void unrelatedOrEmptyNamesFindNothingAndTheLargestCategoriesAreSuggested() {
        assertThat(CategoryMatcher.match(TREE, "electronics")).isEmpty();
        assertThat(CategoryMatcher.match(TREE, "  ")).isEmpty();
        assertThat(CategoryMatcher.match(TREE, "ab")).isEmpty();
        assertThat(CategoryMatcher.suggestions(TREE, 3)).containsExactly("Vegetables", "Dairy Products", "Fruits");
    }

    @Test
    void theTreeIsFlattenedWithChildren() {
        assertThat(TREE).extracting(CategoryMatcher.Category::name)
                .containsExactly("Fruits", "Berries", "Vegetables", "Dairy Products", "Rice & Grains");
        assertThat(CategoryMatcher.flatten(JSON.readTree("{}"))).isEmpty();
    }
}
