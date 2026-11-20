package com.grocery.reporting.report;

import com.grocery.reporting.report.ReportDtos.CouponRow;
import com.grocery.reporting.report.ReportDtos.DeliveryDay;
import com.grocery.reporting.report.ReportDtos.Overview;
import com.grocery.reporting.report.ReportDtos.ProductRow;
import com.grocery.reporting.report.ReportDtos.PurchasingRow;
import com.grocery.reporting.report.ReportDtos.RatedTarget;
import com.grocery.reporting.report.ReportDtos.Ratings;
import com.grocery.reporting.report.ReportDtos.SalesDay;
import com.grocery.reporting.report.ReportDtos.StoreRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The {@code ?format=csv} form of each report: the column names are the JSON field names (nested ones dotted:
 * {@code couponDiscounts.platform}), one row per array element (per day, store, product, coupon, vendor-day); the
 * overview is one row; ratings list {@code lowest} then {@code highest} with a {@code list} column.
 */
public final class ReportCsv {

    private ReportCsv() {
    }

    public static String overview(Overview o) {
        return Csv.write(
                List.of("from", "to", "timezone", "currency", "grossSales", "netSales", "orders", "averageOrderValue",
                        "cancellationRate", "refunds", "couponDiscounts.platform", "couponDiscounts.store",
                        "couponDiscounts.total", "couponDiscounts.items", "couponDiscounts.deliveryFee",
                        "newCustomers", "placedOrders", "cancelledOrders", "rejectedOrders", "refundCount",
                        "couponOrders", "returnsApproved", "returnedAmount"),
                List.of(Arrays.asList(o.from(), o.to(), o.timezone(), o.currency(), o.grossSales(), o.netSales(),
                        o.orders(), o.averageOrderValue(), o.cancellationRate(), o.refunds(),
                        o.couponDiscounts().platform(), o.couponDiscounts().store(), o.couponDiscounts().total(),
                        o.couponDiscounts().items(), o.couponDiscounts().deliveryFee(), o.newCustomers(),
                        o.placedOrders(), o.cancelledOrders(), o.rejectedOrders(), o.refundCount(), o.couponOrders(),
                        o.returnsApproved(), o.returnedAmount())));
    }

    public static String dailySales(List<SalesDay> days) {
        return Csv.write(List.of("date", "orders", "gmv", "discounts", "refunds", "net"),
                days.stream()
                        .map(d -> List.<Object>of(d.date(), d.orders(), d.gmv(), d.discounts(), d.refunds(), d.net()))
                        .toList());
    }

    public static String topStores(List<StoreRow> stores) {
        return Csv.write(List.of("rank", "storeId", "storeName", "storeType", "orders", "gmv", "averageOrderValue"),
                stores.stream()
                        .map(s -> Arrays.<Object>asList(s.rank(), s.storeId(), s.storeName(), s.storeType(),
                                s.orders(), s.gmv(), s.averageOrderValue()))
                        .toList());
    }

    public static String topProducts(List<ProductRow> products) {
        return Csv.write(List.of("rank", "itemId", "itemType", "name", "storeId", "storeName", "units", "revenue",
                        "orders", "returnedUnits"),
                products.stream()
                        .map(p -> Arrays.<Object>asList(p.rank(), p.itemId(), p.itemType(), p.name(), p.storeId(),
                                p.storeName(), p.units(), p.revenue(), p.orders(), p.returnedUnits()))
                        .toList());
    }

    public static String deliveryPerformance(List<DeliveryDay> days) {
        return Csv.write(List.of("date", "deliveries", "avgConfirmToAssignedMinutes", "avgAssignedToPickedUpMinutes",
                        "avgPickedUpToDeliveredMinutes", "failed"),
                days.stream()
                        .map(d -> Arrays.<Object>asList(d.date(), d.deliveries(), d.avgConfirmToAssignedMinutes(),
                                d.avgAssignedToPickedUpMinutes(), d.avgPickedUpToDeliveredMinutes(), d.failed()))
                        .toList());
    }

    public static String promotions(List<CouponRow> coupons) {
        return Csv.write(List.of("couponCode", "couponName", "orders", "discountGiven", "gmv", "fundedBy", "discount",
                        "deliveryFeeDiscount", "stores"),
                coupons.stream()
                        .map(c -> Arrays.<Object>asList(c.couponCode(), c.couponName(), c.orders(), c.discountGiven(),
                                c.gmv(), c.fundedBy(), c.discount(), c.deliveryFeeDiscount(), c.stores()))
                        .toList());
    }

    public static String ratings(Ratings report) {
        List<List<Object>> rows = new ArrayList<>();
        report.lowest().forEach(t -> rows.add(target("lowest", t)));
        report.highest().forEach(t -> rows.add(target("highest", t)));
        return Csv.write(List.of("list", "rank", "targetType", "targetId", "targetName", "averageRating",
                "ratingCount", "stars1", "stars2", "stars3", "stars4", "stars5", "updatedAt"), rows);
    }

    private static List<Object> target(String list, RatedTarget t) {
        List<Integer> d = t.distribution();
        return Arrays.asList(list, t.rank(), t.targetType(), t.targetId(), t.targetName(), t.averageRating(),
                t.ratingCount(), d.get(0), d.get(1), d.get(2), d.get(3), d.get(4), t.updatedAt());
    }

    public static String purchasing(List<PurchasingRow> rows) {
        return Csv.write(List.of("vendorId", "vendorName", "date", "units", "cost", "receipts", "purchaseOrders"),
                rows.stream()
                        .map(r -> Arrays.<Object>asList(r.vendorId(), r.vendorName(), r.date(), r.units(), r.cost(),
                                r.receipts(), r.purchaseOrders()))
                        .toList());
    }
}
