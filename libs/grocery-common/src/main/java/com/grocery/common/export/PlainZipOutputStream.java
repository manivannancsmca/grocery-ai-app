package com.grocery.common.export;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Turns the streamed ZIP that fastexcel writes into a plain ZIP, on the fly, so every spreadsheet program opens it.
 *
 * <p>fastexcel zips the workbook with opczip, which marks every entry "ZIP64" (version 4.5) and ends it with a 24-byte
 * ZIP64 data descriptor — without the ZIP64 extra field that tells a reader to expect one. Excel and fastexcel's reader
 * accept that; LibreOffice (24.x) refuses the file as corrupt and Java's {@code ZipInputStream} fails on it. The same
 * entries with the standard 16-byte descriptor and version 2.0 open everywhere.
 *
 * <p>How: the records are rewritten as they pass ({@code PK\3\4} local header, {@code PK\1\2} central directory entry,
 * {@code PK\5\6} end record; a few dozen bytes each). An entry's compressed data goes straight through; where it ends
 * is not written anywhere before it, so an {@link Inflater} reads along — deflate data ends itself, and the inflater
 * says where. The descriptor after it is rewritten with 32-bit sizes, which shifts every later entry by 8 bytes; the
 * central directory and the end record get the new offsets. Memory stays a few kilobytes whatever the file size.
 * Anything this does not expect (an entry that is not deflated, a part or a file over 4 GB, a real ZIP64 record) stops
 * the export with an {@link IOException} rather than writing a damaged file — the export limits keep files far below.
 */
final class PlainZipOutputStream extends FilterOutputStream {

    private static final int LOCAL_HEADER = 0x04034b50;
    private static final int DATA_DESCRIPTOR = 0x08074b50;
    private static final int CENTRAL_HEADER = 0x02014b50;
    private static final int END_OF_CENTRAL_DIRECTORY = 0x06054b50;
    private static final int ZIP64_DESCRIPTOR_LENGTH = 24;
    private static final short PLAIN_VERSION = 20;
    private static final long MAX_32_BIT = 0xFFFF_FFFFL;

    private enum State { RECORD, DATA, DESCRIPTOR, DONE }

    private State state = State.RECORD;
    /** The record being collected (a header, a descriptor). */
    private final ByteArrayOutputStream record = new ByteArrayOutputStream(128);
    private final byte[] one = new byte[1];
    private final byte[] scratch = new byte[64 * 1024];
    private Inflater inflater;
    /** Bytes written to the target: the new offset of the next record. */
    private long written;
    /** The entries' new local-header offsets, in order (the central directory lists them in the same order). */
    private final Deque<Long> localOffsets = new ArrayDeque<>();
    private long centralDirectoryStart = -1;

    PlainZipOutputStream(OutputStream out) {
        super(out);
    }

