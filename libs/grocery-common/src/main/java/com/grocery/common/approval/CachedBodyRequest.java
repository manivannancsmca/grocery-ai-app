package com.grocery.common.approval;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * The request with its body read once into memory (bounded), so the approval filter can hash the exact bytes and the
 * handler still reads the same bytes afterwards. Works for empty bodies too.
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    /** The body is larger than the approval filter accepts. */
    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException(int limit) {
            super("request body larger than " + limit + " bytes");
        }
    }

    private final int limit;
    private byte[] body;

    CachedBodyRequest(HttpServletRequest request, int limit) {
        super(request);
        this.limit = limit;
    }

    /** The body bytes, read from the original request on the first call. */
    byte[] body() throws IOException {
        if (body == null) {
            byte[] bytes = super.getInputStream().readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new BodyTooLargeException(limit);
            }
            body = bytes;
        }
        return body;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        return new CachedInputStream(body());
    }

    @Override
    public BufferedReader getReader() throws IOException {
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body()), charset));
    }

    @Override
    public int getContentLength() {
        return body == null ? super.getContentLength() : body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body == null ? super.getContentLengthLong() : body.length;
    }

    private static final class CachedInputStream extends ServletInputStream {

        private final ByteArrayInputStream in;

        CachedInputStream(byte[] bytes) {
            this.in = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() {
            return in.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return in.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return in.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            try {
                listener.onDataAvailable();
                listener.onAllDataRead();
            } catch (IOException e) {
                listener.onError(e);
            }
        }
    }
}
