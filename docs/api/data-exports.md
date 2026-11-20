# Data exports (CSV, Excel, PDF) — API contract

Every list page of every stakeholder has an **Export** button: CSV, Excel (`.xlsx`) or PDF of **every** row that
matches the page's filters (and in its order), not only the page on screen. The services build the file from the same
query and the same access rules as the list; nothing is filtered in the browser. Conventions as in
[Phase 2](phase-2-identity-and-stakeholders.md#1-conventions); paths go through the API gateway.

## 1. The request

```
GET <list path>/export?format=csv|xlsx|pdf&<the list's own filters>[&sort=<column>,asc|desc]
Authorization: Bearer <token>
```

- **Same endpoint family as the list**: same controller, same `@PreAuthorize`, same filter names and parsing. A
  store's, customer's or partner's id always comes from the token, never from a parameter.
- **`format`** (required): `csv`, `xlsx` (or `excel`), `pdf`.
- **`sort`** (optional): a column the export whitelists (below). Without it: the list's own order. Keys must be columns
  that are never empty, and the unique id always breaks ties.
- No `page` / `size`: the export holds every matching row.

## 2. The answer

`200` with the file, streamed (chunked) as it is read:

| Header | Value |
|---|---|
| `Content-Type` | `text/csv;charset=UTF-8`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` or `application/pdf` |
| `Content-Disposition` | `attachment; filename="orders-2026-11-15-1030.csv"` (export name + India time) |
| `X-Export-Rows` | the number of rows the file will have |
| `Cache-Control` | `no-store` |

- **CSV**: UTF-8 with a byte-order mark (Excel reads ₹ and non-Latin names), RFC 4180 quoting, CRLF. Text starting with
  `= + - @` gets a leading `'` so a spreadsheet never runs it as a formula (phone numbers show as `'+91…`).
- **Excel**: like the PDF, each sheet opens with the title (row 1) and a line with the row count, who exported it, when
  and the filters (row 2). The **column headers are on row 4, highlighted: white bold text on the brand green**
  (`#065F46`), with filter buttons on every column; rows 1–4 stay in view while scrolling and the headers print on every
  page. The data starts on row 5, every other row lightly shaded. Numbers, money and dates are real cells (they sort
  and sum); text is never a formula. Past Excel's 1,048,576 rows a new sheet continues, with the same title and headers.
- **PDF**: A4 landscape, title, row count, who exported it and the filters on top, column headers (white on the brand
  green) on every page, page numbers. Standard Helvetica: characters outside Latin show as "?" (CSV and Excel keep
  them).
- Dates and times are **India time** (`yyyy-MM-dd HH:mm`); money has two decimals, the currency in the header
  (`Total (INR)`); status codes are words (`OUT_FOR_DELIVERY` → "Out for delivery").

## 3. Errors (ProblemDetail, before any byte of the file)

| Status | `errorCode` | When |
|---|---|---|
| 400 | `INVALID_PARAMETER` | unknown or missing `format`, a `sort` column not offered, a bad filter value |
| 401 / 403 | `UNAUTHENTICATED`, `FORBIDDEN`, `VENDOR_NOT_ACTIVE`… | exactly as the list |
| 422 | `EXPORT_TOO_LARGE` | more than 1,000,000 rows; a PDF of more than 5,000; a client-sorted export of more than 100,000 (`rows`, `limit` in the body) |
| 429 | `EXPORT_BUSY` | this user already runs 2 exports, or the service instance 3 (`retryAfterSeconds`) |
| 404 | `EXPORT_DISABLED` | `grocery.export.enabled=false` |

A failure **after** the file started (the database going away mid-way) cannot become an error answer: the service cuts
the connection, so the download fails visibly instead of saving a truncated file. The browser shows "The export stopped
before it finished, so nothing was saved."

## 4. Endpoints

`sort` lists the column keys the export accepts; they are the keys of the page's table columns.

### Admin (every admin role unless noted)
| Page | Endpoint | Filters | `sort` |
|---|---|---|---|
| Orders | `GET /api/admin/orders/export` | `status`, `type`, `customerId`, `storeId`, `q`, `from`, `to` | `order`, `total` |
| Returns | `GET /api/admin/returns/export` | `status`, `storeId` | `return`, `refund` |
| Deliveries | `GET /api/admin/deliveries/export` | `status`, `assignmentFailed`, `partnerId`, `q` | `order`, `total` |
| Delivery partners | `GET /api/admin/delivery-partners/export` | `status`, `query` | — (the list's order) |
| Vendors | `GET /api/admin/vendors/export` | `status`, `query` | — |
| Restaurants | `GET /api/admin/restaurants/export` | `status`, `query` | — |
| Customers | `GET /api/admin/customers/export` | `query` | `name`, `joined`, `updated` |
| Users (SUPER_ADMIN, OPS_ADMIN, SUPPORT) | `GET /api/admin/users/export` | `query`, `role`, `status` | `user`, `status`, `created` |
| Payments | `GET /api/admin/payments/export` | `status`, `refundStatus`, `orderId`, `from`, `to` | `order`, `amount`, `status`, `refunds` |
| Purchase orders | `GET /api/admin/purchase-orders/export` | `vendorId`, `status` | `number`, `created` |
| Catalog products | `GET /api/admin/products/export` | `vendorId`, `categoryId`, `status`, `q` | `product`, `status`, `updated` |
| Coupons | `GET /api/admin/coupons/export` | `scope`, `storeId`, `status`, `q` | `code`, `validity` |
| Price approvals | `GET /api/admin/price-change-requests/export` | `status`, `vendorId` | `current`, `requested`, `change`, `requestedAt` |
| Reviews | `GET /api/admin/reviews/export` | `status`, `targetType`, `q` | — |
| Email log | `GET /api/admin/notifications/emails/export` | `status`, `to` | `to`, `subject`, `status`, `attempts`, `when` |
| AI audit (tool calls) | `GET /api/admin/ai/tool-invocations/export` | `agent`, `userId`, `tool`, `status`, `from`, `to` | `when`, `user`, `status` |
| AI approvals | `GET /api/approvals/export` | `scope`, `status` | `requested`, `request`, `requester` |

**Reports** (`/admin/reports`, reporting-service): each section's report endpoint takes `format` itself, next to its
period (`from`, `to`) and its own filters — `GET /api/admin/reports/<report>?format=csv|xlsx|pdf&from=&to=&…` for
`overview`, `sales/daily`, `stores/top`, `products/top`, `delivery/performance`, `promotions`, `ratings`, `purchasing`
(no `format`, or `json`: the JSON report). `xlsx` and `pdf` are written as above, with headings in words and the period
and filters on top; the overview lists its figures downwards (Figure, Value, Unit). `csv` keeps the reports' own
contract ([Phase 12 §7](phase-12-post-mvp-features.md)): the JSON field names as headers, named
`<report>_<from>_<to>.csv`, without the byte-order mark or `X-Export-Rows`.

### Vendor (the store from the token)
| Page | Endpoint | Filters |
|---|---|---|
| Orders | `GET /api/vendor/orders/export` | `status` (none: every order, in every status) |
| Products | `GET /api/vendor/products/export` | `status`, `q` |
| Inventory | `GET /api/vendor/inventory/export` | `q`, `filter` |
| Pricing | `GET /api/vendor/prices/export` | `q`, `filter` |
| Price change requests | `GET /api/vendor/price-change-requests/export` | `status` |
| Purchase orders | `GET /api/vendor/purchase-orders/export` | `status`, `supplierId`, `number` |
| Suppliers | `GET /api/vendor/suppliers/export` | `active` |
| Coupons | `GET /api/vendor/coupons/export` | `status`, `q`; `sort`: `code`, `validity` |
| Returns (vendors and restaurants) | `GET /api/store/returns/export` | `status`; `sort`: `return`, `refund` |
| Reviews (vendors and restaurants) | `GET /api/store/reviews/export` | `targetType`, `rating` |

### Restaurant owner (the restaurant from the token)
| Page | Endpoint | Filters |
|---|---|---|
| Orders | `GET /api/restaurant/orders/export` | `status` |
| Coupons | `GET /api/restaurant/coupons/export` | `status`, `q`; `sort`: `code`, `validity` |
| Returns, reviews | the store endpoints above | |

### Customer (their own data)
| Page | Endpoint | Filters |
|---|---|---|
| My orders | `GET /api/orders/export` | `status` |
| My returns | `GET /api/returns/export` | — |
| My reviews | `GET /api/reviews/mine/export` | — |
| Assistant actions | `GET /api/approvals/export?scope=mine` | `status` |

### Delivery partner (their own deliveries)
| Page | Endpoint | Filters |
|---|---|---|
| Delivery history | `GET /api/delivery/history/export` | `status`, `from`, `to` |

## 5. What a file contains
- Only fields the stakeholder already sees on the list page — never more.
- **Personal data**: in admin exports a person's e-mail, phone and street address (and free text that may contain them:
  the AI tool-call arguments, e-mail delivery errors) stay in the file only for SUPER_ADMIN, OPS_ADMIN and SUPPORT;
  other admins get the same export without those columns. Store exports never carry a customer's e-mail or phone.
- **Never exported**: password hashes, tokens, secrets, payment signatures, card data, driving licence or vehicle
  registration numbers, bank details, e-mail bodies.

## 6. How the services keep up
- **Batches, not one big query**: 1,000 rows at a time, "the rows after the last one seen" (keyset), each batch in its
  own short read-only transaction; no database connection is held while the file goes to a slow client.
- **Limits per instance** (3 exports at once, 2 per user) keep exports from crowding out normal traffic.
- Nothing is stored on the server: there is no export file to protect, expire or clean up.
- Every export is logged (export, format, rows, duration, user id) and measured (`grocery_export_seconds`,
  `grocery_export_rows`).
- Settings: `grocery.export.*` (see [grocery-common](../../libs/grocery-common/README.md#changes-in-0100-data-exports);
  the Excel and PDF layout: [0.10.1](../../libs/grocery-common/README.md#changes-in-0101-the-look-of-excel-and-pdf-files)).

## 7. In the React app
`shared/export`: `ExportButton` (format menu with what the file will hold, the PDF limit, the table's sort passed
through when the server supports that column), `ExportProvider` (downloads keep going across pages, a progress card
with **Cancel** under the top bar, a toast with the file name and row count, or the server's reason when refused).
