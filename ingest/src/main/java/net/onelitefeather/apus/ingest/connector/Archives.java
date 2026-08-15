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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extraction helpers for the archive formats a world source may hand back: ZIP and gzip-compressed
 * tar. Every entry path is resolved against the target directory with a zip-slip guard, since
 * archive contents are attacker-controllable input coming from an external source (an S3 bucket
 * or a Pterodactyl panel backup).
 *
 * <p><b>Extraction is bounded by {@link Limits}.</b> The ingest job mounts no volume for its work
 * directory and sets no filesystem-level quota of its own -- extraction lands on the container's
 * writable layer, backed by the node's own disk. A crafted "archive bomb" (either a huge total
 * uncompressed volume, or an enormous number of tiny entries) would otherwise be able to fill that
 * disk and, since it is shared with every other pod scheduled on the node, degrade or evict
 * unrelated workloads. Both the total-bytes and entry-count limits are enforced as extraction
 * proceeds -- not trusted from a declared size in the archive metadata, which is attacker-supplied
 * and (for ZIP in particular) not always even present -- so an oversized archive is aborted with a
 * clear {@link IOException} partway through rather than only being caught after already having
 * written its way past the limit.
 */
public final class Archives {

    private static final Logger LOGGER = LoggerFactory.getLogger(Archives.class);

    /** Config key: total bytes across every extracted entry before extraction aborts. See {@link Limits}. */
    public static final String CONFIG_MAX_TOTAL_BYTES = "archiveMaxTotalBytes";

    /** Config key: number of entries (files + directories) written before extraction aborts. See {@link Limits}. */
    public static final String CONFIG_MAX_ENTRIES = "archiveMaxEntries";

    private static final int COPY_BUFFER_SIZE = 8192;

    private Archives() {}

    /** Upper bounds enforced while extracting one archive -- see the class Javadoc. */
    public record Limits(long maxTotalBytes, long maxEntries) {

        /** No limit at all -- only for callers (tests, internal reuse) that intentionally want unbounded extraction. */
        public static final Limits UNBOUNDED = new Limits(Long.MAX_VALUE, Long.MAX_VALUE);
    }

    /**
     * Reads {@link #CONFIG_MAX_TOTAL_BYTES}/{@link #CONFIG_MAX_ENTRIES} out of a connector's
     * config map, as put there by {@code IngestConfig} -- see that class for where the
     * configured (or defaulted) values originate.
     */
    public static Limits limitsFrom(Map<String, String> config) {
        return new Limits(
                parseOrUnbounded(config.get(CONFIG_MAX_TOTAL_BYTES)), parseOrUnbounded(config.get(CONFIG_MAX_ENTRIES)));
    }

    private static long parseOrUnbounded(String value) {
        if (value == null || value.isBlank()) {
            return Long.MAX_VALUE;
        }
        return Long.parseLong(value.trim());
    }

    static boolean isZip(String key) {
        return endsWithIgnoreCase(key, ".zip");
    }

    static boolean isTarGz(String key) {
        return endsWithIgnoreCase(key, ".tar.gz") || endsWithIgnoreCase(key, ".tgz");
    }

    static boolean isTar(String key) {
        return endsWithIgnoreCase(key, ".tar");
    }

    static boolean isArchive(String key) {
        return isZip(key) || isTarGz(key) || isTar(key);
    }

    /** Extracts every entry of {@code key}'s archive format from {@code rawIn} into {@code targetDir}, unbounded. */
    static void extract(String key, InputStream rawIn, Path targetDir) throws IOException {
        extract(key, rawIn, targetDir, Limits.UNBOUNDED);
    }

    /** Same as {@link #extract(String, InputStream, Path)}, bounded by {@code limits}. */
    public static void extract(String key, InputStream rawIn, Path targetDir, Limits limits) throws IOException {
        if (isZip(key)) {
            extractZip(rawIn, targetDir, limits);
        } else if (isTarGz(key)) {
            try (GZIPInputStream gzip = new GZIPInputStream(rawIn)) {
                extractTar(gzip, targetDir, entryName -> true, limits);
            }
        } else if (isTar(key)) {
            extractTar(rawIn, targetDir, entryName -> true, limits);
        } else {
            throw new IllegalArgumentException("not a recognised archive key: " + key);
        }
    }

