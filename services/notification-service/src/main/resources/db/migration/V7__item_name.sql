-- Phase 12 §1: review notifications name the product or dish ("{targetName}"), but review events carry only its id.
-- The order lines of OrderCreated / OrderConfirmed (which this service already consumes) give productId / menuItemId ->
-- name; a review is always about an item of a delivered order, so the name is known by then. The latest name wins.
-- Bounded by the catalogue size: no clean-up.
CREATE TABLE item_name (
    item_id     BINARY(16)    NOT NULL,   -- productId (PRODUCT) or menuItemId (MENU_ITEM)
    item_type   VARCHAR(12)   NULL,       -- PRODUCT | MENU_ITEM
    name        VARCHAR(200)  NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    CONSTRAINT pk_item_name PRIMARY KEY (item_id)
) ENGINE = InnoDB;
