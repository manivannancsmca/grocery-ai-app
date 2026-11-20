package com.grocery.pricing.cache;

import com.grocery.pricing.entity.ProductPrice;
import com.grocery.pricing.entity.ProductRef;
import com.grocery.pricing.entity.ProductStatus;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One quote cache entry ({@code price:v1:<productId>}, JSON). {@code priceVersion} and {@code productVersion}
 * decide which of two entries is newer (see {@code price-put-if-newer.lua}); an {@code archived} entry is a
 * tombstone: the product is quoted as missing.
 */
public record CachedPrice(UUID productId, UUID vendorId, BigDecimal mrp, BigDecimal sellingPrice, String currency,
                          long priceVersion, long productVersion, boolean archived) {

    public static CachedPrice of(ProductPrice price, ProductRef product) {
        return of(price, product.getProductVersion(), product.getStatus());
    }

    public static CachedPrice of(ProductPrice price, long productVersion, ProductStatus status) {
        return new CachedPrice(price.getProductId(), price.getVendorId(), price.getMrp(), price.getSellingPrice(),
                price.getCurrency(), price.getPriceVersion(), productVersion, status == ProductStatus.ARCHIVED);
    }
}
