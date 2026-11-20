package com.grocery.apitests.support;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A response plus contract assertions. Every failure message shows the request and the (abbreviated) response
 * body, so a red test explains itself.
 */
public final class Res {

    private final String method;
    private final String path;
    private final Response raw;
    private final String body;

    Res(String method, String path, Response raw) {
        this.method = method;
        this.path = path;
        this.raw = raw;
        this.body = raw.asString();
    }

    public int status() {
        return raw.statusCode();
    }

    public String body() {
        return body;
    }

    public Response raw() {
        return raw;
    }

    public String header(String name) {
        return raw.header(name);
    }

    public List<String> headers(String name) {
        return raw.headers().getValues(name);
    }

    public String contentType() {
        return raw.contentType();
    }

    public JsonPath json() {
        return JsonPath.from(body.isBlank() ? "{}" : body)
                .using(io.restassured.path.json.config.JsonPathConfig.jsonPathConfig()
                        .numberReturnType(io.restassured.path.json.config.JsonPathConfig.NumberReturnType.BIG_DECIMAL));
    }

    /** A string at a GPath ({@code "items[0].name"}), or null. */
    public String str(String gpath) {
        Object value = json().get(root(gpath));
        return value == null ? null : String.valueOf(value);
    }

    public BigDecimal dec(String gpath) {
        Object value = json().get(root(gpath));
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    public Integer integer(String gpath) {
        Object value = json().get(root(gpath));
        return value == null ? null : new BigDecimal(String.valueOf(value)).intValueExact();
    }

    public Boolean bool(String gpath) {
        Object value = json().get(root(gpath));
        return value == null ? null : (Boolean) value;
    }

    public <T> List<T> list(String gpath) {
        List<T> value = json().getList(root(gpath));
        return value == null ? List.of() : value;
    }

    public Map<String, Object> map(String gpath) {
        return json().getMap(root(gpath));
    }

    private static String root(String gpath) {
        return gpath == null || gpath.isEmpty() ? "$" : gpath;
    }

    public String errorCode() {
        return isJson() ? str("errorCode") : null;
    }

    public boolean isJson() {
        String type = contentType();
        return type != null && type.contains("json") && !body.isBlank();
    }

    public String describe() {
        return method + " " + path + " → " + status() + " " + Json.abbreviate(body, 900);
    }

    // ---- assertions --------------------------------------------------------------------------------------------

    public Res expect(int expectedStatus) {
        assertThat(status()).as("HTTP status of %s", describe()).isEqualTo(expectedStatus);
        return this;
    }

    public Res ok() {
        return expect(200);
    }

    public Res created() {
        return expect(201);
    }

    /**
     * An RFC 9457 ProblemDetail as the platform writes it: {@code application/problem+json}, numeric {@code status}
     * equal to the HTTP status, a {@code title}, and the stable {@code errorCode}.
     */
    public Res problem(int expectedStatus, String expectedErrorCode) {
        expect(expectedStatus);
        assertThat(contentType()).as("content type of %s", describe()).startsWith("application/problem+json");
        assertThat(integer("status")).as("ProblemDetail.status of %s", describe()).isEqualTo(expectedStatus);
        assertThat(str("title")).as("ProblemDetail.title of %s", describe()).isNotBlank();
        if (expectedErrorCode != null) {
            assertThat(errorCode()).as("errorCode of %s", describe()).isEqualTo(expectedErrorCode);
        } else {
            assertThat(errorCode()).as("errorCode of %s", describe()).isNotBlank();
        }
        return this;
    }

    /** A conflict carrying the documented {@code currentStatus} property (never a second {@code status}). */
    public Res conflict(String expectedErrorCode, String expectedCurrentStatus) {
        problem(409, expectedErrorCode);
        assertThat(str("currentStatus")).as("currentStatus of %s", describe()).isEqualTo(expectedCurrentStatus);
        return this;
    }

    /** A Phase 2 {@code PageResponse}: content[], page, size, totalElements, totalPages. */
    public Res page() {
        assertThat(json().getList("content")).as("PageResponse.content of %s", describe()).isNotNull();
        assertThat(integer("page")).as("PageResponse.page of %s", describe()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(integer("size")).as("PageResponse.size of %s", describe()).isNotNull().isPositive();
        assertThat(integer("totalElements")).as("PageResponse.totalElements of %s", describe()).isNotNull()
                .isGreaterThanOrEqualTo(0);
        assertThat(integer("totalPages")).as("PageResponse.totalPages of %s", describe()).isNotNull()
                .isGreaterThanOrEqualTo(0);
        return this;
    }

    public Res has(String gpath, Object expected) {
        Object actual = json().get(root(gpath));
        if (expected instanceof BigDecimal expectedDecimal && actual != null) {
            assertThat(new BigDecimal(String.valueOf(actual))).as("%s of %s", gpath, describe())
                    .isEqualByComparingTo(expectedDecimal);
        } else if (expected instanceof Number && actual instanceof Number) {
            assertThat(new BigDecimal(String.valueOf(actual))).as("%s of %s", gpath, describe())
                    .isEqualByComparingTo(new BigDecimal(String.valueOf(expected)));
        } else {
            assertThat(actual).as("%s of %s", gpath, describe()).isEqualTo(expected);
        }
        return this;
    }

    public Res bodyContains(String text) {
        assertThat(body).as("body of %s", describe()).contains(text);
        return this;
    }

    public Res bodyDoesNotContain(String text) {
        assertThat(body).as("body of %s", describe()).doesNotContain(text);
        return this;
    }

    @Override
    public String toString() {
        return describe();
    }
}
