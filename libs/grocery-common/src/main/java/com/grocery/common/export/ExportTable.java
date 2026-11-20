package com.grocery.common.export;

import com.grocery.common.export.ExportColumn.Kind;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * What an export looks like: its file name, title and columns. Defined once per list (usually a constant next to the
 * list's view), so the export always has the same columns whatever the format:
 * <pre>{@code
 * static final ExportTable<OrderSummary> ORDERS = ExportTable.<OrderSummary>named("orders", "Orders")
 *         .text("Order number", OrderSummary::orderNumber)
 *         .text("Status", OrderSummary::status)
 *         .money("Total (INR)", OrderSummary::total)
 *         .dateTime("Placed", OrderSummary::createdAt)
 *         .build();
 * }</pre>
 * Only list here what the stakeholder may already see on the list page: an export never shows more than the page.
 * Never list secrets or identity documents (password hashes, tokens, licence / PAN / bank numbers). Mark a person's
 * contact details (e-mail, phone, street address) with {@link Builder#personal()} in admin exports: they stay in the
 * file only for {@link #PERSONAL_DATA_ROLES} (see {@link #visibleTo}).
 *
 * @param <R> the row type
 */
public final class ExportTable<R> {

    /** File names: lower-case words joined by dashes ("admin-orders"). The date and extension are added. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /**
     * The admin roles that may take people's contact details out of the system in bulk (support work, operations).
     * Other admins (finance, delivery management) get the same export without the {@link Builder#personal()} columns.
     */
    public static final Set<String> PERSONAL_DATA_ROLES = Set.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN, Roles.SUPPORT);

    private final String name;
    private final String title;
    private final List<ExportColumn<R>> columns;
    private volatile ExportTable<R> withoutPersonal;

    private ExportTable(String name, String title, List<ExportColumn<R>> columns) {
        this.name = name;
        this.title = title;
        this.columns = List.copyOf(columns);
    }

    /**
     * The table as {@code user} may export it: unchanged for {@link #PERSONAL_DATA_ROLES}, otherwise without the
     * columns marked {@link Builder#personal()}.
     */
    public ExportTable<R> visibleTo(CurrentUser user) {
        boolean anyPersonal = columns.stream().anyMatch(ExportColumn::personal);
        if (!anyPersonal || PERSONAL_DATA_ROLES.stream().anyMatch(user::hasRole)) {
            return this;
        }
        ExportTable<R> reduced = withoutPersonal;
        if (reduced == null) {
            reduced = new ExportTable<>(name, title, columns.stream().filter(column -> !column.personal()).toList());
            withoutPersonal = reduced;
        }
        return reduced;
    }

    /**
     * @param name  the file name's start, e.g. {@code "orders"} → {@code orders-2026-11-15-1030.csv}
     * @param title the heading of the PDF and the name of the Excel sheet, e.g. "Orders"
     */
    public static <R> Builder<R> named(String name, String title) {
        return new Builder<>(name, title);
    }

    public String name() {
        return name;
    }

    public String title() {
        return title;
    }

    public List<ExportColumn<R>> columns() {
        return columns;
    }

    /** One row's values, in column order. */
    public Object[] values(R row) {
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = columns.get(i).read(row);
        }
        return values;
    }

    public static final class Builder<R> {

        private final String name;
        private final String title;
        private final List<ExportColumn<R>> columns = new ArrayList<>();

        private Builder(String name, String title) {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Export names are lower-case words joined by dashes: " + name);
            }
            this.name = name;
            this.title = Objects.requireNonNull(title, "title");
        }

        public Builder<R> text(String header, Function<? super R, ?> value) {
            return column(header, Kind.TEXT, value);
        }

        /**
         * A code shown as words: {@code "OUT_FOR_DELIVERY"} → "Out for delivery" (for views that carry a status as a
         * string). Use {@link #text} for codes people type (coupon codes, SKUs): those stay as they are.
         */
        public Builder<R> label(String header, Function<? super R, ?> value) {
            return column(header, Kind.TEXT, row -> {
                Object code = value.apply(row);
                return code == null ? null : CellValues.humanize(code instanceof Enum<?> constant ? constant.name()
                        : code.toString());
            });
        }

        public Builder<R> integer(String header, Function<? super R, ? extends Number> value) {
            return column(header, Kind.INTEGER, value);
        }

        public Builder<R> decimal(String header, Function<? super R, ? extends Number> value) {
            return column(header, Kind.DECIMAL, value);
        }

        public Builder<R> money(String header, Function<? super R, ? extends Number> value) {
            return column(header, Kind.MONEY, value);
        }

        public Builder<R> date(String header, Function<? super R, ?> value) {
            return column(header, Kind.DATE, value);
        }

        public Builder<R> dateTime(String header, Function<? super R, ?> value) {
            return column(header, Kind.DATE_TIME, value);
        }

        public Builder<R> yesNo(String header, Function<? super R, Boolean> value) {
            return column(header, Kind.YES_NO, value);
        }

        public Builder<R> column(String header, Kind kind, Function<? super R, ?> value) {
            columns.add(new ExportColumn<>(header, kind, value));
            return this;
        }

        /** Marks the column just added as a person's contact detail (see {@link ExportTable#visibleTo}). */
        public Builder<R> personal() {
            if (columns.isEmpty()) {
                throw new IllegalStateException("personal() marks the column added just before it");
            }
            columns.add(columns.removeLast().asPersonal());
            return this;
        }

        public ExportTable<R> build() {
            if (columns.isEmpty()) {
                throw new IllegalStateException("An export needs at least one column");
            }
            return new ExportTable<>(name, title, columns);
        }
    }
}
