package com.grocery.common.export;

/**
 * An export failed after its first bytes were sent: the status (200) and part of the file are already on their way,
 * so no error body can follow. {@code GlobalExceptionHandler} lets this exception reach the servlet container, which
 * then closes the connection without ending the response: the browser's download fails visibly ("network error")
 * instead of saving a file that only looks complete.
 */
public class ExportStreamException extends RuntimeException {

    public ExportStreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