    private static void extractZip(InputStream in, Path targetDir, Limits limits) throws IOException {
        long[] totalBytes = {0};
        long entries = 0;
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                checkEntryCount(entries, limits);
                Path target = resolveSafely(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target)) {
                        copyLimited(zip, out, limits, totalBytes);
                    }
                }
                zip.closeEntry();
            }
        }
        LOGGER.info("unpacked {} zip entries ({} bytes) into {}", entries, totalBytes[0], targetDir);
    }

    /**
     * Streams an already-decompressed tar body exactly once, writing only entries for which
     * {@code include} returns {@code true}. The archive is never buffered as a whole -- an
     * included entry is copied straight from the input stream to its target file, and an
     * excluded entry's bytes are read and discarded in bounded chunks, never landing on disk.
     */
    static void extractTar(InputStream in, Path targetDir, Predicate<String> include) throws IOException {
        extractTar(in, targetDir, include, Limits.UNBOUNDED);
    }

    /** Same as {@link #extractTar(InputStream, Path, Predicate)}, bounded by {@code limits}. */
    public static void extractTar(InputStream in, Path targetDir, Predicate<String> include, Limits limits)
            throws IOException {
        long[] totalBytes = {0};
        long entries = 0;
        try (TarStreamReader tar = new TarStreamReader(in)) {
            TarStreamReader.Entry entry;
            while ((entry = tar.nextEntry()) != null) {
                if (!include.test(entry.name())) {
                    continue;
                }
                entries++;
                checkEntryCount(entries, limits);
                Path target = resolveSafely(targetDir, entry.name());
                if (entry.directory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    tar.transferTo(new LimitCheckingOutputStream(out, limits, totalBytes));
                }
            }
        }
        // Entries the include predicate rejected are not counted: for a Pterodactyl backup that is
        // the whole server minus the world, and reporting it as "extracted" would be a lie.
        LOGGER.info("unpacked {} tar entries ({} bytes) into {}", entries, totalBytes[0], targetDir);
    }

    /**
     * Copies {@code in} to {@code out} in bounded chunks, tracking {@code totalBytes} (shared
     * across every entry of the archive being extracted) against {@code limits.maxTotalBytes()}.
     * Enforced against bytes actually copied, not a declared/expected size -- an entry's
     * declared size is attacker-controlled input and, for ZIP in particular, not always even
     * present -- so a single oversized entry is caught mid-copy exactly like many small entries
     * summing past the limit would be.
     */
    private static void copyLimited(InputStream in, OutputStream out, Limits limits, long[] totalBytes)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            totalBytes[0] += read;
            if (totalBytes[0] > limits.maxTotalBytes()) {
                throw new IOException("archive exceeds the configured total size limit of " + limits.maxTotalBytes()
                        + " bytes; aborting extraction");
            }
            out.write(buffer, 0, read);
        }
    }

    /**
     * Wraps a destination {@link OutputStream}, enforcing {@code limits.maxTotalBytes()} against a
     * running total shared across every entry -- lets {@link TarStreamReader#transferTo} (which
     * only knows how to push bytes to an {@code OutputStream}, not report them back in bounded
     * chunks) participate in the same total-volume accounting {@link #copyLimited} applies to ZIP.
     */
    private static final class LimitCheckingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final Limits limits;
        private final long[] totalBytes;

        LimitCheckingOutputStream(OutputStream delegate, Limits limits, long[] totalBytes) {
            this.delegate = delegate;
            this.limits = limits;
            this.totalBytes = totalBytes;
        }

        @Override
        public void write(int b) throws IOException {
            checkAndAccount(1);
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            checkAndAccount(len);
            delegate.write(b, off, len);
        }

        private void checkAndAccount(int additional) throws IOException {
            totalBytes[0] += additional;
            if (totalBytes[0] > limits.maxTotalBytes()) {
                throw new IOException("archive exceeds the configured total size limit of " + limits.maxTotalBytes()
                        + " bytes; aborting extraction");
            }
        }
    }

    private static void checkEntryCount(long entries, Limits limits) throws IOException {
        if (entries > limits.maxEntries()) {
            throw new IOException(
                    "archive exceeds the configured entry limit of " + limits.maxEntries() + "; aborting extraction");
        }
    }

    private static Path resolveSafely(Path targetDir, String entryName) throws IOException {
        Path normalisedTargetDir = targetDir.normalize();
        Path target = normalisedTargetDir.resolve(entryName).normalize();
        if (!target.startsWith(normalisedTargetDir)) {
            throw new IOException("archive entry escapes target directory: " + entryName);
        }
        return target;
    }

    private static boolean endsWithIgnoreCase(String value, String suffix) {
        return value != null && value.toLowerCase(Locale.ROOT).endsWith(suffix);
    }
}
