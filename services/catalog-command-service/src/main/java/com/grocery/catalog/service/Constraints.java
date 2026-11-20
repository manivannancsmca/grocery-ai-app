package com.grocery.catalog.service;

import org.springframework.dao.DataIntegrityViolationException;

import java.util.Locale;

/** Recognises which unique key a {@link DataIntegrityViolationException} came from. */
final class Constraints {

    private Constraints() {
    }

    /** MySQL reports e.g. "Duplicate entry '…' for key 'product.uk_product_vendor_sku'". */
    static boolean isViolation(DataIntegrityViolationException e, String constraintName) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(constraintName.toLowerCase(Locale.ROOT));
    }
}
