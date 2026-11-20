# Grocery catalog seed data

The local development catalog: **30 categories and 224 products**. Categories CAT001–CAT020 and their 166 products
come from the business's category and product list (`grocery_categories_and_products.pdf`). CAT021–CAT030 were
added on 2026-11-19 with 58 products: eggs, meat and seafood; dry fruits and nuts; leafy greens and herbs; pickles
and chutney powders; sauces, spreads and honey; noodles, pasta and ready to cook; salt, sugar and jaggery; millets;
pooja needs; pet care. Their photos are credited in the last section of `CREDITS.md`. The Chennai stores
(`../chennai/`) sell these products too. Load the catalog into the running app with:

```bash
node infrastructure/local/scripts/seed-catalog.mjs --dry-run   # checks the files and the photos, sends nothing
node infrastructure/local/scripts/seed-catalog.mjs
```

Then search for "Apple" or "Tomato", or pick a category under **Shop by category** on the home page.

## The files

`categories.csv`: one row per category.

| Column | Meaning |
|---|---|
| `code` | The category's code from the list (`CAT001`). Products point to their category with it |
| `name` | Shown to shoppers. Top-level category names must be unique |
| `sort_order` | Position in **Shop by category** (lower first) |
| `image` | Photo under `frontend/grocery-web/public/images/`. Each category shows one of its products (`products/apple.jpg`) |
| `description` | Short description |

`products.csv`: one row per product.

| Column | Meaning |
|---|---|
| `category_code` | The category's `code` from `categories.csv` |
| `code` | The product's code from the list (`PRD00101`). It is saved as the product's **SKU** |
| `name` | Shown to shoppers and searched |
| `unit_value`, `unit` | Pack size: `1`,`KG` is shown as "1 kg". Units: `G`, `KG`, `ML`, `L`, `PCS`, `PACK` |
| `mrp`, `selling_price` | Rupees. `selling_price` must be between 1 and `mrp` |
| `stock` | Opening stock in packs |
| `food_type` | `VEG`, `NON_VEG`, `EGG`, or empty for non-food products |
| `image` | Photo under `frontend/grocery-web/public/images/` (`products/apple.jpg`) |
| `description` | Short description. Other names help search (`Brinjal (baingan, eggplant)`) |

Values that contain a comma go in double quotes: `"Atta, Flour and Sooji"`.

## Where the data ends up

The script talks to the API gateway like an admin and a store would, so each service stores its own part:

| Data | Saved by | Table |
|---|---|---|
| Category name, description, photo URL, sort order | catalog-command-service (admin) | `catalog_db.category` |
| Product name, category, pack size, SKU (= product code), food type, description | catalog-command-service (store) | `catalog_db.product` |
| Product photo URL | catalog-command-service (store) | `catalog_db.product_image` |
| MRP and selling price | pricing-service (store) | `pricing_db.product_price`, `price_history` |
| Opening stock | inventory-service (store) | `inventory_db.stock_item`, `stock_movement` |
| Search (name, category, photo, price, stock) | catalog-query-service, from the events | Elasticsearch `products` |

The products are sold by the store **FreshCart Store**. The script registers it as `dev.store@grocery.local` with
password `Dev@12345`, completes its onboarding and approves it as the admin. Sign in with that account to manage the
products, prices and stock in the app.

The photos are stored as absolute URLs (`http://localhost:6420/images/products/apple.jpg`), because the catalog only
accepts http(s) image URLs. Set `IMAGE_BASE_URL` when the frontend runs elsewhere.

## Changing the catalog

- **Add a product:**
  1. Add a row to `products.csv` with a new `code`.
  2. Put its photo in `frontend/grocery-web/public/images/products/` and credit it in `CREDITS.md` there.
  3. Run the script again.
- **Change a product:** edit its row and run the script again. The product is found again by its `code` (SKU) and
  updated. A price change of more than 20% waits for an admin's approval (Admin → Pricing), as for any store.
- **Stock:** the script only sets the opening stock of products with nothing on hand. Stock that changed since then
  (orders, counts) is left alone.
- **Remove a product:** delete it in the app (store → Products). Removing the row only stops the script from updating
  it.
