package com.grocery.catalog.mapper;

import com.grocery.catalog.dto.ProductDtos.AdminProductView;
import com.grocery.catalog.dto.ProductDtos.AttributeDto;
import com.grocery.catalog.dto.ProductDtos.BrandView;
import com.grocery.catalog.dto.ProductDtos.CategoryRef;
import com.grocery.catalog.dto.ProductDtos.ProductRequest;
import com.grocery.catalog.dto.ProductDtos.ProductView;
import com.grocery.catalog.entity.Brand;
import com.grocery.catalog.entity.Category;
import com.grocery.catalog.entity.FoodType;
import com.grocery.catalog.entity.Product;
import com.grocery.catalog.entity.ProductAttribute;
import com.grocery.catalog.entity.UnitOfMeasure;
import com.grocery.catalog.entity.VendorRef;
import com.grocery.catalog.service.CategoryTree;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class ProductMapper {

    private ProductMapper() {
    }

    /** Validated request → normalised entity values (trimmed text, lower-cased unique tags). */
    public static Product.Details toDetails(ProductRequest r, Brand brand) {
        return new Product.Details(
                r.categoryId(),
                brand == null ? null : brand.getId(),
                r.name().trim(),
                trim(r.description()),
                r.unitValue(),
                UnitOfMeasure.valueOf(r.unitOfMeasure()),
                trim(r.sku()),
                trim(r.barcode()),
                trim(r.foodType()) == null ? null : FoodType.valueOf(r.foodType().trim()),
                normalizeTags(r.tags()),
                r.attributes() == null ? List.of() : r.attributes().stream()
                        .map(a -> new ProductAttribute(a.name().trim(), a.value().trim())).toList(),
                r.imageUrls() == null ? List.of() : r.imageUrls().stream().map(String::trim).toList());
    }

    /** Trimmed, inner whitespace collapsed, lower-cased, duplicates removed (first occurrence wins). */
    public static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String tag : tags) {
            String normalized = tag.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (normalized.length() >= 2) {
                unique.add(normalized);
            }
        }
        return List.copyOf(unique);
    }

    public static ProductView toView(Product p, CategoryTree tree, Brand brand) {
        return new ProductView(
                p.getId(),
                p.getVendorId(),
                p.getName(),
                p.getDescription(),
                categoryRef(p.getCategoryId(), tree),
                brand == null ? null : new BrandView(brand.getId(), brand.getName()),
                UnitOfMeasure.plain(p.getUnitValue()),
                p.getUnitOfMeasure().name(),
                p.unitLabel(),
                p.getSku(),
                p.getBarcode(),
                p.getFoodType() == null ? null : p.getFoodType().name(),
                p.getTags(),
                p.getAttributes().stream().map(a -> new AttributeDto(a.getName(), a.getValue())).toList(),
                p.getImageUrls(),
                p.getStatus().name(),
                p.getPublishedAt(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }

    public static AdminProductView toAdminView(Product p, CategoryTree tree, Brand brand, VendorRef vendor) {
        return new AdminProductView(toView(p, tree, brand), VendorRef.nameOf(vendor));
    }

    public static BrandView toView(Brand brand) {
        return new BrandView(brand.getId(), brand.getName());
    }

    /** Null only for an archived product whose category was deleted later. */
    private static CategoryRef categoryRef(UUID categoryId, CategoryTree tree) {
        List<Category> path = tree.path(categoryId);
        if (path.isEmpty()) {
            return null;
        }
        Category leaf = path.getLast();
        return new CategoryRef(leaf.getId(), leaf.getName(), path.stream().map(Category::getName).toList(),
                tree.effectiveActive(leaf.getId()));
    }

    public static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
