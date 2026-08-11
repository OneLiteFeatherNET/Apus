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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Detects how a Minecraft world's files are laid out on disk and resolves each dimension to its
 * region directory.
 *
 * <p>Different ingest sources place the same world's data differently: a vanilla server keeps
 * every dimension nested under one world folder ({@code DIM-1} for the nether, {@code DIM1} for
 * the end), while a Bukkit-family server splits the nether and the end into sibling folders
 * ({@code <world>_nether}, {@code <world>_the_end}). A ZIP upload may additionally wrap the whole
 * thing in one extra top-level directory. This class tells those apart so downstream rendering
 * always points at the correct region files instead of guessing -- if no known layout can be
 * recognized, detection fails loudly rather than picking a plausible-looking wrong answer.
 *
 * <p><b>This class operates on untrusted directory trees.</b> Its input is not first-party data:
 * it is extracted Pterodactyl server backups and user-uploaded ZIP archives, both of which are
 * fully attacker-controlled. A crafted archive may name a world {@code ../../etc}, or contain a
 * symlink such as {@code world/region -> /etc} that a naive walk would happily report as a valid
 * dimension path. Whatever this class returns is later read by the bundle writer and uploaded to
 * S3, so every returned path is verified -- via real-path resolution -- to stay inside the given
 * root, and every directory is checked with {@link LinkOption#NOFOLLOW_LINKS} so symlinks are
 * never silently followed. Do not remove these checks to "simplify" traversal; they are the only
 * thing standing between a hostile archive and reading/writing arbitrary files on the host.
 */
public final class LayoutDetector {

    private static final String KIND_VANILLA = "vanilla";
    private static final String KIND_BUKKIT = "bukkit";

    private static final String DIM_OVERWORLD = "overworld";
    private static final String DIM_THE_NETHER = "the_nether";
    private static final String DIM_THE_END = "the_end";

    private static final String REGION_DIR = "region";
    private static final String NETHER_SUBDIR = "DIM-1";
    private static final String END_SUBDIR = "DIM1";

    private LayoutDetector() {}

    /**
     * Detects the on-disk layout of a world rooted at {@code root}.
     *
     * <p>{@code root} is searched directly first. If neither a vanilla nor a bukkit layout is
     * found there and {@code root} contains exactly one subdirectory, that subdirectory is
     * searched next -- this sees through the extra top-level folder a ZIP upload commonly adds.
     *
     * @param root the directory to search, e.g. an extracted backup or bucket prefix
     * @param worldName the logical world name to look for, e.g. {@code "world"}
     * @param forcedLayout when non-null, only that layout kind ({@code "vanilla"} or
     *     {@code "bukkit"}) is accepted; any other structure fails detection instead of falling
     *     back to a different kind
     * @return the recognized layout with its dimensions resolved to region directories
     * @throws LayoutDetectionException if no known layout can be recognized, if {@code worldName}
     *     is not a single safe path segment, or if resolving a candidate path would escape
     *     {@code root} (whether via a {@code ..} segment or via a symlink)
     */
    public static WorldLayout detect(Path root, String worldName, String forcedLayout) {
        validateWorldName(worldName);
        Path realRoot = toRealPathOrFail(root);
        return detect(root, root, realRoot, worldName, forcedLayout);
    }

    /**
     * Rejects world names that are not a single, literal path segment.
     *
     * <p>The world name comes from a tenant-supplied custom resource and is used verbatim to
     * build filesystem paths. Without this check a name such as {@code ../../etc} would let a
     * tenant walk the resolved path straight out of the extracted archive root.
     */
    private static void validateWorldName(String worldName) {
        if (worldName == null || worldName.isEmpty()) {
            throw new LayoutDetectionException("World name must not be null or empty.");
        }
        if (worldName.contains("/") || worldName.contains("\\")) {
            throw new LayoutDetectionException(
                    "World name '" + worldName + "' must not contain path separators.");
        }
        if (worldName.equals(".") || worldName.equals("..")) {
            throw new LayoutDetectionException(
                    "World name '" + worldName + "' must not be a relative path segment.");
        }
    }

    private static Path toRealPathOrFail(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new LayoutDetectionException("Could not resolve real path of '" + path + "': " + e.getMessage());
        }
    }

    private static WorldLayout detect(
            Path searchRoot, Path originalRoot, Path realRoot, String worldName, String forcedLayout) {
        Optional<WorldLayout> vanilla = detectVanilla(realRoot, searchRoot, worldName);
        Optional<WorldLayout> bukkit = detectBukkit(realRoot, searchRoot, worldName);

        Optional<WorldLayout> match = select(vanilla, bukkit, forcedLayout);
        if (match.isPresent()) {
            return match.get();
        }

        Optional<Path> nestedChild = singleSubdirectory(realRoot, searchRoot);
        if (nestedChild.isPresent()) {
            return detect(nestedChild.get(), originalRoot, realRoot, worldName, forcedLayout);
        }

        throw new LayoutDetectionException(failureMessage(originalRoot, searchRoot, worldName, forcedLayout));
    }

    private static Optional<WorldLayout> select(
            Optional<WorldLayout> vanilla, Optional<WorldLayout> bukkit, String forcedLayout) {
        if (forcedLayout != null) {
            return switch (forcedLayout) {
                case KIND_VANILLA -> vanilla;
                case KIND_BUKKIT -> bukkit;
                default -> throw new LayoutDetectionException("Unknown forced layout '" + forcedLayout
                        + "', expected '" + KIND_VANILLA + "' or '" + KIND_BUKKIT + "'.");
            };
        }
        if (vanilla.isPresent() && bukkit.isPresent()) {
            // Both structurally match on the shared overworld path; the layout that accounts for
            // more dimensions is the one that actually explains the directory tree.
            return bukkit.get().dimensions().size() > vanilla.get().dimensions().size() ? bukkit : vanilla;
        }
        return vanilla.isPresent() ? vanilla : bukkit;
    }

    private static Optional<WorldLayout> detectVanilla(Path realRoot, Path searchRoot, String worldName) {
        Path worldDir = searchRoot.resolve(worldName);
        Path overworld = worldDir.resolve(REGION_DIR);
        if (!isRegionDir(realRoot, overworld)) {
            return Optional.empty();
        }
        Map<String, Path> dimensions = new LinkedHashMap<>();
        dimensions.put(DIM_OVERWORLD, overworld);
        putIfRegionDir(realRoot, dimensions, DIM_THE_NETHER, worldDir.resolve(NETHER_SUBDIR).resolve(REGION_DIR));
        putIfRegionDir(realRoot, dimensions, DIM_THE_END, worldDir.resolve(END_SUBDIR).resolve(REGION_DIR));
        return Optional.of(new WorldLayout(KIND_VANILLA, dimensions));
    }

    private static Optional<WorldLayout> detectBukkit(Path realRoot, Path searchRoot, String worldName) {
        Path overworld = searchRoot.resolve(worldName).resolve(REGION_DIR);
        if (!isRegionDir(realRoot, overworld)) {
            return Optional.empty();
        }
        Path netherRegion =
                searchRoot.resolve(worldName + "_nether").resolve(NETHER_SUBDIR).resolve(REGION_DIR);
        Path endRegion =
                searchRoot.resolve(worldName + "_the_end").resolve(END_SUBDIR).resolve(REGION_DIR);
        boolean netherPresent = isRegionDir(realRoot, netherRegion);
        boolean endPresent = isRegionDir(realRoot, endRegion);
        if (!netherPresent && !endPresent) {
            // Nothing here distinguishes this from a vanilla layout; do not claim it as bukkit.
            return Optional.empty();
        }
        Map<String, Path> dimensions = new LinkedHashMap<>();
        dimensions.put(DIM_OVERWORLD, overworld);
        if (netherPresent) {
            dimensions.put(DIM_THE_NETHER, netherRegion);
        }
        if (endPresent) {
            dimensions.put(DIM_THE_END, endRegion);
        }
        return Optional.of(new WorldLayout(KIND_BUKKIT, dimensions));
    }

    private static void putIfRegionDir(Path realRoot, Map<String, Path> dimensions, String key, Path candidate) {
        if (isRegionDir(realRoot, candidate)) {
            dimensions.put(key, candidate);
        }
    }

    /**
     * Returns whether {@code path} is a real, non-symlink directory that resolves to somewhere
     * inside {@code realRoot}.
     *
     * <p>Two independent checks guard against a hostile archive: {@link LinkOption#NOFOLLOW_LINKS}
     * rejects {@code path} outright if it is itself a symlink (even one that would resolve back
     * inside the tree -- convenience does not justify the risk), and the real-path containment
     * check catches an escape introduced by a symlink higher up the chain, e.g. a world folder
     * itself being a symlink to {@code /etc}.
     */
    private static boolean isRegionDir(Path realRoot, Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return isWithinRoot(realRoot, path);
    }

    private static boolean isWithinRoot(Path realRoot, Path path) {
        try {
            return path.toRealPath().startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }

    private static Optional<Path> singleSubdirectory(Path realRoot, Path dir) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> subdirectories = entries
                    .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> isWithinRoot(realRoot, p))
                    .collect(Collectors.toList());
            return subdirectories.size() == 1 ? Optional.of(subdirectories.get(0)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String failureMessage(
            Path originalRoot, Path searchRoot, String worldName, String forcedLayout) {
        StringBuilder message = new StringBuilder();
        message.append("Could not recognize a known world layout for world '")
                .append(worldName)
                .append("' under ")
                .append(originalRoot);
        if (forcedLayout != null) {
            message.append(" (forced layout '").append(forcedLayout).append("')");
        }
        message.append(". Checked ").append(searchRoot).append(", found: ").append(listEntries(searchRoot));
        return message.toString();
    }

    private static String listEntries(Path dir) {
        if (!Files.isDirectory(dir)) {
            return "<not a directory>";
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(Path::toString).sorted().collect(Collectors.joining(", ", "[", "]"));
        } catch (IOException e) {
            return "<could not list " + dir + ": " + e.getMessage() + ">";
        }
    }

    /** Thrown when no known world layout can be recognized under the given root. */
    public static final class LayoutDetectionException extends RuntimeException {

        LayoutDetectionException(String message) {
            super(message);
        }
    }
}
