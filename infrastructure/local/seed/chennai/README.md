# Chennai test data

Test data for every stakeholder in Chennai, loaded by `infrastructure/local/scripts/seed-chennai.mjs`. It covers
grocery stores (vendors), restaurants, customers and delivery partners, every business **approved**, plus orders in
different delivery states. The accounts and how to test with them are listed in the main README, section
"Chennai test accounts".

```bash
node infrastructure/local/scripts/seed-chennai.mjs --check   # offline: the files, the photos, who delivers where
node infrastructure/local/scripts/seed-chennai.mjs           # loads it into the running app (safe to re-run)
```

| File | What |
|---|---|
| `chennai.json` | Restaurants with menus, stores with business details and the products they sell, customers with addresses, delivery partners with their area, and the order scenarios |
| `products.csv` | The Chennai master list: 30 branded grocery products in 10 groups, sold by the south-Chennai and OMR stores |

People and business names are made up. GSTIN and FSSAI numbers only have the right format. The streets and
coordinates are real Chennai places, checked against OpenStreetMap, so distances, delivery fees, partner dispatch and
live tracking behave realistically. E-mails use `example.com`, and phone numbers use the Indian mobile format
(`+91` and 10 digits).

## Areas

| Area | Stores | Restaurants | Customers | Delivery partners |
|---|---|---|---|---|
| Tambaram (West Tambaram, Selaiyur) | Tambaram Super Bazaar | Tambaram Udupi Bhavan | Meenakshi Sundaram | Prabhu Ramasamy |
| Porur (Mugalivakkam) | Porur Family Mart | Porur Ambur Biryani Centre | Harish Babu | Kathiravan Murugesan |
| Guindy (Ekkattuthangal) | Guindy Daily Fresh | – | Swathi Ramesh, Priya Raman (office) | Dinesh Kumar |
| OMR: Sholinganallur | OMR Fresh Basket | OMR Spice Route | Rahul Menon | Saravanan Muthu |
| OMR: Perungudi, Thoraipakkam | Thoraipakkam Super Store | Perungudi Kerala Kitchen | Nandhini Prakash, Aishwarya Iyer | Yasmin Banu |
| Medavakkam | Medavakkam Fresh & Daily | Medavakkam Parotta Point | Balamurugan Selvam | Arjun Prakash |
| Velachery (Vijaya Nagar, Sony Nagar, AGS Colony) | Velachery Green Grocers, **Vijaya Nagar Fresh Mart** (dev) | Velachery Dragon Wok, **Kaveri Meals & Tiffin** (dev) | Sanjay Venkatesh, **Kavya Sriram** (dev; also Taramani and Adyar) | Meena Devi, **Suresh Kannan** (dev) |
| Adyar (Thiruvanmiyur) | Adyar Organic Basket | Adyar Chettinad Kitchen | Divya Krishnan | Ravi Shankar |
| T. Nagar (West Mambalam, Kodambakkam) | T. Nagar Daily Needs | Pondy Bazaar Tiffin House | Lakshmi Narayanan, Priya Raman | Senthil Kumar |
| Anna Nagar (Kilpauk) | Anna Nagar Supermart | Anna Nagar Biryani Point | Deepak Raj, Arun Kumar | Gopal Iyer |
| Mylapore (Alwarpet, Royapettah), Nungambakkam, Besant Nagar | Mylapore Fresh Mart | Mylai Mess, Nungambakkam Grill House, Besant Nagar Beach Cafe | Karthik Subramanian, Fathima Begum | Mohammed Irfan, Vijay Anand |

`--check` prints, for every customer address, the nearest store and how many stores and restaurants deliver there.
It uses the cart's own rules: road distance is the straight line × 1.3, and stores deliver up to 15 km, restaurants up
to 12 km. It fails when a customer's default address has no store, an order could not be delivered, or a store has no
delivery partner within 10 km (the largest dispatch radius).

## The development accounts

