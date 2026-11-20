package com.grocery.common.export;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/**
 * {@code grocery.export.*}: the limits that keep exports from slowing down normal API traffic. The defaults suit
 * every service; change them per service in its {@code application.yml} or with environment variables.
 */
@ConfigurationProperties(prefix = "grocery.export")
public class ExportProperties {

    /** Exports on (the endpoints answer 404 EXPORT_DISABLED when off). */
    private boolean enabled = true;
    /** The most rows one export may have (CSV and Excel). More → 422 EXPORT_TOO_LARGE: narrow the filters. */
    private long maxRows = 1_000_000;
    /** The most rows a PDF may have (a PDF is for reading or printing; 5,000 rows is about 150 pages). */
    private long pdfMaxRows = 5_000;
    /**
     * The most rows of an export the client sorted by a column ({@code ?sort=}): reading in another column's order
     * may need the database to sort the rows for every batch, so such exports stay smaller.
     */
    private long sortedMaxRows = 100_000;
    /** Rows read per database round trip (one short read-only transaction each). */
    private int batchSize = 1_000;
    /** Exports running at once in this service instance; more → 429 EXPORT_BUSY. */
    private int maxConcurrent = 3;
    /** Exports one user may run at once (across this instance); more → 429 EXPORT_BUSY. */
    private int maxPerUser = 2;
    /** Dates and times in the files are shown in this zone (India time). */
    private ZoneId timezone = ZoneId.of("Asia/Kolkata");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(long maxRows) {
        this.maxRows = maxRows;
    }

    public long getPdfMaxRows() {
        return pdfMaxRows;
    }

    public void setPdfMaxRows(long pdfMaxRows) {
        this.pdfMaxRows = pdfMaxRows;
    }

    public long getSortedMaxRows() {
        return sortedMaxRows;
    }

    public void setSortedMaxRows(long sortedMaxRows) {
        this.sortedMaxRows = sortedMaxRows;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public int getMaxPerUser() {
        return maxPerUser;
    }

    public void setMaxPerUser(int maxPerUser) {
        this.maxPerUser = maxPerUser;
    }

    public ZoneId getTimezone() {
        return timezone;
    }

    public void setTimezone(ZoneId timezone) {
        this.timezone = timezone;
    }
}
