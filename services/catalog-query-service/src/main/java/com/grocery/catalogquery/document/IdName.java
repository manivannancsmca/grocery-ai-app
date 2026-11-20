package com.grocery.catalogquery.document;

/** An id with its display name (category path entries, brand, vendor). */
public record IdName(String id, String name) {

    /** Facet key "id|name" (ids never contain '|'). */
    public String facetKey() {
        return id + "|" + name;
    }

    /** Parses a facet key; null when it is not of the form "id|name". */
    public static IdName fromFacetKey(String key) {
        if (key == null) {
            return null;
        }
        int separator = key.indexOf('|');
        return separator < 0 ? null : new IdName(key.substring(0, separator), key.substring(separator + 1));
    }
}