The `development` block of `chennai.json` names one account of each stakeholder (marked "dev" above): a customer, a
grocery store, a restaurant and a delivery partner in Velachery. They are the sign-in screen's development accounts
(`dev.customer@grocery.local`, `dev.vendor@…`, `dev.restaurant@…`, `dev.delivery@…`), which identity-service creates
with the password `Dev@12345`: their entries carry that password (`"password"`), so the script signs in with it. They
are ordinary entries of the lists otherwise: `seed-chennai.mjs` fills in their profiles (the customer's name and phone
too), submits them and has the admin approve them like every other account. `--check` also makes sure they work together. Both businesses must
deliver to every one of the customer's addresses, and the partner must be within 3 km of both (the first dispatch
radius, so the first offer goes to them). The partner must be approved, the restaurant must take orders, and the store
must open with every product in stock. Vijaya Nagar Fresh Mart sells all 30 branded products with the file's stock
(the same as the Tambaram column below), plus the grocery catalog's products in the 17 categories the branded list does
not cover: 145 products, so the development customer has a store with all 30 categories close by.

`infrastructure/local/scripts/verify-workflow.mjs` then runs a grocery order, a restaurant order and a rejected order
through the four accounts, step by step, through the API gateway; the main README describes it.

## The branded master list (`products.csv`)

| Column | Meaning |
|---|---|
| `master_category` | The group in the master list (Fruits, Rice & Grains, …). Documentation only |
| `category_code` | The app category it is filed under: a code from `../catalog/categories.csv` |
| `code` | Product code (`CHN00101`), saved as the product's **SKU**; a re-run finds the product again by it |
| `name`, `brand` | Shown to shoppers. The brand is saved in the shared brand list and shows on the product card |
| `unit_value`, `unit` | Pack size: `G`, `KG`, `ML`, `L`, `PCS`, `PACK` |
| `mrp`, `selling_price` | Rupees. The master list gives one price, so both are the same |
| `stock` | Opening stock of a full-size store. A store's `stockScale` in `chennai.json` scales it, and its `outOfStock` list sets it to 0 |
| `food_type` | `VEG`, `EGG`, or empty for non-food products |
| `image` | Photo under `frontend/grocery-web/public/images/` (credits in `products/CREDITS.md` there) |
| `description` | Short description, with Tamil names where shoppers use them (`nattu thakkali`, `nallennai`) |

**Categories.** The master list's 10 groups are filed under the app's existing categories (the business's category
list in `../catalog/categories.csv`), so shoppers do not see near-duplicates such as "Cooking Oils" next to "Edible
Oils". Most groups map one to one. "Rice & Grains", "Snacks & Biscuits" and "Beverages" each span two app categories,
as the table shows.

**Approval.** Products have no approval step of their own: a product can be published only by an approved store. Every
product below is **published** by an **approved** store, and its price applies at once (only a later change of more
than 20% waits for an admin).

Stock per store (`–`: the store does not sell it). Alphonso Mango is out of season in November: Porur Family Mart lists
it with no stock, so the out-of-stock state can be tested.

