package com.grocery.catalog.mapper;

import com.grocery.catalog.entity.Brand;
import com.grocery.catalog.entity.Category;
import com.grocery.catalog.entity.Product;
import com.grocery.catalog.entity.UnitOfMeasure;
import com.grocery.catalog.entity.VendorRef;
import com.grocery.catalog.event.CatalogEvents.Attribute;
import com.grocery.catalog.event.CatalogEvents.CategoryChanged;
import com.grocery.catalog.event.CatalogEvents.IdName;
import com.grocery.catalog.event.CatalogEvents.ProductUpserted;
import com.grocery.catalog.service.CategoryTree;

import java.util.List;

/** Entity state → event payloads (contract §8). Pure functions; versions are taken as they are. */
public final class SnapshotMapper {

    private SnapshotMapper() {
    }

    public static ProductUpserted toSnapshot(Product p, CategoryTree tree, Brand brand, VendorRef vendor) {
        List<Category> path = tree.path(p.getCategoryId());
        Category leaf = path.isEmpty() ? null : path.getLast();
        return new ProductUpserted(
                p.getId(),
                p.getVendorId(),
                VendorRef.nameOf(vendor),
                VendorRef.isActive(vendor),
                p.getName(),
                p.getDescription(),
                brand == null ? null : new IdName(brand.getId(), brand.getName()),
                leaf == null ? null : idName(leaf),
                path.stream().map(SnapshotMapper::idName).toList(),
                leaf != null && tree.effectiveActive(leaf.getId()),
                UnitOfMeasure.plain(p.getUnitValue()),
                p.getUnitOfMeasure().name(),
                p.unitLabel(),
                p.getSku(),
                p.getBarcode(),
                p.getFoodType() == null ? null : p.getFoodType().name(),
                p.getTags(),
                p.getAttributes().stream().map(a -> new Attribute(a.getName(), a.getValue())).toList(),
                p.getImageUrls(),
                p.getStatus().name(),
                p.getPublishedAt(),
                p.getUpdatedAt(),
                p.getProductVersion());
    }

    public static CategoryChanged toSnapshot(Category c, CategoryTree tree, boolean deleted) {
        return new CategoryChanged(
                c.getId(),
                c.getParentId(),
                c.getName(),
                c.getDescription(),
                c.getImageUrl(),
                c.getSortOrder(),
                c.getDepth(),
                tree.path(c.getId()).stream().map(SnapshotMapper::idName).toList(),
                c.isActive(),
                tree.effectiveActive(c.getId()),
                deleted,
                c.getCategoryVersion());
    }

    private static IdName idName(Category c) {
        return new IdName(c.getId(), c.getName());
    }
}
