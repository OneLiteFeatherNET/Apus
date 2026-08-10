/**
 * Apus - render and host BlueMap maps on Kubernetes.
 * Copyright (C) 2026 OneLiteFeather and contributors
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.onelitefeather.apus.ingest.connector;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A minimal, single-pass reader for the tar container format (ustar, with GNU long-name and PAX
 * extended-header extensions), reading entries directly off an {@link InputStream} without ever
 * buffering the archive as a whole.
 *
 * <p>This exists only because the ingest module cannot take on a general-purpose archive library
 * dependency from within this task's file scope (build files are out of bounds -- see the task
 * report). It implements exactly the subset of the format real-world backups use: regular files,
 * directories, ustar name prefixes, GNU long names ({@code typeflag 'L'}), and PAX path overrides
 * ({@code typeflag 'x'}). It is not a general-purpose tar library and does not attempt to be one.
 */
final class TarStreamReader implements Closeable {

    private static final int BLOCK_SIZE = 512;

    private final InputStream in;
    private long remainingInEntry;
    private long entryPadding;
    private String pendingLongName;

    TarStreamReader(InputStream in) {
        this.in = in;
    }

    /** One tar entry's header fields relevant to extraction. */
    record Entry(String name, long size, boolean directory) {}

    /**
     * Advances to the next entry, skipping whatever of the previous entry (and its block padding)
     * was not consumed via {@link #transferTo}.
     *
     * @return the next entry, or {@code null} at the end of the archive
     */
    Entry nextEntry() throws IOException {
        finishCurrentEntry();
        while (true) {
            byte[] header = readHeaderBlock();
            if (header == null || isAllZero(header)) {
                return null;
            }

            char typeflag = (char) (header[156] & 0xFF);
            long size = parseSize(header, 124, 12);

            if (typeflag == 'L') {
                pendingLongName = readAndUnpad(size);
                continue;
            }
            if (typeflag == 'K') {
                skipFully(size + paddingFor(size));
                continue;
            }
            if (typeflag == 'x' || typeflag == 'g') {
                String paxPath = parsePaxPath(readAndUnpad(size));
                if (paxPath != null) {
                    pendingLongName = paxPath;
                }
                continue;
            }

            String name = readString(header, 0, 100);
            String prefix = readString(header, 345, 155);
            String fullName = prefix.isEmpty() ? name : prefix + "/" + name;
            if (pendingLongName != null) {
                fullName = pendingLongName;
                pendingLongName = null;
            }

            remainingInEntry = size;
            entryPadding = paddingFor(size);
            boolean directory = typeflag == '5' || fullName.endsWith("/");
            return new Entry(fullName, size, directory);
        }
    }

    /** Copies the current entry's remaining content to {@code out}. */
    long transferTo(OutputStream out) throws IOException {
        long copied = 0;
        byte[] buffer = new byte[8192];
        while (remainingInEntry > 0) {
            int toRead = (int) Math.min(buffer.length, remainingInEntry);
            int read = in.read(buffer, 0, toRead);
            if (read < 0) {
                throw new EOFException("unexpected end of tar stream while reading entry content");
            }
            out.write(buffer, 0, read);
            remainingInEntry -= read;
            copied += read;
        }
        return copied;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    private void finishCurrentEntry() throws IOException {
        skipFully(remainingInEntry + entryPadding);
        remainingInEntry = 0;
        entryPadding = 0;
    }

    private String readAndUnpad(long size) throws IOException {
        byte[] data = readExact(toIntSize(size));
        skipFully(paddingFor(size));
        return new String(data, StandardCharsets.UTF_8).replace("\0", "");
    }

    private byte[] readHeaderBlock() throws IOException {
        byte[] block = new byte[BLOCK_SIZE];
        int total = 0;
        while (total < BLOCK_SIZE) {
            int read = in.read(block, total, BLOCK_SIZE - total);
            if (read < 0) {
                if (total == 0) {
                    return null;
                }
                throw new EOFException("truncated tar header block");
            }
            total += read;
        }
        return block;
    }

    private byte[] readExact(int n) throws IOException {
        byte[] data = new byte[n];
        int total = 0;
        while (total < n) {
            int read = in.read(data, total, n - total);
            if (read < 0) {
                throw new EOFException("unexpected end of tar stream");
            }
            total += read;
        }
        return data;
    }

    private void skipFully(long n) throws IOException {
        long remaining = n;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, toRead);
            if (read < 0) {
                throw new EOFException("unexpected end of tar stream while skipping");
            }
            remaining -= read;
        }
    }

    private static long paddingFor(long size) {
        return (BLOCK_SIZE - (int) (size % BLOCK_SIZE)) % BLOCK_SIZE;
    }

    private static int toIntSize(long size) throws IOException {
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new IOException("unsupported tar entry size: " + size);
        }
        return (int) size;
    }

    private static boolean isAllZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String readString(byte[] header, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long parseSize(byte[] header, int offset, int length) {
        if ((header[offset] & 0x80) != 0) {
            // GNU base-256 extension: high bit set marks a big-endian binary size, used for
            // files too large to fit the 8GiB ceiling of a 12-digit octal field.
            long value = 0;
            for (int i = 1; i < length; i++) {
                value = (value << 8) | (header[offset + i] & 0xFF);
            }
            return value;
        }
        String field = new String(header, offset, length, StandardCharsets.US_ASCII)
                .replace("\0", "")
                .trim();
        return field.isEmpty() ? 0L : Long.parseLong(field, 8);
    }

    /**
     * Extracts the {@code path} record from a PAX extended header body (records look like
     * {@code "<length> <key>=<value>\n"}, length-prefixed and self-describing).
     */
    private static String parsePaxPath(String content) {
        int index = 0;
        while (index < content.length()) {
            int spaceIndex = content.indexOf(' ', index);
            if (spaceIndex < 0) {
                break;
            }
            int recordLength;
            try {
                recordLength = Integer.parseInt(content.substring(index, spaceIndex));
            } catch (NumberFormatException e) {
                break;
            }
            int recordEnd = index + recordLength;
            if (recordLength <= 0 || recordEnd > content.length()) {
                break;
            }
            // record body is "key=value\n" (trailing newline dropped)
            String record = content.substring(spaceIndex + 1, recordEnd - 1);
            int equalsIndex = record.indexOf('=');
            if (equalsIndex > 0 && "path".equals(record.substring(0, equalsIndex))) {
                return record.substring(equalsIndex + 1);
            }
            index = recordEnd;
        }
        return null;
    }
}