| Category (master list) | App category | Code | Product | Brand | Pack | Price (₹) | Tambaram | Porur | Guindy | OMR | Thoraipakkam | Medavakkam |
|---|---|---|---|---|---|--:|--:|--:|--:|--:|--:|--:|
| Fruits | Fruits | CHN00101 | Robusta Banana | Local Fresh | 1 dozen (12 pcs) | 60 | 60 | 54 | 42 | 66 | – | 36 |
| Fruits | Fruits | CHN00102 | Shimla Apple | Fresh Farm | 1 kg | 180 | 45 | 41 | 31 | 50 | – | 27 |
| Fruits | Fruits | CHN00103 | Alphonso Mango | Fresh Farm | 1 kg | 220 | 25 | **0 (out of stock)** | 18 | 28 | – | 15 |
| Vegetables | Vegetables | CHN00201 | Tomato | Local Fresh | 1 kg | 40 | 120 | 108 | 84 | 132 | – | 72 |
| Vegetables | Vegetables | CHN00202 | Onion | Local Fresh | 1 kg | 50 | 150 | 135 | 105 | 165 | – | 90 |
| Vegetables | Vegetables | CHN00203 | Carrot | Ooty Fresh | 1 kg | 60 | 70 | 63 | 49 | 77 | – | 42 |
| Rice & Grains | Rice and Rice Products | CHN00301 | India Gate Ponni Boiled Rice | India Gate | 5 kg | 350 | 40 | 36 | 28 | 44 | 32 | 24 |
| Rice & Grains | Rice and Rice Products | CHN00302 | Daawat Basmati Rice | Daawat | 1 kg | 120 | 50 | 45 | 35 | 55 | 40 | 30 |
| Rice & Grains | Atta, Flour and Sooji | CHN00303 | 24 Mantra Ragi (Finger Millet) | 24 Mantra | 1 kg | 90 | 35 | 32 | 25 | 39 | 28 | 21 |
| Dairy & Eggs | Dairy Products | CHN00401 | Aavin Full Cream Milk | Aavin | 1 litre | 68 | 100 | 90 | 70 | 110 | 80 | 60 |
| Dairy & Eggs | Dairy Products | CHN00402 | Aavin Fresh Curd | Aavin | 500 g | 35 | 80 | 72 | 56 | 88 | 64 | 48 |
| Dairy & Eggs | Dairy Products | CHN00403 | Suguna Farm Eggs | Suguna | 6 pcs | 48 | 90 | 81 | 63 | 99 | 72 | 54 |
| Cooking Oils | Edible Oils | CHN00501 | Fortune Sunflower Oil | Fortune | 1 litre | 155 | 60 | 54 | 42 | 66 | 48 | – |
| Cooking Oils | Edible Oils | CHN00502 | Idhayam Gingelly Oil | Idhayam | 500 ml | 145 | 40 | 36 | 28 | 44 | 32 | – |
| Cooking Oils | Edible Oils | CHN00503 | Gold Winner Groundnut Oil | Gold Winner | 1 litre | 190 | 45 | 41 | 31 | 50 | 36 | – |
| Masala & Spices | Spices and Masalas | CHN00601 | Aachi Turmeric Powder | Aachi | 100 g | 35 | 80 | 72 | 56 | 88 | 64 | – |
| Masala & Spices | Spices and Masalas | CHN00602 | Sakthi Chilli Powder | Sakthi | 100 g | 40 | 80 | 72 | 56 | 88 | 64 | – |
| Masala & Spices | Spices and Masalas | CHN00603 | Aachi Sambar Powder | Aachi | 100 g | 45 | 75 | 68 | 53 | 83 | 60 | – |
| Snacks & Biscuits | Biscuits and Cookies | CHN00701 | Britannia Good Day Biscuits | Britannia | 200 g | 40 | 100 | 90 | 70 | 110 | 80 | – |
| Snacks & Biscuits | Biscuits and Cookies | CHN00702 | Britannia Marie Gold | Britannia | 200 g | 35 | 100 | 90 | 70 | 110 | 80 | – |
| Snacks & Biscuits | Snacks and Namkeen | CHN00703 | Lay's Classic Salted Potato Chips | Lay's | 52 g | 20 | 150 | 135 | 105 | 165 | 120 | – |
| Beverages | Tea and Coffee | CHN00801 | Lipton Green Tea Bags | Lipton | 25 tea bags | 160 | 40 | 36 | 28 | 44 | 32 | – |
| Beverages | Tea and Coffee | CHN00802 | Bru Coffee Powder | Bru | 100 g | 120 | 60 | 54 | 42 | 66 | 48 | – |
| Beverages | Beverages and Drinks | CHN00803 | Real Orange Juice | Real | 1 litre | 110 | 50 | 45 | 35 | 55 | 40 | – |
| Personal Care | Personal Care | CHN00901 | Dove Bathing Soap | Dove | 100 g | 55 | 80 | 72 | – | 88 | 64 | – |
| Personal Care | Personal Care | CHN00902 | Clinic Plus Shampoo | Clinic Plus | 180 ml | 120 | 50 | 45 | – | 55 | 40 | – |
| Personal Care | Personal Care | CHN00903 | Colgate Toothpaste | Colgate | 150 g | 95 | 90 | 81 | – | 99 | 72 | – |
| Household & Cleaning | Household Cleaning | CHN01001 | Vim Dishwash Liquid | Vim | 500 ml | 110 | 60 | 54 | – | 66 | 48 | – |
| Household & Cleaning | Household Cleaning | CHN01002 | Lizol Floor Cleaner | Lizol | 500 ml | 115 | 45 | 41 | – | 50 | 36 | – |
| Household & Cleaning | Household Cleaning | CHN01003 | Harpic Toilet Cleaner | Harpic | 500 ml | 105 | 55 | 50 | – | 61 | 44 | – |

