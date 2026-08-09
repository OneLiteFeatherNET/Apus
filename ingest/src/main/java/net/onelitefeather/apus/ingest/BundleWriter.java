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

    /** Sibling directories of a dimension's region directory that are part of the bundle when present. */
    private static final String ENTITIES_DIR = "entities";

    private static final String POI_DIR = "poi";
    private static final String LEVEL_DAT = "level.dat";

    /** The dimension whose region directory's parent holds the world's {@code level.dat}. */
    private static final String OVERWORLD_DIMENSION = "overworld";

    /**
     * Writes every region file of {@code layout}'s dimensions (plus, where present, each
     * dimension's {@code entities}/{@code poi} data and the world's {@code level.dat}), then the
     * manifest describing them as the bundle's last object.
     *
     * <p>{@code sourceType} and {@code minecraftVersion} are opaque to this class -- neither the
     * layout detector nor the bundle writer has any way to know where the data came from or which
     * Minecraft version produced it. Only the orchestrator ({@code IngestMain}) knows both, so it
     * passes them straight through into {@link BundleManifest#source()} and {@link
     * BundleManifest#minecraftVersion()}.
     *
     * <p>{@code level.dat} is read from the overworld dimension's region directory's parent (the
     * world root every detected layout resolves the overworld's {@code region/} directory
     * under -- see {@code LayoutDetector}), since that copy is the one describing the world as a
     * whole; the nether/end folders a Bukkit-style split layout produces each carry their own
     * {@code level.dat} too, but those are per-dimension placeholders, not the world's actual
     * level data.
     *
     * @param sourceName the {@code WorldSource} this bundle was produced from, by name -- scopes
     *     the bundle path so two sources whose worlds happen to share a {@code worldId} (e.g. the
     *     Minecraft default {@code "world"}) never collide on the same prefix; see {@link
     *     BundlePath}
     * @param sourceType the source connector type (e.g. {@code "s3"}, {@code "pterodactyl"}), or
     *     {@code null} if not known to the caller
     * @param sourceRef an identifier for the exact source version this bundle was produced from
     *     (e.g. a Pterodactyl backup UUID or an S3 object key) -- distinct from {@code version},
     *     which is the *bundle's own* version identifier, not where it came from
     * @param minecraftVersion the Minecraft version the world was generated/played under, or
     *     {@code null} if not known to the caller
     * @return the bundle's root path within the bucket, {@code <tenant>/<sourceName>/<worldId>/<version>}
     */
    public String write(
            String tenant,
            String sourceName,
            String worldId,
            String version,
            String sourceType,
            String sourceRef,
            String minecraftVersion,
            WorldLayoutLike layout,
            ProgressSink progress) {
        String bundlePath = BundlePath.of(tenant, sourceName, worldId, version);

        Map<String, List<RegionFile>> filesByDimension = new LinkedHashMap<>();
        Map<String, List<RegionFile>> entityFilesByDimension = new LinkedHashMap<>();
        Map<String, List<RegionFile>> poiFilesByDimension = new LinkedHashMap<>();
        long totalBytes = 0;
        for (Map.Entry<String, Path> entry : layout.dimensions().entrySet()) {
            Path regionDir = entry.getValue();
            List<RegionFile> files = listRegionFiles(regionDir);
            filesByDimension.put(entry.getKey(), files);
            for (RegionFile file : files) {
                totalBytes += file.sizeBytes();
            }

            List<RegionFile> entityFiles = listRegionFilesIfPresent(regionDir.resolveSibling(ENTITIES_DIR));
            entityFilesByDimension.put(entry.getKey(), entityFiles);
            for (RegionFile file : entityFiles) {
                totalBytes += file.sizeBytes();
            }

            List<RegionFile> poiFiles = listRegionFilesIfPresent(regionDir.resolveSibling(POI_DIR));
            poiFilesByDimension.put(entry.getKey(), poiFiles);
            for (RegionFile file : poiFiles) {
                totalBytes += file.sizeBytes();
            }
        }

        Path levelDat = levelDatPath(layout);
        long levelDatSize = levelDat != null && Files.isRegularFile(levelDat) ? sizeOrZero(levelDat) : 0;
        totalBytes += levelDatSize;

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

            bytesDone = writeSidecarFiles(
                    dimensionPath + "/" + ENTITIES_DIR,
                    entityFilesByDimension.get(dimensionId),
                    digest,
                    progress,
                    bytesDone,
                    totalBytes);
            bytesDone = writeSidecarFiles(
                    dimensionPath + "/" + POI_DIR,
                    poiFilesByDimension.get(dimensionId),
                    digest,
                    progress,
                    bytesDone,
                    totalBytes);
        }

        if (levelDat != null && levelDatSize > 0) {
            byte[] content = readFully(levelDat);
            digest.update(content);
            s3.putObject(bucket, bundlePath + "/" + LEVEL_DAT, content);
            sizeBytes += content.length;
            bytesDone += content.length;
            if (progress != null) {
                progress.update(bytesDone, totalBytes);
            }
        }

        BundleManifest manifest = new BundleManifest(
                SCHEMA_VERSION,
                tenant,
                worldId,
                version,
                new BundleManifest.SourceInfo(sourceType, sourceRef, layout.kind()),
                minecraftVersion,
                dimensionInfos,
                sizeBytes,
                new BundleManifest.Checksums(DIGEST_ALGORITHM, toHex(digest.digest())));

        // The manifest is the commit point: written last, and only after every region file
        // above succeeded. If any putObject or file read above threw, execution never reaches
        // this line, and no manifest exists for this bundle version.
        s3.putObject(bucket, bundlePath + "/manifest.json", manifest.toJson().getBytes(StandardCharsets.UTF_8));

        return bundlePath;
    }

    /** {@code null} if the layout has no overworld dimension (should not happen for a detected layout). */
    private static Path levelDatPath(WorldLayoutLike layout) {
        Path overworldRegion = layout.dimensions().get(OVERWORLD_DIMENSION);
        return overworldRegion == null ? null : overworldRegion.resolveSibling(LEVEL_DAT);
    }

    private static long sizeOrZero(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to size " + path, e);
        }
    }

    /** Uploads {@code files} (entities or poi region files) under {@code targetPrefix}, updating progress as it goes. */
    private long writeSidecarFiles(
            String targetPrefix,
            List<RegionFile> files,
            MessageDigest digest,
            ProgressSink progress,
            long bytesDone,
            long totalBytes) {
        if (files == null) {
            return bytesDone;
        }
        long done = bytesDone;
        for (RegionFile file : files) {
            byte[] content = readFully(file.path());
            digest.update(content);
            s3.putObject(bucket, targetPrefix + "/" + file.path().getFileName(), content);
            done += content.length;
            if (progress != null) {
                progress.update(done, totalBytes);
            }
        }
        return done;
    }

    private record RegionFile(Path path, int x, int z, long sizeBytes) {}

    /** Same as {@link #listRegionFiles}, but returns an empty list rather than failing when {@code dir} does not exist. */
    private static List<RegionFile> listRegionFilesIfPresent(Path dir) {
        return Files.isDirectory(dir) ? listRegionFiles(dir) : List.of();
    }

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
