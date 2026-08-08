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
package net.onelitefeather.apus.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes one version of a world bundle to S3.
 *
 * <p>Every region file of every dimension is uploaded first; {@link BundleManifest} is uploaded
 * last, and only once every region file has succeeded. S3 offers no cross-object transaction, so
 * this write order is what makes the manifest the bundle's commit point: a reader that finds the
 * manifest can trust every region file it references already exists, and a write that fails
 * partway through never leaves a manifest behind for a bundle version that isn't actually
 * complete -- there is nothing to roll back, because the one object that matters was never
 * written.
 */
public final class BundleWriter {

    private static final Pattern REGION_FILE_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int SCHEMA_VERSION = 1;
    private static final String DIGEST_ALGORITHM = "SHA-256";

    private final S3Client s3;
    private final String bucket;

    public BundleWriter(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    /**
     * A narrow view of a detected world layout: just enough for {@link BundleWriter} to write
     * one, without depending on the layout-detection module directly. The record the layout
     * detector produces is expected to satisfy this interface.
     */
    public interface WorldLayoutLike {
        /** The detected layout kind, e.g. {@code "vanilla"} or {@code "bukkit"}. */
        String kind();

        /** Logical dimension name (e.g. {@code "overworld"}) to its region directory. */
        Map<String, Path> dimensions();
    }

    /** Receives progress updates while a bundle is being written. */
    public interface ProgressSink {
        /**
         * Called after each region file finishes uploading.
         *
         * @param bytesDone bytes uploaded so far
         * @param bytesTotal total bytes to upload, known upfront from the region files on disk
         */
        void update(long bytesDone, long bytesTotal);
    }

    /**
     * Writes every region file of {@code layout}'s dimensions, then the manifest describing them
     * as the bundle's last object.
     *
     * @return the bundle's root path within the bucket, {@code <tenant>/<worldId>/<version>}
     */
    public String write(
            String tenant, String worldId, String version, WorldLayoutLike layout, ProgressSink progress) {
        String bundlePath = tenant + "/" + worldId + "/" + version;

        Map<String, List<RegionFile>> filesByDimension = new LinkedHashMap<>();
        long totalBytes = 0;
        for (Map.Entry<String, Path> entry : layout.dimensions().entrySet()) {
            List<RegionFile> files = listRegionFiles(entry.getValue());
            filesByDimension.put(entry.getKey(), files);
            for (RegionFile file : files) {
                totalBytes += file.sizeBytes();
            }
        }

        MessageDigest digest = newDigest();
        List<BundleManifest.DimensionInfo> dimensionInfos = new ArrayList<>();
        long bytesDone = 0;
        long sizeBytes = 0;

        for (Map.Entry<String, List<RegionFile>> entry : filesByDimension.entrySet()) {
            String dimensionId = entry.getKey();
            String dimensionPath = bundlePath + "/dimensions/" + dimensionId;
            List<int[]> regions = new ArrayList<>();
            for (RegionFile file : entry.getValue()) {
                byte[] content = readFully(file.path());
                digest.update(content);
                s3.putObject(bucket, dimensionPath + "/region/" + file.path().getFileName(), content);
                regions.add(new int[] {file.x(), file.z()});
                sizeBytes += content.length;
                bytesDone += content.length;
                if (progress != null) {
                    progress.update(bytesDone, totalBytes);
                }
            }
            dimensionInfos.add(
                    new BundleManifest.DimensionInfo(dimensionId, dimensionPath, regions, regions.size()));
        }

        BundleManifest manifest = new BundleManifest(
                SCHEMA_VERSION,
                tenant,
                worldId,
                version,
                new BundleManifest.SourceInfo(null, version, layout.kind()),
                null,
                dimensionInfos,
                sizeBytes,
                new BundleManifest.Checksums(DIGEST_ALGORITHM, toHex(digest.digest())));

        // The manifest is the commit point: written last, and only after every region file
        // above succeeded. If any putObject or file read above threw, execution never reaches
        // this line, and no manifest exists for this bundle version.
        s3.putObject(bucket, bundlePath + "/manifest.json", manifest.toJson().getBytes(StandardCharsets.UTF_8));

        return bundlePath;
    }

    private record RegionFile(Path path, int x, int z, long sizeBytes) {}

    private static List<RegionFile> listRegionFiles(Path regionDir) {
        List<RegionFile> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mca")) {
            for (Path candidate : stream) {
                Matcher matcher = REGION_FILE_NAME.matcher(candidate.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                int x = Integer.parseInt(matcher.group(1));
                int z = Integer.parseInt(matcher.group(2));
                files.add(new RegionFile(candidate, x, z, Files.size(candidate)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list region files in " + regionDir, e);
        }
        files.sort(Comparator.<RegionFile>comparingInt(RegionFile::x).thenComparingInt(RegionFile::z));
        return files;
    }

    private static byte[] readFully(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read region file " + path, e);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