The stores in the columns: Tambaram Super Bazaar, Porur Family Mart, Guindy Daily Fresh, OMR Fresh Basket
(Sholinganallur), Thoraipakkam Super Store and Medavakkam Fresh & Daily. The other five Chennai stores sell 12 products
each from the grocery catalog (`../catalog/products.csv`).

Every store also sells whole categories of the grocery catalog (added 2026-11-19). A store with branded products never
gets a category those already cover, so nobody shows near-duplicates.

| Store | Catalog categories added | Products |
|---|---|---|
| Vijaya Nagar Fresh Mart (dev) | Dals; Chocolates and Sweets; Breakfast; Bakery; Frozen; Baby Care; Household Essentials; all of CAT021–CAT030 | 145 |
| Velachery Green Grocers | Eggs, Meat and Seafood; Dry Fruits; Leafy Greens; Pickles; Salt, Sugar and Jaggery | 41 |
| Mylapore Fresh Mart | Pooja Needs; Pickles; Leafy Greens; Millets; Dals | 43 |
| T. Nagar Daily Needs | Sauces and Spreads; Noodles and Pasta; Chocolates and Sweets; Bakery; Pooja Needs | 46 |
| Adyar Organic Basket | Millets; Dry Fruits; Leafy Greens; Salt, Sugar and Jaggery; Breakfast | 42 |
| Anna Nagar Supermart | Pet Care; Sauces and Spreads; Noodles and Pasta; Frozen; Baby Care | 44 |
| Tambaram Super Bazaar | Dals; Eggs, Meat and Seafood; Salt, Sugar and Jaggery; Pooja Needs; Household Essentials | 66 |
| Porur Family Mart | Baby Care; Breakfast; Dry Fruits; Noodles and Pasta | 58 |
| Guindy Daily Fresh | Bakery; Sauces and Spreads; Frozen; Leafy Greens | 51 |
| OMR Fresh Basket | Pet Care; Frozen; Sauces and Spreads; Noodles and Pasta; Chocolates and Sweets | 62 |
| Thoraipakkam Super Store | Eggs, Meat and Seafood; Millets; Pickles; Household Essentials | 51 |
| Medavakkam Fresh & Daily | Dals; Salt, Sugar and Jaggery; Pooja Needs; Dry Fruits; Baby Care | 46 |

## Where the data ends up

The script works through the API gateway, as people do in the app, so each service stores its own part:

| Data | Service | Tables |
|---|---|---|
| Accounts, roles, the store or restaurant an owner belongs to | identity-service | `identity_db.user_account`, `user_role`, `business_membership` |
| Store profile, GSTIN, FSSAI, address with coordinates, approval | vendor-service | `vendor_db.vendor`, `vendor_status_change` |
| Restaurant profile, cuisines, hours, menu, approval | restaurant-service | `restaurant_db.restaurant`, `restaurant_cuisine`, `restaurant_opening_hours`, `menu_category`, `menu_item`, `restaurant_status_change` |
| Customer profile and delivery addresses with coordinates | customer-service | `customer_db.customer`, `customer_address` |
| Delivery partner profile, vehicle, licence, approval, online status | delivery-service | `delivery_db.delivery_partner`, `delivery_partner_status_change`, `partner_online_session` |
| Categories, brands, products, photo URLs | catalog-command-service | `catalog_db.category`, `brand`, `product` (`brand_id`, `sku` = code), `product_image` |
| MRP and selling price | pricing-service | `pricing_db.product_price`, `price_history` |
| Opening stock | inventory-service | `inventory_db.stock_item`, `stock_movement` |
| Orders, payments, deliveries and tracking | order, payment, delivery services | `order_db.customer_order`, `order_item`, `delivery_db.delivery`, `location_sample`, … |
| Search (name, brand, category, photo, price, stock) | catalog-query-service, from the events | Elasticsearch `products`, `restaurants` |

## Changing the data

- **Add a branded product:** add a row to `products.csv` with a new `CHN` code, put its photo in
  `frontend/grocery-web/public/images/products/` and credit it in `CREDITS.md` there, add the code to a store's
  `products` in `chennai.json`, then run `--check` and the script.
- **Add a person or business:** copy an entry in `chennai.json` and give it a new `key`, e-mail and phone. Take the
  coordinates from OpenStreetMap, then run `--check`: it shows whether the stores and restaurants around the address
  deliver there.
- Re-running never creates anything twice, and it never changes a product or stock that already exists. To change
  those, use the store's workspace in the app.
