package com.grocery.catalogquery.index;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;

/** Classifies Elasticsearch error responses by their error type and HTTP status. */
public final class ElasticsearchErrors {

    public static final String INDEX_NOT_FOUND = "index_not_found_exception";
    public static final String VERSION_CONFLICT = "version_conflict_engine_exception";
    public static final String RESOURCE_ALREADY_EXISTS = "resource_already_exists_exception";

    private ElasticsearchErrors() {
    }

    public static String type(ElasticsearchException e) {
        ErrorCause error = e.error();
        return error == null ? null : error.type();
    }

    /** The alias / index does not exist (including writes with {@code require_alias=true}). */
    public static boolean isIndexNotFound(ElasticsearchException e) {
        return INDEX_NOT_FOUND.equals(type(e));
    }

    /** External versioning rejected the write: the stored document is as new or newer (stale or duplicate event). */
    public static boolean isVersionConflict(ElasticsearchException e) {
        return e.status() == 409 || VERSION_CONFLICT.equals(type(e));
    }

    public static boolean isAlreadyExists(ElasticsearchException e) {
        return RESOURCE_ALREADY_EXISTS.equals(type(e));
    }

    /** Overloaded (429) or failing (5xx) cluster: worth retrying later, not a problem with the request. */
    public static boolean isTransient(ElasticsearchException e) {
        return e.status() == 429 || e.status() >= 500;
    }
}
