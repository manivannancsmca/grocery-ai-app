package com.grocery.ai.tool;

import com.grocery.ai.support.Json;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds the catalog category a person means by name ("fruits", "Fruit", "vegitables", "dairy"), in the category tree
 * of {@code GET /api/catalog/categories} ({@code [{id, name, productCount, children: [...]}]}). People type
 * categories loosely, so the match is tolerant, from strict to loose:
 * <ol>
 *   <li>the same name, ignoring case, spaces and punctuation;</li>
 *   <li>the same word in singular or plural ("fruit" = "Fruits");</li>
 *   <li>one name contains the other ("dairy" → "Dairy Products");</li>
 *   <li>a small spelling mistake (at most 2 letters different, for words of 5+ letters).</li>
 * </ol>
 * When several categories match at the same level, the one with the most products wins.
 */
final class CategoryMatcher {

    /** One category of the tree, flattened. */
    record Category(UUID id, String name, int productCount) {
    }

    private CategoryMatcher() {
    }

    /** Every category of the tree (parents and children), in tree order. */
    static List<Category> flatten(JsonNode tree) {
        List<Category> categories = new ArrayList<>();
        if (tree != null && tree.isArray()) {
            for (JsonNode node : tree) {
                collect(node, categories);
            }
        }
        return categories;
    }

    private static void collect(JsonNode node, List<Category> into) {
        UUID id = Json.uuid(node, "id");
        String name = Json.text(node, "name");
        if (id != null && name != null && !name.isBlank()) {
            Integer count = Json.integer(node, "productCount");
            into.add(new Category(id, name.strip(), count == null ? 0 : count));
        }
        for (JsonNode child : Json.list(node, "children")) {
            collect(child, into);
        }
    }

    static Optional<Category> match(List<Category> categories, String wanted) {
        String target = normalize(wanted);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        String targetSingular = singular(target);
        List<java.util.function.Predicate<Category>> levels = List.of(
                c -> normalize(c.name()).equals(target),
                c -> singular(normalize(c.name())).equals(targetSingular),
                c -> {
                    String name = singular(normalize(c.name()));
                    return targetSingular.length() >= 3 && (name.contains(targetSingular)
                            || targetSingular.contains(name));
                },
                c -> targetSingular.length() >= 5
                        && distance(singular(normalize(c.name())), targetSingular) <= 2);
        for (var level : levels) {
            Optional<Category> best = categories.stream().filter(level)
                    .max(Comparator.comparingInt(Category::productCount));
            if (best.isPresent()) {
                return best;
            }
        }
        return Optional.empty();
    }

    /** Up to {@code limit} category names, the largest first: what the model can offer when nothing matched. */
    static List<String> suggestions(List<Category> categories, int limit) {
        return categories.stream().sorted(Comparator.comparingInt(Category::productCount).reversed())
                .map(Category::name).distinct().limit(limit).toList();
    }

    /** Lower case, letters and digits only, "and" left out, single spaces: "Fruits and Veg." → "fruits veg". */
    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\band\\b", " ").replaceAll(" {2,}", " ").strip();
    }

    /** A rough singular of the last word: "fruits" → "fruit", "tomatoes" → "tomato", "berries" → "berry". */
    static String singular(String text) {
        if (text.endsWith("ies") && text.length() > 4) {
            return text.substring(0, text.length() - 3) + "y";
        }
        if (text.endsWith("oes") && text.length() > 4) {
            return text.substring(0, text.length() - 2);
        }
        if (text.endsWith("s") && !text.endsWith("ss") && text.length() > 3) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** Levenshtein distance (insert, delete, replace), for short names only. */
    static int distance(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 2) {
            return Integer.MAX_VALUE;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int replace = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(replace, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
