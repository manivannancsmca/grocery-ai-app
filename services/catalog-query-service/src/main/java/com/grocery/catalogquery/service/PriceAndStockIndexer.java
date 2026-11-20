package com.grocery.catalogquery.service;

import com.grocery.catalogquery.document.ProductDocument;
import com.grocery.catalogquery.document.ProductDocument.PricePart;
import com.grocery.catalogquery.document.ProductDocument.StockPart;
import com.grocery.catalogquery.event.EventPayloads.PriceChanged;
import com.grocery.catalogquery.event.EventPayloads.ProductAvailabilityChanged;
import com.grocery.catalogquery.index.IndexDefinition;
import com.grocery.catalogquery.index.SearchDocumentWriter;
import com.grocery.common.kafka.InvalidEventException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * pricing.events and inventory.events → the price and stock parts of the {@code products} documents.
 *
 * <p>Each part has its own version ({@code priceVersion}, {@code stockVersion}) and is written only when the event
 * is newer than what the document holds, so replays, duplicates and out-of-order events are harmless and a price
 * event can never undo a stock event or a catalog snapshot (or the other way round). An event for a product that
 * has no document yet creates a placeholder with just that part; it is not {@code visible}, so nobody sees it
 * until the catalog snapshot arrives and completes it.
 */
@Service
public class PriceAndStockIndexer {

    static final String DEFAULT_CURRENCY = "INR";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SearchDocumentWriter writer;

    public PriceAndStockIndexer(SearchDocumentWriter writer) {
        this.writer = writer;
    }

    public void onPriceChanged(PriceChanged price) {
        if (blank(price.productId()) || price.priceVersion() == null
                || price.mrp() == null || price.sellingPrice() == null) {
            throw new InvalidEventException("PriceChanged without productId, priceVersion, mrp or sellingPrice");
        }
        if (price.sellingPrice().signum() <= 0 || price.mrp().signum() <= 0) {
            throw new InvalidEventException("PriceChanged for " + price.productId() + " has a non-positive price");
        }
        PricePart part = new PricePart(
                price.productId(),
                price.mrp(),
                price.sellingPrice(),
                blank(price.currency()) ? DEFAULT_CURRENCY : price.currency(),
                discountPercent(price.mrp(), price.sellingPrice()),
                price.priceVersion());
        writer.merge(IndexDefinition.PRODUCTS, price.productId(), ProductDocument.PRICE_VERSION,
                price.priceVersion(), ProductDocument.PRICE_FIELDS, part);
    }

    public void onAvailabilityChanged(ProductAvailabilityChanged stock) {
        if (blank(stock.productId()) || stock.stockVersion() == null || stock.inStock() == null) {
            throw new InvalidEventException("ProductAvailabilityChanged without productId, stockVersion or inStock");
        }
        StockPart part = new StockPart(stock.productId(), stock.inStock(), stock.stockVersion());
        writer.merge(IndexDefinition.PRODUCTS, stock.productId(), ProductDocument.STOCK_VERSION,
                stock.stockVersion(), ProductDocument.STOCK_FIELDS, part);
    }

    /** Whole percent off the MRP, rounded half up, 0–99 (0 when the selling price is not below the MRP). */
    static int discountPercent(BigDecimal mrp, BigDecimal sellingPrice) {
        if (mrp.signum() <= 0 || sellingPrice.compareTo(mrp) >= 0) {
            return 0;
        }
        int percent = mrp.subtract(sellingPrice).multiply(HUNDRED)
                .divide(mrp, 0, RoundingMode.HALF_UP).intValue();
        return Math.max(0, Math.min(99, percent));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
