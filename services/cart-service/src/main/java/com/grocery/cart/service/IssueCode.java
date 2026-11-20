package com.grocery.cart.service;

import com.grocery.cart.dto.CartDtos.Issue;
import com.grocery.cart.model.StoredLine;

import java.math.BigDecimal;
import java.util.UUID;

/** Issue codes of the cart and the checkout preview (contract §2, Phase 12 §2). Only PRICE_CHANGED lets the customer check out. */
public enum IssueCode {
    PRICE_CHANGED(false),
    ITEM_UNAVAILABLE(true),
    OUT_OF_STOCK(true),
    INSUFFICIENT_STOCK(true),
    STORE_UNAVAILABLE(true),
    STORE_CLOSED(true),
    NOT_ACCEPTING_ORDERS(true),
    PRICING_UNAVAILABLE(true),
    AVAILABILITY_UNKNOWN(true),
    ADDRESS_REQUIRED(true),
    ADDRESS_LOCATION_MISSING(true),
    OUT_OF_DELIVERY_AREA(true),
    BELOW_MINIMUM_ORDER(true),
    EMPTY_CART(true),
    /** Phase 12: the applied coupon cannot be used (any more), or could not be checked. */
    COUPON_NOT_APPLICABLE(true);

    private final boolean blocking;

    IssueCode(boolean blocking) {
        this.blocking = blocking;
    }

    public boolean blocking() {
        return blocking;
    }

    public Issue issue(UUID itemId, String message) {
        return new Issue(name(), itemId, blocking, message);
    }

    /** The message of a line issue, for the item called {@code name}. */
    static String lineMessage(IssueCode code, String name, StoredLine line, BigDecimal unitPrice, Integer available) {
        return switch (code) {
            case PRICE_CHANGED -> name + " now costs " + Money.format(unitPrice)
                    + " (was " + Money.format(line.priceWhenAdded()) + ")";
            case ITEM_UNAVAILABLE -> name + " is no longer available";
            case OUT_OF_STOCK -> name + " is out of stock";
            case INSUFFICIENT_STOCK -> "Only " + available + " of " + name + " left in stock (you have "
                    + line.quantity() + ")";
            case PRICING_UNAVAILABLE -> "The current price of " + name
                    + " could not be checked. Please try again shortly.";
            case AVAILABILITY_UNKNOWN -> "The availability of " + name
                    + " could not be checked. Please try again shortly.";
            default -> name + ": " + code.name();
        };
    }
}
