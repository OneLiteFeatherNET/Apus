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
package net.onelitefeather.apus.paper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Copies the region files of a running world into a local staging directory, touching only files
 * that actually changed since the last run.
 *
 * <p><b>Why incremental at all.</b> Copying an entire world's region files on every push cycle is
 * the safe default but does not scale: for a large world it can take long enough that keeping the
 * server's autosave paused for the whole copy (see {@link SaveCoordinator}) would make the server
 * noticeably stutter. Restricting the copy to changed files keeps the disruptive part -- the
 * brief autosave pause plus one forced save -- short regardless of world size; only the copy
 * itself scales with how much actually changed, and that step runs off the main thread anyway.
 *
 * <p><b>What "changed" means.</b> Two signals, used together, exactly as the design brief
 * specifies ("only region files with a changed modification time or checksum"):
 *
 * <ol>
 *   <li>{@code size}/{@code mtime} is the cheap first check (a single {@code stat}, no file
 *       content is read). If both match the last recorded {@link RegionFileState}, the file is
 *       skipped outright -- this is what keeps a cycle over an untouched world near-instant.
 *   <li>If either differs, the file's SHA-256 checksum is computed and compared against the last
 *       recorded one. Only a genuine checksum mismatch causes an actual copy; a file whose mtime
 *       moved but whose bytes did not (a `touch`, a filesystem quirk, a region file rewritten with
 *       identical content) updates its recorded {@code size}/{@code mtime} for next time without
 *       being re-uploaded.
 * </ol>
 *
 * <p><b>Crash safety.</b> Each file is copied to a temporary file in the same destination
 * directory (guaranteeing the same filesystem) and then moved onto its final name with {@link
 * StandardCopyOption#ATOMIC_MOVE}. A reader of the staging directory therefore only ever sees a
 * region file as either fully absent or fully present at its final size -- never truncated or
 * half-written. If the copy of one file fails, its temporary file is deleted and the exception
 * propagates; files already moved into place before the failure stay in place (there is no
 * transactional rollback across the whole batch -- and none is needed, since each file's own
 * atomicity is what matters: a caller that retries the whole cycle later will simply find those
 * files unchanged next time via the check above). {@link #copyChanged} does not persist {@code
 * state} itself -- see {@link CopyState}'s own Javadoc for why leaving that to the caller, once
 * per successful cycle, is what makes the persisted state crash-safe too.
 */
public final class IncrementalWorldCopier {

    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final String REGION_FILE_SUFFIX = ".mca";

    /**
     * Copies every changed {@code *.mca} file from {@code regionDirs} into {@code stagingRoot},
     * updating {@code state} in place for every file examined (changed or not). Does not persist
     * {@code state}; the caller does that once the whole cycle -- across all dimensions -- has
     * completed successfully.
     *
     * @throws IOException if listing a region directory or copying a file fails; already-copied
     *     files from earlier in this call remain in {@code stagingRoot} (see the class Javadoc)
     */
    public CopyResult copyChanged(List<DimensionRegionDir> regionDirs, Path stagingRoot, CopyState state)
            throws IOException {
        List<String> copied = new ArrayList<>();
        long copiedBytes = 0;
        int unchanged = 0;

        for (DimensionRegionDir dimension : regionDirs) {
            for (Path sourceFile : listRegionFiles(dimension.sourceDir())) {
                String relativePath = dimension.relativePrefix() + "/" + sourceFile.getFileName();
                long size = Files.size(sourceFile);
                long lastModifiedMillis = Files.getLastModifiedTime(sourceFile).toMillis();

                RegionFileState prior = state.get(relativePath);
                if (prior != null && prior.size() == size && prior.lastModifiedMillis() == lastModifiedMillis) {
                    unchanged++;
                    continue;
                }

                String checksum = sha256(sourceFile);
                if (prior != null && prior.checksum().equals(checksum)) {
                    // Same content, only the timestamp moved -- refresh the cheap-check fields
                    // so the next cycle takes the fast path again, but do not copy or upload.
                    state.put(relativePath, new RegionFileState(size, lastModifiedMillis, checksum));
                    unchanged++;
                    continue;
                }

                copyAtomically(sourceFile, stagingRoot.resolve(relativePath));
                state.put(relativePath, new RegionFileState(size, lastModifiedMillis, checksum));
                copied.add(relativePath);
                copiedBytes += size;
            }
        }

        return new CopyResult(List.copyOf(copied), copiedBytes, unchanged);
    }

    private static List<Path> listRegionFiles(Path regionDir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream =
                Files.newDirectoryStream(regionDir, entry -> Files.isRegularFile(entry)
                        && entry.getFileName().toString().endsWith(REGION_FILE_SUFFIX))) {
            for (Path entry : stream) {
                files.add(entry);
            }
        }
        files.sort(Path::compareTo);
        return files;
    }

    private static void copyAtomically(Path sourceFile, Path destination) throws IOException {
        Path destinationDir = destination.toAbsolutePath().getParent();
        Files.createDirectories(destinationDir);
        Path tmp = Files.createTempFile(destinationDir, destination.getFileName().toString(), ".tmp");
        try {
            Files.copy(sourceFile, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // Every JDK ships SHA-256; see java.security.MessageDigest's own guarantee.
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[8192];
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            while (in.read(buffer) != -1) {
                // DigestInputStream updates the digest as a side effect of reading.
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
