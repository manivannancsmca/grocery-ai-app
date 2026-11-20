-- Phase 3: restaurant menus and the public listing published to search (RestaurantListingUpdated).

-- accepting_orders: the owner's "temporarily stop new orders" switch (the restaurant stays visible).
-- listing_version:  version of the last published RestaurantListingUpdated snapshot. Incremented, under a
--                   SELECT … FOR UPDATE lock on this row, by every change to an ever-approved restaurant's listing;
--                   catalog-query uses it as the Elasticsearch external version (see RestaurantListingPublisher).
ALTER TABLE restaurant
    ADD COLUMN accepting_orders BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN listing_version  BIGINT  NOT NULL DEFAULT 0;

-- Menu sections ("Biryani", "Starters"). At most 30 per restaurant (enforced by the service under the row lock).
-- The collation is case- (and accent-) insensitive, so the unique key also rejects "biryani" next to "Biryani".
CREATE TABLE menu_category (
    id             BINARY(16)   NOT NULL,
    restaurant_id  BINARY(16)   NOT NULL,
    name           VARCHAR(80)  NOT NULL,
    description    VARCHAR(300) NULL,
    sort_order     INT          NOT NULL DEFAULT 0,      -- 0–9999, lower first; ties sorted by name
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_menu_category PRIMARY KEY (id),
    CONSTRAINT uk_menu_category_restaurant_name UNIQUE (restaurant_id, name),
    CONSTRAINT fk_menu_category_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Dishes. At most 500 per restaurant. Prices in INR with 2 decimals. Unavailable ("sold out") items stay listed.
CREATE TABLE menu_item (
    id             BINARY(16)    NOT NULL,
    restaurant_id  BINARY(16)    NOT NULL,
    category_id    BINARY(16)    NOT NULL,
    name           VARCHAR(120)  NOT NULL,
    description    VARCHAR(500)  NULL,
    price          DECIMAL(10,2) NOT NULL,              -- 1.00 – 100000.00
    food_type      VARCHAR(10)   NOT NULL,              -- VEG | NON_VEG | EGG
    image_url      VARCHAR(500)  NULL,
    available      BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order     INT           NOT NULL DEFAULT 0,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_menu_item PRIMARY KEY (id),
    CONSTRAINT fk_menu_item_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id) ON DELETE CASCADE,
    -- No cascade: a category can only be deleted once it is empty (409 MENU_CATEGORY_NOT_EMPTY).
    CONSTRAINT fk_menu_item_category FOREIGN KEY (category_id) REFERENCES menu_category (id),
    INDEX idx_menu_item_restaurant_category (restaurant_id, category_id),
    INDEX idx_menu_item_category (category_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
