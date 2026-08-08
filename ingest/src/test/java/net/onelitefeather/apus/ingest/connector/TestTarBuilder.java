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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * Hand-builds minimal ustar/GNU-tar byte streams for tests, since the module has no archive
 * library dependency to build real ones with (see {@link TarStreamReader}'s Javadoc for why).
 */
final class TestTarBuilder {

    private static final int BLOCK_SIZE = 512;

    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    TestTarBuilder addFile(String name, byte[] content) {
        writeHeader(name, content.length, '0');
        body.writeBytes(content);
        writePadding(content.length);
        return this;
    }

    TestTarBuilder addFile(String name, String content) {
        return addFile(name, content.getBytes(StandardCharsets.UTF_8));
    }

    TestTarBuilder addDirectory(String name) {
        String dirName = name.endsWith("/") ? name : name + "/";
        writeHeader(dirName, 0, '5');
        return this;
    }

    /** Writes a GNU long-name entry pair: an {@code 'L'} header carrying the real name, then the file. */
    TestTarBuilder addFileWithLongName(String name, byte[] content) {
        byte[] nameBytes = (name + "\0").getBytes(StandardCharsets.UTF_8);
        writeHeader("", nameBytes.length, 'L');
        body.writeBytes(nameBytes);
        writePadding(nameBytes.length);

        // The following regular-file header's own 100-byte name field is irrelevant once a
        // preceding 'L' entry is present -- real GNU tar still fills something plausible in
        // there, so this mirrors that instead of leaving it blank.
        String truncated = name.length() > 100 ? name.substring(0, 100) : name;
        writeHeader(truncated, content.length, '0');
        body.writeBytes(content);
        writePadding(content.length);
        return this;
    }

    byte[] toTarBytes() {
        ByteArrayOutputStream full = new ByteArrayOutputStream();
        full.writeBytes(body.toByteArray());
        full.writeBytes(new byte[BLOCK_SIZE * 2]); // two zero blocks mark end-of-archive
        return full.toByteArray();
    }

    byte[] toGzippedTarBytes() throws IOException {
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
            gzip.write(toTarBytes());
        }
        return gzipped.toByteArray();
    }

    private void writeHeader(String name, int size, char typeflag) {
        byte[] header = new byte[BLOCK_SIZE];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));
        writeOctal(header, 100, 8, 0x1A4); // mode 0644
        writeOctal(header, 108, 8, 0); // uid
        writeOctal(header, 116, 8, 0); // gid
        writeOctal(header, 124, 12, size);
        writeOctal(header, 136, 12, 0); // mtime
        header[156] = (byte) typeflag;
        byte[] magic = "ustar".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, header, 257, magic.length);
        header[263] = '0';
        header[264] = '0';

        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        int checksum = 0;
        for (byte b : header) {
            checksum += (b & 0xFF);
        }
        byte[] checksumField = String.format("%06o\0 ", checksum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(checksumField, 0, header, 148, checksumField.length);

        body.writeBytes(header);
    }

    private void writeOctal(byte[] header, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        StringBuilder padded = new StringBuilder();
        for (int i = 0; i < length - 1 - octal.length(); i++) {
            padded.append('0');
        }
        padded.append(octal);
        byte[] bytes = padded.toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
        header[offset + length - 1] = 0;
    }

    private void writePadding(int size) {
        int pad = (BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE;
        body.writeBytes(new byte[pad]);
    }
}
