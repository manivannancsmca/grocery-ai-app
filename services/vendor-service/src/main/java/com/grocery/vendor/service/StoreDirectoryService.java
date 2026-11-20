package com.grocery.vendor.service;

import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.NotFoundException;
import com.grocery.vendor.config.StoreDirectoryProperties;
import com.grocery.vendor.dto.StoreDirectoryDtos.NearbyStores;
import com.grocery.vendor.dto.StoreDirectoryDtos.StoreCard;
import com.grocery.vendor.entity.BusinessAddress;
import com.grocery.vendor.entity.Vendor;
import com.grocery.vendor.entity.VendorStatus;
import com.grocery.vendor.repository.VendorRepository;
import com.grocery.vendor.service.StoreDistance.BoundingBox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The public store directory (docs/api/phase-3-catalog-and-search.md §9): APPROVED grocery stores, which of them deliver
 * to a point, and one store's public card. Read-only; never shows anything but the {@link StoreCard} fields.
 */
@Service
public class StoreDirectoryService {

    /** Nearest first; equal distances by name, then id. */
    private static final Comparator<StoreCard> NEAREST_FIRST = Comparator
            .comparing(StoreCard::distanceKm)
            .thenComparing(StoreCard::name, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(card -> card.id().toString());

    private final VendorRepository vendors;
    private final StoreDirectoryProperties properties;

    public StoreDirectoryService(VendorRepository vendors, StoreDirectoryProperties properties) {
        this.vendors = vendors;
        this.properties = properties;
    }

    /**
     * The approved stores whose road distance to the point is within the delivery limit, nearest first, at most
     * {@code max-results}. A bounding box in the query keeps the candidates few; the exact distance decides.
     *
     * @throws BadRequestException INVALID_PARAMETER when {@code lat} / {@code lng} is missing or out of range
     */
    @Transactional(readOnly = true)
    public NearbyStores nearby(Double lat, Double lng) {
        if (lat == null || lng == null) {
            throw new BadRequestException("INVALID_PARAMETER", "lat and lng are required");
        }
        requireInRange(lat, lng);
        BoundingBox box = StoreDistance.searchBox(lat, lng, properties.deliveryLimitKm(), properties.roadFactor());
        List<StoreCard> stores = vendors.findInBox(VendorStatus.APPROVED, box.minLatitude(), box.maxLatitude(),
                        box.minLongitude(), box.maxLongitude()).stream()
                .map(vendor -> card(vendor, lat, lng))
                .filter(card -> Boolean.TRUE.equals(card.deliversHere()))
                .sorted(NEAREST_FIRST)
                .limit(properties.maxResults())
                .toList();
        return new NearbyStores(properties.deliveryLimitKm().doubleValue(), stores);
    }

    /**
     * One approved store. With a point ({@code lat} and {@code lng} together) the card carries the distance and whether
     * the store delivers there; without one both are null.
     *
     * @throws BadRequestException INVALID_PARAMETER when only one of {@code lat} / {@code lng} is given or one is out
     *                             of range
     * @throws NotFoundException   STORE_NOT_FOUND for an unknown id or a vendor that is not APPROVED
     */
    @Transactional(readOnly = true)
    public StoreCard store(UUID id, Double lat, Double lng) {
        if ((lat == null) != (lng == null)) {
            throw new BadRequestException("INVALID_PARAMETER", "lat and lng must be given together");
        }
        if (lat != null) {
            requireInRange(lat, lng);
        }
        Vendor vendor = vendors.findById(id)
                .filter(v -> v.getStatus() == VendorStatus.APPROVED)
                .orElseThrow(() -> new NotFoundException("STORE_NOT_FOUND", "Store " + id + " was not found"));
        return card(vendor, lat, lng);
    }

    /**
     * The public card; with a point, the distance and {@code deliversHere}. A store without coordinates (none can be
     * approved without, but older rows might) has no distance and does not deliver anywhere.
     */
    private StoreCard card(Vendor vendor, Double lat, Double lng) {
        BusinessAddress address = vendor.getAddress();
        Double storeLat = address == null ? null : address.getLatitude();
        Double storeLng = address == null ? null : address.getLongitude();
        BigDecimal distanceKm = null;
        Boolean deliversHere = null;
        if (lat != null && lng != null) {
            if (storeLat != null && storeLng != null) {
                distanceKm = StoreDistance.roadKm(storeLat, storeLng, lat, lng, properties.roadFactor());
                deliversHere = distanceKm.compareTo(properties.deliveryLimitKm()) <= 0;
            } else {
                deliversHere = false;
            }
        }
        return new StoreCard(vendor.getId(), vendor.getBusinessName(),
                address == null ? null : address.getLine1(),
                address == null ? null : address.getLine2(),
                address == null ? null : address.getCity(),
                address == null ? null : address.getPostalCode(),
                storeLat, storeLng, distanceKm, deliversHere);
    }

    /** -90 ≤ lat ≤ 90 and -180 ≤ lng ≤ 180 (NaN fails both), else 400 INVALID_PARAMETER. */
    private static void requireInRange(double lat, double lng) {
        if (!(lat >= -90 && lat <= 90)) {
            throw new BadRequestException("INVALID_PARAMETER", "lat must be between -90 and 90");
        }
        if (!(lng >= -180 && lng <= 180)) {
            throw new BadRequestException("INVALID_PARAMETER", "lng must be between -180 and 180");
        }
    }
}
