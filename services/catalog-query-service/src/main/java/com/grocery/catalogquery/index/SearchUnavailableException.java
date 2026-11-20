package com.grocery.catalogquery.index;

import com.grocery.common.web.ApiException;
import org.springframework.http.HttpStatus;

/** 503 SEARCH_UNAVAILABLE — Elasticsearch is unreachable or overloaded; the client may retry shortly. */
public class SearchUnavailableException extends ApiException {

    public SearchUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_UNAVAILABLE",
                "Search is temporarily unavailable. Please try again shortly.");
        initCause(cause);
    }
}
