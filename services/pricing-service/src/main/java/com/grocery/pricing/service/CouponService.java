package com.grocery.pricing.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.pricing.dto.CouponDtos.CouponOffer;
import com.grocery.pricing.dto.CouponDtos.CouponRequest;
import com.grocery.pricing.dto.CouponDtos.CouponUsage;
import com.grocery.pricing.dto.CouponDtos.CouponView;
import com.grocery.pricing.dto.CouponDtos.RedemptionView;
import com.grocery.pricing.dto.CouponExports;
import com.grocery.pricing.entity.Coupon;
import com.grocery.pricing.entity.CouponRedemption;
import com.grocery.pricing.entity.CouponScope;
import com.grocery.pricing.entity.CouponStoreType;
import com.grocery.pricing.entity.CouponTerms;
import com.grocery.pricing.entity.DiscountType;
import com.grocery.pricing.entity.OrderType;
import com.grocery.pricing.entity.RedemptionStatus;
import com.grocery.pricing.repository.CouponRedemptionRepository;
import com.grocery.pricing.repository.CouponRepository;
import com.grocery.pricing.repository.ProductRefRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Coupon management (contract §2): admins manage every coupon and choose the scope; vendors and restaurant owners
 * manage the STORE coupons of their own store (another store's coupon is reported as not found); customers list the
 * public offers of a store. Usage figures come from {@code coupon_redemption}: {@code usedCount} = RESERVED +
 * CONFIRMED (what the limits count), {@code discountGiven} = Σ discount + deliveryFeeDiscount of CONFIRMED uses.
 */
@Service
public class CouponService {

    /** {@code status} filter of the coupon lists (the derived {@code CouponView.status}). */
    public enum StatusFilter {
        ACTIVE,
        PAUSED,
        SCHEDULED,
        EXPIRED
    }

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt")
            .and(Sort.by(Sort.Direction.DESC, "id"));
    private static final Sort NEWEST_REDEMPTION_FIRST = Sort.by(Sort.Direction.DESC, "reservedAt")
            .and(Sort.by(Sort.Direction.DESC, "id"));
    private static final BigDecimal MIN_PERCENT = new BigDecimal("1");
    private static final BigDecimal MAX_PERCENT = new BigDecimal("90");
    private static final char LIKE_ESCAPE = '!';

    private final CouponRepository coupons;
    private final CouponRedemptionRepository redemptions;
    private final ProductRefRepository products;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final ExportQueries exports;
    private final Clock clock;

    public CouponService(CouponRepository coupons, CouponRedemptionRepository redemptions,
                         ProductRefRepository products, EntityManager entityManager,
                         PlatformTransactionManager transactionManager, ExportQueries exports, Clock clock) {
        this.coupons = coupons;
        this.redemptions = redemptions;
        this.products = products;
        this.entityManager = entityManager;
        this.transaction = new TransactionTemplate(transactionManager);
        this.exports = exports;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- queries

    /**
     * Newest first. Admins may filter by {@code scope} and {@code storeId}; a store owner sees only their store's
     * coupons. {@code q} = code starts with / name contains (case-insensitive).
     */
    @Transactional(readOnly = true)
    public PageResponse<CouponView> list(CouponOwner owner, String scope, UUID storeId, String status, String q,
                                         Integer page, Integer size) {
        Instant now = clock.instant();
        Page<Coupon> found = coupons.findAll(listFilter(owner, scope, storeId, status, q, now),
                Pages.request(page, size, NEWEST_FIRST));
        return Pages.toResponse(found, views(found.getContent(), now));
    }

    /**
     * {@code GET …/coupons/export}: the list's filters on every page, newest first or by {@code sort}, in keyset
     * batches; the usage figures and store names are looked up once per batch, as the list does per page. No
     * transaction here: each batch has its own.
     */
    public RowSource<CouponView> listExport(CouponOwner owner, String scope, UUID storeId, String status, String q,
                                            String sort) {
        Instant now = clock.instant();
        return exports.specification(coupons, listFilter(owner, scope, storeId, status, q, now))
                .orderBy(CouponExports.SORT.resolve(sort))
                .batches(batch -> batch.stream().map(views(batch, now)).toList());
    }

    /** The list's filters, parsed (shared by the list and its export); the derived status as of {@code now}. */
    private static Specification<Coupon> listFilter(CouponOwner owner, String scope, UUID storeId, String status,
                                                    String q, Instant now) {
        CouponScope scopeFilter = BadRequestException.parseEnum(CouponScope.class, "scope", scope);
        StatusFilter statusFilter = BadRequestException.parseEnum(StatusFilter.class, "status", status);
        return filter(owner, scopeFilter, storeId, statusFilter, q, now);
    }

    /** The views of one page (or export batch) of coupons: usage figures and store names in one query each. */
    private Function<Coupon, CouponView> views(List<Coupon> found, Instant now) {
        Map<UUID, Stats> stats = stats(found.stream().map(Coupon::getId).toList());
        Map<UUID, String> storeNames = storeNames(found);
        return coupon -> view(coupon, stats.getOrDefault(coupon.getId(), Stats.NONE),
                coupon.getStoreId() == null ? null : storeNames.get(coupon.getStoreId()), now);
    }

    @Transactional(readOnly = true)
    public CouponView get(CouponOwner owner, UUID id) {
        return view(manageable(owner, id), clock.instant());
    }

    /** Counts per status, amounts given and the redemptions (newest first, optional {@code status} filter). */
    @Transactional(readOnly = true)
    public CouponUsage usage(CouponOwner owner, UUID id, String status, Integer page, Integer size) {
        RedemptionStatus statusFilter = BadRequestException.parseEnum(RedemptionStatus.class, "status", status);
        Coupon coupon = manageable(owner, id);
        Stats stats = stats(List.of(id)).getOrDefault(id, Stats.NONE);
        Page<CouponRedemption> found = statusFilter == null
                ? redemptions.findByCouponId(id, Pages.request(page, size, NEWEST_REDEMPTION_FIRST))
                : redemptions.findByCouponIdAndStatus(id, statusFilter,
                Pages.request(page, size, NEWEST_REDEMPTION_FIRST));
        return CouponUsage.of(id, coupon.getCode(), stats.used(), stats.reserved(), stats.confirmed(),
                stats.released(), stats.discount(), stats.deliveryFeeDiscount(),
                Pages.toResponse(found, CouponService::redemptionView));
    }

    /**
     * {@code GET /api/coupons?storeId=}: the active, public coupons valid now that this customer can still use at
     * the store — PLATFORM coupons of its store type (or ANY) and the store's own STORE coupons — without those whose
     * total limit or the customer's own limit is reached. The store type is {@code storeType} when given, else
     * GROCERY for a vendor known from the catalog and RESTAURANT otherwise; without {@code storeId}: every PLATFORM
     * offer.
     */
    @Transactional(readOnly = true)
    public List<CouponOffer> offers(UUID customerId, UUID storeId, String storeType) {
        OrderType type = BadRequestException.parseEnum(OrderType.class, "storeType", storeType);
        Collection<CouponStoreType> storeTypes;
        if (storeId == null) {
            storeTypes = EnumSet.allOf(CouponStoreType.class);
        } else {
            if (type == null) {
                type = products.existsByVendorId(storeId) ? OrderType.GROCERY : OrderType.RESTAURANT;
            }
            storeTypes = List.of(CouponStoreType.ANY, CouponStoreType.of(type));
        }
        Instant now = clock.instant();
        List<Coupon> found = coupons.findOffers(now, CouponScope.PLATFORM, storeTypes, CouponScope.STORE, storeId);
        if (found.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = found.stream().map(Coupon::getId).toList();
        Map<UUID, Stats> stats = stats(ids);
        Map<UUID, Long> mine = new HashMap<>();
        for (Object[] row : redemptions.countActiveByCustomer(ids, customerId, RedemptionStatus.RELEASED)) {
            mine.put((UUID) row[0], (Long) row[1]);
        }
        return found.stream()
                .filter(coupon -> CouponRules.limitReason(coupon.terms(),
                        stats.getOrDefault(coupon.getId(), Stats.NONE).used(),
                        mine.getOrDefault(coupon.getId(), 0L)) == null)
                .map(coupon -> {
                    CouponTerms terms = coupon.terms();
                    return new CouponOffer(terms.code(), terms.name(), terms.description(),
                            terms.discountType().name(), terms.value(), terms.maxDiscount(),
                            terms.minOrderSubtotal(), terms.validUntil());
                })
                .toList();
    }

    // ---------------------------------------------------------------- commands

    /** 409 COUPON_CODE_TAKEN when the (upper-case) code exists. */
    public CouponView create(CouponOwner owner, CouponRequest request, CurrentUser user) {
        Instant now = clock.instant();
        CouponTerms terms = terms(owner, request, null, now);
        try {
            return transaction.execute(status -> {
                if (coupons.existsByCode(terms.code())) {
                    throw CouponException.codeTaken(terms.code());
                }
                Coupon coupon = Coupon.create(Ids.newId(), terms, user.userId(), now);
                entityManager.persist(coupon);
                coupons.flush();
                log.info("Coupon {} ({} {}) created by {}", coupon.getCode(), terms.scope(),
                        terms.storeId() == null ? terms.storeType() : terms.storeId(), user.userId());
                return view(coupon, now);
            });
        } catch (DataIntegrityViolationException e) {
            throw codeTakenOr(terms.code(), e);
        }
    }

    /** Replaces the coupon's terms (see {@link CouponRequest} for omitted fields). */
    public CouponView update(CouponOwner owner, UUID id, CouponRequest request, CurrentUser user) {
        Instant now = clock.instant();
        String code = CouponRules.normalizeCode(request.code());
        try {
            return transaction.execute(status -> {
                Coupon coupon = manageable(owner, id);
                CouponTerms terms = terms(owner, request, coupon.terms(), now);
                if (coupons.existsByCodeAndIdNot(terms.code(), id)) {
                    throw CouponException.codeTaken(terms.code());
                }
                coupon.update(terms, user.userId(), now);
                coupons.flush();
                log.info("Coupon {} ({}) updated by {}", coupon.getCode(), id, user.userId());
                return view(coupon, now);
            });
        } catch (DataIntegrityViolationException e) {
            throw codeTakenOr(code, e);
        }
    }

    /** Pause ({@code false}) / resume ({@code true}); idempotent. */
    public CouponView setActive(CouponOwner owner, UUID id, boolean active, CurrentUser user) {
        Instant now = clock.instant();
        return transaction.execute(status -> {
            Coupon coupon = manageable(owner, id);
            if (coupon.setActive(active, user.userId(), now)) {
                coupons.flush();
                log.info("Coupon {} ({}) {} by {}", coupon.getCode(), id, active ? "resumed" : "paused", user.userId());
            }
            return view(coupon, now);
        });
    }

    // ---------------------------------------------------------------- terms

    /**
     * Validates and normalises a request into terms. {@code existing} = the coupon's current terms on PUT (their
     * values are kept for omitted defaulted fields), null on POST.
     */
    CouponTerms terms(CouponOwner owner, CouponRequest request, CouponTerms existing, Instant now) {
        List<Map<String, String>> errors = new ArrayList<>();
        CouponScope scope;
        CouponStoreType storeType;
        UUID storeId;
        if (owner.isAdmin()) {
            scope = request.scope() != null ? request.scope()
                    : existing != null ? existing.scope() : CouponScope.PLATFORM;
            boolean sameScope = existing != null && existing.scope() == scope;
            if (scope == CouponScope.PLATFORM) {
                if (request.storeId() != null) {
                    errors.add(error("storeId", "must be empty for a PLATFORM coupon"));
                }
                storeId = null;
                storeType = request.storeType() != null ? request.storeType()
                        : sameScope ? existing.storeType() : CouponStoreType.ANY;
            } else {
                storeId = request.storeId() != null ? request.storeId() : sameScope ? existing.storeId() : null;
                if (storeId == null) {
                    errors.add(error("storeId", "is required for a STORE coupon"));
                }
                if (request.storeType() != null) {
                    storeType = request.storeType();
                } else if (sameScope && existing.storeId().equals(storeId)) {
                    storeType = existing.storeType();
                } else {
                    // not given: a vendor known from the catalog is a grocery store, anything else a restaurant
                    storeType = storeId != null && products.existsByVendorId(storeId)
                            ? CouponStoreType.GROCERY : CouponStoreType.RESTAURANT;
                }
                if (storeType == CouponStoreType.ANY) {
                    errors.add(error("storeType", "must be GROCERY or RESTAURANT for a STORE coupon"));
                }
            }
        } else {
            if ((request.scope() != null && request.scope() != CouponScope.STORE)
                    || (request.storeId() != null && !request.storeId().equals(owner.storeId()))
                    || (request.storeType() != null && request.storeType() != owner.storeType())) {
                throw CouponException.scopeNotAllowed();
            }
            scope = CouponScope.STORE;
            storeId = owner.storeId();
            storeType = owner.storeType();
        }

        DiscountType discountType = request.discountType();
        BigDecimal value = null;
        BigDecimal maxDiscount = null;
        switch (discountType) {
            case PERCENT -> {
                if (request.value() == null) {
                    errors.add(error("value", "is required for PERCENT"));
                } else if (request.value().compareTo(MIN_PERCENT) < 0 || request.value().compareTo(MAX_PERCENT) > 0) {
                    errors.add(error("value", "must be between 1 and 90 for PERCENT"));
                }
                value = CouponRules.money(request.value());
                maxDiscount = CouponRules.money(request.maxDiscount());
            }
            case FLAT -> {
                if (request.value() == null) {
                    errors.add(error("value", "is required for FLAT"));
                }
                value = CouponRules.money(request.value());
            }
            case FREE_DELIVERY -> {
                // no value, no cap: the delivery fee is waived
            }
        }

        BigDecimal minOrderSubtotal = request.minOrderSubtotal() != null ? CouponRules.money(request.minOrderSubtotal())
                : existing != null ? existing.minOrderSubtotal() : CouponRules.ZERO;
        Instant validFrom = request.validFrom() != null ? micros(request.validFrom())
                : existing != null ? existing.validFrom() : micros(now);
        Instant validUntil = micros(request.validUntil());
        if (!validUntil.isAfter(validFrom)) {
            errors.add(error("validUntil", "must be after validFrom"));
        }
        int perCustomerLimit = request.perCustomerLimit() != null ? request.perCustomerLimit()
                : existing != null ? existing.perCustomerLimit() : 1;
        boolean active = request.active() != null ? request.active() : existing == null || existing.active();
        boolean publicOffer = request.publicOffer() != null ? request.publicOffer()
                : existing != null && existing.publicOffer();
        if (!errors.isEmpty()) {
            throw CouponException.invalid(errors);
        }
        return new CouponTerms(CouponRules.normalizeCode(request.code()), request.name().strip(),
                blankToNull(request.description()), scope, storeType, storeId, discountType, value, maxDiscount,
                minOrderSubtotal, validFrom, validUntil, request.totalUsageLimit(), perCustomerLimit, active,
                publicOffer);
    }

    // ---------------------------------------------------------------- helpers

    private Coupon manageable(CouponOwner owner, UUID id) {
        return coupons.findById(id).filter(owner::canManage).orElseThrow(CouponException::notFound);
    }

    private RuntimeException codeTakenOr(String code, DataIntegrityViolationException e) {
        // a concurrent create / rename took the code between the check and the insert (unique uk_coupon_code)
        return coupons.existsByCode(code) ? CouponException.codeTaken(code) : e;
    }

    /** Usage statistics per coupon (coupons without redemptions are absent). */
    private Map<UUID, Stats> stats(Collection<UUID> couponIds) {
        Map<UUID, Stats> result = new HashMap<>();
        if (couponIds.isEmpty()) {
            return result;
        }
        for (Object[] row : redemptions.usageStats(couponIds)) {
            UUID couponId = (UUID) row[0];
            RedemptionStatus status = (RedemptionStatus) row[1];
            long count = (Long) row[2];
            Stats stats = result.getOrDefault(couponId, Stats.NONE);
            result.put(couponId, stats.plus(status, count, (BigDecimal) row[3], (BigDecimal) row[4]));
        }
        return result;
    }

    /** One coupon's view with its usage figures and store name. */
    private CouponView view(Coupon coupon, Instant now) {
        String storeName = coupon.getStoreId() == null ? null : storeNames(List.of(coupon)).get(coupon.getStoreId());
        return view(coupon, stats(List.of(coupon.getId())).getOrDefault(coupon.getId(), Stats.NONE), storeName, now);
    }

    /**
     * The names of the STORE coupons' stores that are known: vendors from the catalog projection ({@code product_ref});
     * restaurants are not known to this service (null).
     */
    private Map<UUID, String> storeNames(Collection<Coupon> found) {
        List<UUID> vendorIds = found.stream()
                .filter(c -> c.getScope() == CouponScope.STORE && c.terms().storeType() == CouponStoreType.GROCERY)
                .map(Coupon::getStoreId).distinct().toList();
        Map<UUID, String> names = new HashMap<>();
        if (!vendorIds.isEmpty()) {
            for (Object[] row : products.findVendorNames(vendorIds)) {
                names.put((UUID) row[0], (String) row[1]);
            }
        }
        return names;
    }

    static CouponView view(Coupon coupon, Stats stats, String storeName, Instant now) {
        CouponTerms terms = coupon.terms();
        return new CouponView(coupon.getId(), terms.code(), terms.name(), terms.description(), terms.scope().name(),
                terms.storeType().name(), terms.storeId(), storeName, terms.scope().name(),
                terms.discountType().name(), terms.value(), terms.maxDiscount(), terms.minOrderSubtotal(),
                terms.validFrom(), terms.validUntil(), terms.totalUsageLimit(), terms.perCustomerLimit(),
                terms.active(), terms.publicOffer(), CouponRules.status(terms, now), stats.used(), stats.given(),
                coupon.getCreatedBy(), coupon.getCreatedAt(), coupon.getUpdatedBy(), coupon.getUpdatedAt());
    }

    static RedemptionView redemptionView(CouponRedemption r) {
        return new RedemptionView(r.getId(), r.getOrderId(), r.getOrderNumber(), r.getCustomerId(), r.getStoreId(),
                r.getOrderType().name(), r.getSubtotal(), r.getDeliveryFee(), r.getDiscount(),
                r.getDeliveryFeeDiscount(), r.getStatus().name(), r.getReservedAt(), r.getReservedAt(),
                r.getConfirmedAt(), r.getReleasedAt(),
                r.getReleaseReason() == null ? null : r.getReleaseReason().name());
    }

    /** Admin filters, or the store owner's own STORE coupons; {@code status} as derived by {@link CouponRules#status}. */
    static Specification<Coupon> filter(CouponOwner owner, CouponScope scope, UUID storeId, StatusFilter status,
                                        String q, Instant now) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!owner.isAdmin()) {
                predicates.add(cb.equal(root.get("scope"), CouponScope.STORE));
                predicates.add(cb.equal(root.get("storeId"), owner.storeId()));
            } else {
                if (scope != null) {
                    predicates.add(cb.equal(root.get("scope"), scope));
                }
                if (storeId != null) {
                    predicates.add(cb.equal(root.get("storeId"), storeId));
                }
            }
            if (status != null) {
                Predicate active = cb.isTrue(root.get("active"));
                predicates.add(switch (status) {
                    case PAUSED -> cb.isFalse(root.get("active"));
                    case SCHEDULED -> cb.and(active, cb.greaterThan(root.<Instant>get("validFrom"), now));
                    case EXPIRED -> cb.and(active, cb.lessThanOrEqualTo(root.<Instant>get("validUntil"), now));
                    case ACTIVE -> cb.and(active, cb.lessThanOrEqualTo(root.<Instant>get("validFrom"), now),
                            cb.greaterThan(root.<Instant>get("validUntil"), now));
                });
            }
            if (q != null && !q.isBlank()) {
                String term = q.strip();
                predicates.add(cb.or(
                        cb.like(root.get("code"), escapeLike(term.toUpperCase(Locale.ROOT)) + "%", LIKE_ESCAPE),
                        cb.like(cb.lower(root.get("name")), "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%",
                                LIKE_ESCAPE)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Map<String, String> error(String field, String message) {
        return Map.of("field", field, "message", message);
    }

    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);     // DATETIME(6)
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** Redemption counts of one coupon; amounts are those of CONFIRMED redemptions. */
    record Stats(long reserved, long confirmed, long released, BigDecimal discount, BigDecimal deliveryFeeDiscount) {

        static final Stats NONE = new Stats(0, 0, 0, CouponRules.ZERO, CouponRules.ZERO);

        long used() {
            return reserved + confirmed;
        }

        BigDecimal given() {
            return CouponRules.money(discount.add(deliveryFeeDiscount));
        }

        Stats plus(RedemptionStatus status, long count, BigDecimal sumDiscount, BigDecimal sumDeliveryFeeDiscount) {
            return switch (status) {
                case RESERVED -> new Stats(reserved + count, confirmed, released, discount, deliveryFeeDiscount);
                case CONFIRMED -> new Stats(reserved, confirmed + count, released,
                        CouponRules.money(discount.add(orZero(sumDiscount))),
                        CouponRules.money(deliveryFeeDiscount.add(orZero(sumDeliveryFeeDiscount))));
                case RELEASED -> new Stats(reserved, confirmed, released + count, discount, deliveryFeeDiscount);
            };
        }

        private static BigDecimal orZero(BigDecimal amount) {
            return amount == null ? CouponRules.ZERO : amount;
        }
    }
}
