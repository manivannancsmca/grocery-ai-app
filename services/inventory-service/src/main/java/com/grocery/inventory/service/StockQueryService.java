package com.grocery.inventory.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.inventory.dto.InventoryDtos.StockMovementView;
import com.grocery.inventory.dto.InventoryDtos.StockSummary;
import com.grocery.inventory.dto.InventoryDtos.StockView;
import com.grocery.inventory.dto.StockExports;
import com.grocery.inventory.dto.StockMapper;
import com.grocery.inventory.entity.ProductRef;
import com.grocery.inventory.entity.ProductStatus;
import com.grocery.inventory.entity.StockItem;
import com.grocery.inventory.repository.ProductRefRepository;
import com.grocery.inventory.repository.StockItemRepository;
import com.grocery.inventory.repository.StockMovementRepository;
import com.grocery.inventory.repository.StockRow;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The vendor's stock lists, summary, single stock view and movement history. Lists and the summary leave
 * archived products out; a single archived product can still be read (read-only, {@code productStatus ARCHIVED}).
 * The list's export reads in its own short transactions (see {@link ExportQueries}), so it opts out of the
 * class-level one.
 */
@Service
@Transactional(readOnly = true)
public class StockQueryService {

    /** {@code filter} values: LOW_STOCK = threshold &gt; 0 and available ≤ threshold (includes out of stock). */
    public enum StockFilter {
        LOW_STOCK, OUT_OF_STOCK, IN_STOCK
    }

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
    private static final char LIKE_ESCAPE = '!';

    private final StockItemRepository stockItems;
    private final ProductRefRepository productRefs;
    private final StockMovementRepository movements;
    private final ExportQueries exports;

    public StockQueryService(StockItemRepository stockItems, ProductRefRepository productRefs,
                             StockMovementRepository movements, ExportQueries exports) {
        this.stockItems = stockItems;
        this.productRefs = productRefs;
        this.movements = movements;
        this.exports = exports;
    }

    /** Sorted by product name; {@code q} = name contains (case-insensitive) or SKU equals. */
    public PageResponse<StockView> list(CurrentUser user, String q, String filter, Integer page, Integer size) {
        UUID vendorId = user.requireVendorId();
        VisibleFilter visible = VisibleFilter.of(q, filter);
        return Pages.toResponse(
                stockItems.findVisible(vendorId, ProductStatus.ARCHIVED, visible.term(), visible.namePattern(),
                        visible.filter(), Pages.request(page, size, Sort.unsorted())),
                (StockRow row) -> StockMapper.toView(row.stock(), row.product()));
    }

    /**
     * {@code GET /api/vendor/inventory/export}: the list's search and filter on every page, by product name, in keyset
     * batches (no transaction here: each batch has its own).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RowSource<StockView> listExport(CurrentUser user, String q, String filter) {
        UUID vendorId = user.requireVendorId();
        VisibleFilter visible = VisibleFilter.of(q, filter);
        return exports.jpql(StockRow.class, StockItemRepository.VISIBLE_ROW, StockItemRepository.VISIBLE)
                .countOf("s")
                .param("vendorId", vendorId)
                .param("archived", ProductStatus.ARCHIVED)
                .param("q", visible.term())
                .param("namePattern", visible.namePattern())
                .param("filter", visible.filter())
                .orderBy(StockExports.SORT.resolve(null))
                .rows(row -> StockMapper.toView(row.stock(), row.product()));
    }

    public StockSummary summary(CurrentUser user) {
        UUID vendorId = user.requireVendorId();
        List<Object[]> rows = stockItems.summarize(vendorId, ProductStatus.ARCHIVED);
        if (rows.isEmpty()) {
            return new StockSummary(0, 0, 0, 0);
        }
        Object[] row = rows.getFirst();
        return new StockSummary(count(row[0]), count(row[1]), count(row[2]), count(row[3]));
    }

    public StockView get(CurrentUser user, UUID productId) {
        UUID vendorId = user.requireVendorId();
        StockItem stock = owned(productId, vendorId);
        ProductRef product = productRefs.findById(productId).orElseThrow(InventoryException::productNotFound);
        return StockMapper.toView(stock, product);
    }

    /** Newest first. */
    public PageResponse<StockMovementView> movements(CurrentUser user, UUID productId, Integer page, Integer size) {
        UUID vendorId = user.requireVendorId();
        owned(productId, vendorId);
        return Pages.toResponse(movements.findByProductId(productId, Pages.request(page, size, NEWEST_FIRST)),
                StockMapper::toView);
    }

    private StockItem owned(UUID productId, UUID vendorId) {
        return stockItems.findById(productId)
                .filter(s -> s.getVendorId().equals(vendorId))
                .orElseThrow(InventoryException::productNotFound);
    }

    private static long count(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /**
     * The list's {@code q} and {@code filter} as {@link StockItemRepository#VISIBLE} binds them (shared by the list and
     * its export): the trimmed term, its escaped lower-case {@code like} pattern, the filter's name.
     */
    private record VisibleFilter(String term, String namePattern, String filter) {

        static VisibleFilter of(String q, String filter) {
            StockFilter stockFilter = BadRequestException.parseEnum(StockFilter.class, "filter", filter);
            String term = q == null || q.isBlank() ? null : q.trim();
            String namePattern = term == null ? null : "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%";
            return new VisibleFilter(term, namePattern, stockFilter == null ? null : stockFilter.name());
        }
    }
}