    @Override
    public void write(int b) throws IOException {
        one[0] = (byte) b;
        write(one, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int end = off + len;
        while (off < end) {
            off = switch (state) {
                case RECORD -> record(b, off, end);
                case DATA -> data(b, off, end);
                case DESCRIPTOR -> descriptor(b, off, end);
                case DONE -> throw new IOException("Bytes after the end of the ZIP");
            };
        }
    }

    /** Collects a header record; once complete, rewrites and writes it. */
    private int record(byte[] b, int off, int end) throws IOException {
        int needed = recordLength();
        int take = Math.min(end - off, needed - record.size());
        record.write(b, off, take);
        if (record.size() == recordLength()) { // the length can grow once the fixed part (with the lengths) is in
            ByteBuffer header = ByteBuffer.wrap(record.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            record.reset();
            switch (header.getInt(0)) {
                case LOCAL_HEADER -> localHeader(header);
                case CENTRAL_HEADER -> centralHeader(header);
                case END_OF_CENTRAL_DIRECTORY -> endRecord(header);
                default -> throw new IOException("Unexpected ZIP record " + Integer.toHexString(header.getInt(0)));
            }
        }
        return off + take;
    }

    /** How long the record being collected is, as far as its bytes so far tell (at least its fixed part). */
    private int recordLength() throws IOException {
        if (record.size() < 4) {
            return 4;
        }
        byte[] bytes = record.toByteArray();
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return switch (header.getInt(0)) {
            case LOCAL_HEADER -> bytes.length < 30 ? 30 : 30 + u16(header, 26) + u16(header, 28);
            case CENTRAL_HEADER -> bytes.length < 46 ? 46 : 46 + u16(header, 28) + u16(header, 30) + u16(header, 32);
            case END_OF_CENTRAL_DIRECTORY -> bytes.length < 22 ? 22 : 22 + u16(header, 20);
            default -> throw new IOException("Unexpected ZIP record " + Integer.toHexString(header.getInt(0)));
        };
    }

    private void localHeader(ByteBuffer header) throws IOException {
        int method = u16(header, 8);
        if (method != 8 || (u16(header, 6) & 0x08) == 0 || u16(header, 28) != 0) {
            throw new IOException("Unsupported ZIP entry (method " + method + ", flags " + u16(header, 6) + ")");
        }
        header.putShort(4, PLAIN_VERSION);
        localOffsets.addLast(written);
        emit(header.array());
        inflater = new Inflater(true);
        state = State.DATA;
    }

    /** Passes the compressed data through until the inflater reaches its end. */
    private int data(byte[] b, int off, int end) throws IOException {
        inflater.setInput(b, off, end - off);
        try {
            while (!inflater.finished() && !inflater.needsInput()) {
                inflater.inflate(scratch);
            }
        } catch (DataFormatException e) {
            throw new IOException("Invalid deflate data in the ZIP", e);
        }
        int used = (end - off) - inflater.getRemaining();
        out.write(b, off, used);
        written += used;
        if (inflater.finished()) {
            state = State.DESCRIPTOR;
        }
        return off + used;
    }

    /**
     * The data descriptor after an entry, as opczip writes it (signature, CRC, 64-bit sizes: 24 bytes), written again
     * with 32-bit sizes (16 bytes). Its sizes must be what the inflater counted, or the ZIP is not what we expect.
     */
    private int descriptor(byte[] b, int off, int end) throws IOException {
        int take = Math.min(end - off, ZIP64_DESCRIPTOR_LENGTH - record.size());
        record.write(b, off, take);
        if (record.size() < ZIP64_DESCRIPTOR_LENGTH) {
            return off + take;
        }
        ByteBuffer descriptor = ByteBuffer.wrap(record.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        record.reset();
        long compressed = inflater.getBytesRead();
        long size = inflater.getBytesWritten();
        if (descriptor.getInt(0) != DATA_DESCRIPTOR || descriptor.getLong(8) != compressed
                || descriptor.getLong(16) != size) {
            throw new IOException("Unexpected ZIP data descriptor");
        }
        if (compressed > MAX_32_BIT || size > MAX_32_BIT) {
            throw new IOException("A part of the workbook is over 4 GB");
        }
        ByteBuffer plain = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(DATA_DESCRIPTOR).putInt(descriptor.getInt(4)).putInt((int) compressed).putInt((int) size);
        emit(plain.array());
        inflater.end();
        inflater = null;
        state = State.RECORD;
        return off + take;
    }

    private void centralHeader(ByteBuffer header) throws IOException {
        if (centralDirectoryStart < 0) {
            centralDirectoryStart = written;
        }
        Long offset = localOffsets.pollFirst();
        if (offset == null || u16(header, 30) != 0) {
            throw new IOException("Unexpected ZIP central directory entry");
        }
        if (offset > MAX_32_BIT) {
            throw new IOException("The workbook is over 4 GB");
        }
        header.putShort(4, PLAIN_VERSION);   // version made by: 2.0, MS-DOS
        header.putShort(6, PLAIN_VERSION);   // version needed to extract
        header.putInt(42, (int) (long) offset);
        emit(header.array());
    }

    private void endRecord(ByteBuffer header) throws IOException {
        if (!localOffsets.isEmpty() || centralDirectoryStart < 0) {
            throw new IOException("ZIP central directory does not list every entry");
        }
        if (centralDirectoryStart > MAX_32_BIT) {
            throw new IOException("The workbook is over 4 GB");
        }
        header.putInt(16, (int) centralDirectoryStart);
        emit(header.array());
        state = State.DONE;
    }

    private void emit(byte[] bytes) throws IOException {
        out.write(bytes);
        written += bytes.length;
    }

    /** Throws unless the whole ZIP went through (called when the workbook is finished). */
    void checkComplete() throws IOException {
        if (state != State.DONE) {
            throw new IOException("The ZIP ended early (" + state + ")");
        }
    }

    @Override
    public void close() throws IOException {
        if (inflater != null) {
            inflater.end();
        }
        flush(); // the response stream belongs to the Exporter
    }

    private static int u16(ByteBuffer buffer, int index) {
        return Short.toUnsignedInt(buffer.getShort(index));
    }
}
