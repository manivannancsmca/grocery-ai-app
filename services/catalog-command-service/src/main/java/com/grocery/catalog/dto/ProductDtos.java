package com.grocery.catalog.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Vendor/admin product API bodies (docs/api/phase-3-catalog-and-search.md §3, §4). Optional text fields
 * may also be sent as "" (treated as absent), which is what HTML forms usually send.
 */
public final class ProductDtos {

    public static final String UNIT = "^(G|KG|ML|L|PCS|PACK)$";
    public static final String FOOD_TYPE = "^$|^(VEG|NON_VEG|EGG)$";
    public static final String SKU = "^$|^[A-Za-z0-9._-]{1,40}$";
    public static final String BARCODE = "^$|^[0-9]{8,14}$";
    /** Checked case-insensitively; the server lower-cases tags. */
    public static final String TAG = "^[a-z0-9 -]{2,30}$";
    public static final String HTTP_URL = "^https?://[^\\s/?#]+[^\\s]*$";

    private ProductDtos() {
    }

    public record AttributeDto(
            @NotBlank @Size(max = 50) String name,
            @NotBlank @Size(max = 200) String value) {
    }

    public record ProductRequest(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 2000) String description,
            @NotNull UUID categoryId,
            @Size(max = 80) String brandName,
            @NotNull
            @DecimalMin(value = "0", inclusive = false)
            @DecimalMax("100000")
            @Digits(integer = 6, fraction = 3, message = "must have at most 3 decimals")
            BigDecimal unitValue,
            @NotNull @Pattern(regexp = UNIT, message = "must be one of G, KG, ML, L, PCS, PACK") String unitOfMeasure,
            @Pattern(regexp = SKU, message = "must be 1-40 letters, digits, '.', '_' or '-'") String sku,
            @Pattern(regexp = BARCODE, message = "must be 8-14 digits") String barcode,
            @Pattern(regexp = FOOD_TYPE, message = "must be one of VEG, NON_VEG, EGG") String foodType,
            @Size(max = 10)
            List<@NotNull @Pattern(regexp = TAG, flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = "must be 2-30 letters, digits, spaces or '-'") String> tags,
            @Size(max = 20) List<@NotNull @Valid AttributeDto> attributes,
            @Size(max = 8)
            List<@NotNull @Size(max = 500) @Pattern(regexp = HTTP_URL, flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = "must be an http(s) URL") String> imageUrls) {
    }

    public record BrandView(UUID id, String name) {
    }

    /** The product's category with its path (names, root → leaf) and effective activity. */
    public record CategoryRef(UUID id, String name, List<String> path, boolean active) {
    }

    public record ProductView(UUID id, UUID vendorId, String name, String description, CategoryRef category,
                              BrandView brand, BigDecimal unitValue, String unitOfMeasure, String unitLabel,
                              String sku, String barcode, String foodType, List<String> tags,
                              List<AttributeDto> attributes, List<String> imageUrls, String status,
                              Instant publishedAt, Instant createdAt, Instant updatedAt) {
    }

    /** ProductView + the vendor's name, for admins. */
    public record AdminProductView(@JsonUnwrapped ProductView product, String vendorName) {
    }

    /** POST /api/admin/catalog/republish response. */
    public record RepublishResult(int categories, int products) {
    }
}
