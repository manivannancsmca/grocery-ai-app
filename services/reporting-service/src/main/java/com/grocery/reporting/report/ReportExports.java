package com.grocery.reporting.report;

import com.grocery.common.export.ExportTable;
import com.grocery.reporting.report.ReportDtos.CouponRow;
import com.grocery.reporting.report.ReportDtos.DeliveryDay;
import com.grocery.reporting.report.ReportDtos.Overview;
import com.grocery.reporting.report.ReportDtos.ProductRow;
import com.grocery.reporting.report.ReportDtos.PurchasingRow;
import com.grocery.reporting.report.ReportDtos.RatedTarget;
import com.grocery.reporting.report.ReportDtos.Ratings;
import com.grocery.reporting.report.ReportDtos.SalesDay;
import com.grocery.reporting.report.ReportDtos.StoreRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Excel and PDF form of each admin report ({@code ?format=xlsx|pdf}, written by grocery-common's exporter): the
 * same rows as the JSON, with headings a person reads ("Gross sales (INR)") and real numbers and dates, the words of
 * the Reports page ("Dish", not MENU_ITEM). The {@code csv} format keeps its own columns (the JSON field names,
 * contract §7: {@link ReportCsv}). Nothing here is personal data: stores, items, coupons and totals.
 */
public final class ReportExports {

    private static final Map<String, String> ITEM_TYPES = Map.of("PRODUCT", "Product", "MENU_ITEM", "Dish");
    private static final Map<String, String> RATED = Map.of("PRODUCT", "Product", "MENU_ITEM", "Dish",
            "VENDOR", "Grocery store", "RESTAURANT", "Restaurant", "DELIVERY_PARTNER", "Delivery partner");

    private ReportExports() {
    }

    // ---------------------------------------------------------------- overview

    /** One key figure of the overview: they are listed downwards, one per row (a single 20-column row prints badly). */
    public record Figure(String figure, BigDecimal value, String unit) {
    }

    public static final ExportTable<Figure> OVERVIEW = ExportTable.<Figure>named("report-overview", "Overview")
            .text("Figure", Figure::figure)
            .decimal("Value", Figure::value)
            .text("Unit", Figure::unit)
            .build();

    /** The overview's figures in the order of the Reports page, then the counts behind them. */
    public static List<Figure> figures(Overview o) {
        List<Figure> figures = new ArrayList<>();
        figures.add(new Figure("Gross sales (GMV)", o.grossSales(), "INR"));
        figures.add(new Figure("Net sales (GMV minus refunds)", o.netSales(), "INR"));
        figures.add(new Figure("Orders (confirmed)", count(o.orders()), "orders"));
        figures.add(new Figure("Average order value", o.averageOrderValue(), "INR"));
        figures.add(new Figure("Cancellation rate (cancelled or rejected)", percent(o.cancellationRate()), "%"));
        figures.add(new Figure("Refunds", o.refunds(), "INR"));
        figures.add(new Figure("Coupon discounts", o.couponDiscounts().total(), "INR"));
        figures.add(new Figure("Coupon discounts on items", o.couponDiscounts().items(), "INR"));
        figures.add(new Figure("Coupon discounts on delivery fees", o.couponDiscounts().deliveryFee(), "INR"));
        // orders placed before the funding was recorded are in neither, so these two can add up to less
        figures.add(new Figure("Coupon discounts funded by the platform", o.couponDiscounts().platform(), "INR"));
        figures.add(new Figure("Coupon discounts funded by stores", o.couponDiscounts().store(), "INR"));
        figures.add(new Figure("New customers (first order in this period)", count(o.newCustomers()), "customers"));
        figures.add(new Figure("Orders placed", count(o.placedOrders()), "orders"));
        figures.add(new Figure("Orders cancelled", count(o.cancelledOrders()), "orders"));
        figures.add(new Figure("Orders rejected", count(o.rejectedOrders()), "orders"));
        figures.add(new Figure("Orders with a coupon", count(o.couponOrders()), "orders"));
        figures.add(new Figure("Refunds made", count(o.refundCount()), "refunds"));
        figures.add(new Figure("Returns approved", count(o.returnsApproved()), "returns"));
        figures.add(new Figure("Returned amount", o.returnedAmount(), "INR"));
        return figures;
    }

    private static BigDecimal count(long value) {
        return BigDecimal.valueOf(value);
    }

    /** 0.3333 → 33.33 */
    private static BigDecimal percent(BigDecimal fraction) {
        return fraction == null ? null : fraction.movePointRight(2).setScale(2, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------- the lists

    public static final ExportTable<SalesDay> DAILY_SALES = ExportTable.<SalesDay>named("report-daily-sales",
                    "Daily sales")
            .date("Day", SalesDay::date)
            .integer("Orders", SalesDay::orders)
            .money("Gross sales (INR)", SalesDay::gmv)
            .money("Discounts (INR)", SalesDay::discounts)
            .money("Refunds (INR)", SalesDay::refunds)
            .money("Net sales (INR)", SalesDay::net)
            .build();

    public static final ExportTable<StoreRow> TOP_STORES = ExportTable.<StoreRow>named("report-top-stores",
                    "Top stores")
            .integer("Rank", StoreRow::rank)
            .text("Store", s -> nameOr(s.storeName(), s.storeId()))
            .label("Store type", StoreRow::storeType)
            .integer("Orders", StoreRow::orders)
            .money("Gross sales (INR)", StoreRow::gmv)
            .money("Average order value (INR)", StoreRow::averageOrderValue)
            .text("Store ID", StoreRow::storeId)
            .build();

    public static final ExportTable<ProductRow> TOP_PRODUCTS = ExportTable.<ProductRow>named("report-top-products",
                    "Top products and dishes")
            .integer("Rank", ProductRow::rank)
            .text("Product or dish", ProductRow::name)
            .text("Type", p -> ITEM_TYPES.getOrDefault(p.itemType(), p.itemType()))
            .text("Store", p -> nameOr(p.storeName(), p.storeId()))
            .integer("Units sold", ProductRow::units)
            .money("Revenue (INR)", ProductRow::revenue)
            .integer("Orders", ProductRow::orders)
            .integer("Units returned", ProductRow::returnedUnits)
            .text("Item ID", ProductRow::itemId)
            .text("Store ID", ProductRow::storeId)
            .build();

    public static final ExportTable<DeliveryDay> DELIVERY_PERFORMANCE = ExportTable.<DeliveryDay>named(
                    "report-delivery-performance", "Delivery performance")
            .date("Day", DeliveryDay::date)
            .integer("Deliveries", DeliveryDay::deliveries)
            .decimal("Avg. minutes to assign a partner", DeliveryDay::avgConfirmToAssignedMinutes)
            .decimal("Avg. minutes to pick up", DeliveryDay::avgAssignedToPickedUpMinutes)
            .decimal("Avg. minutes to deliver", DeliveryDay::avgPickedUpToDeliveredMinutes)
            .integer("Failed", DeliveryDay::failed)
            .build();

    public static final ExportTable<CouponRow> PROMOTIONS = ExportTable.<CouponRow>named("report-promotions",
                    "Promotions")
            .text("Coupon code", CouponRow::couponCode)
            .text("Coupon name", CouponRow::couponName)
            .label("Funded by", CouponRow::fundedBy)
            .integer("Orders", CouponRow::orders)
            .money("Discount given (INR)", CouponRow::discountGiven)
            .money("On items (INR)", CouponRow::discount)
            .money("On delivery fees (INR)", CouponRow::deliveryFeeDiscount)
            .money("Gross sales of these orders (INR)", CouponRow::gmv)
            .integer("Stores", CouponRow::stores)
            .build();

    /** One line of the ratings report: the lowest rated first, then the highest rated. */
    public record RatedLine(String list, RatedTarget target) {
    }

    public static final ExportTable<RatedLine> RATINGS = ExportTable.<RatedLine>named("report-ratings", "Ratings")
            .text("List", RatedLine::list)
            .integer("Rank", l -> l.target().rank())
            .text("Rated", l -> RATED.getOrDefault(l.target().targetType(), l.target().targetType()))
            .text("Name", l -> nameOr(l.target().targetName(), l.target().targetId()))
            .decimal("Average rating", l -> l.target().averageRating())
            .integer("Ratings", l -> l.target().ratingCount())
            .integer("1 star", l -> stars(l, 1))
            .integer("2 stars", l -> stars(l, 2))
            .integer("3 stars", l -> stars(l, 3))
            .integer("4 stars", l -> stars(l, 4))
            .integer("5 stars", l -> stars(l, 5))
            .dateTime("Last rated", l -> l.target().updatedAt())
            .text("ID", l -> l.target().targetId())
            .build();

    public static List<RatedLine> ratedLines(Ratings report) {
        List<RatedLine> lines = new ArrayList<>();
        report.lowest().forEach(t -> lines.add(new RatedLine("Lowest rated", t)));
        report.highest().forEach(t -> lines.add(new RatedLine("Highest rated", t)));
        return lines;
    }

    private static Integer stars(RatedLine line, int stars) {
        List<Integer> distribution = line.target().distribution();
        return distribution == null || distribution.size() < stars ? null : distribution.get(stars - 1);
    }

    public static final ExportTable<PurchasingRow> PURCHASING = ExportTable.<PurchasingRow>named("report-purchasing",
                    "Purchasing")
            .date("Day", PurchasingRow::date)
            .text("Vendor", r -> nameOr(r.vendorName(), r.vendorId()))
            .integer("Units received", PurchasingRow::units)
            .money("Cost (INR)", PurchasingRow::cost)
            .integer("Goods receipts", PurchasingRow::receipts)
            .integer("Purchase orders", PurchasingRow::purchaseOrders)
            .text("Vendor ID", PurchasingRow::vendorId)
            .build();

    /** The name when one was seen in the events, else the id (as the Reports page shows it). */
    private static Object nameOr(String name, Object id) {
        return name == null || name.isBlank() ? Objects.toString(id, null) : name;
    }
}
