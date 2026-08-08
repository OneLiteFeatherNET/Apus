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
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extraction helpers for the archive formats a world source may hand back: ZIP and gzip-compressed
 * tar. Every entry path is resolved against the target directory with a zip-slip guard, since
 * archive contents are attacker-controllable input coming from an external source (an S3 bucket
 * or a Pterodactyl panel backup).
 */
final class Archives {

    private Archives() {}

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

    /** Extracts every entry of {@code key}'s archive format from {@code rawIn} into {@code targetDir}. */
    static void extract(String key, InputStream rawIn, Path targetDir) throws IOException {
        if (isZip(key)) {
            extractZip(rawIn, targetDir);
        } else if (isTarGz(key)) {
            try (GZIPInputStream gzip = new GZIPInputStream(rawIn)) {
                extractTar(gzip, targetDir, entryName -> true);
            }
        } else if (isTar(key)) {
            extractTar(rawIn, targetDir, entryName -> true);
        } else {
            throw new IllegalArgumentException("not a recognised archive key: " + key);
        }
    }

    private static void extractZip(InputStream in, Path targetDir) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = resolveSafely(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    /**
     * Streams an already-decompressed tar body exactly once, writing only entries for which
     * {@code include} returns {@code true}. The archive is never buffered as a whole -- an
     * included entry is copied straight from the input stream to its target file, and an
     * excluded entry's bytes are read and discarded in bounded chunks, never landing on disk.
     */
    static void extractTar(InputStream in, Path targetDir, Predicate<String> include) throws IOException {
        try (TarStreamReader tar = new TarStreamReader(in)) {
            TarStreamReader.Entry entry;
            while ((entry = tar.nextEntry()) != null) {
                if (!include.test(entry.name())) {
                    continue;
                }
                Path target = resolveSafely(targetDir, entry.name());
                if (entry.directory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    tar.transferTo(out);
                }
            }
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
