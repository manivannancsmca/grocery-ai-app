package com.grocery.delivery.dispatch.repository;

import java.util.UUID;

/** Who an offer belongs to (immutable columns, read without a lock and without loading the entity). */
public record OfferRef(UUID deliveryId, UUID partnerId) {
}
