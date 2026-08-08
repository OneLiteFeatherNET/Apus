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
     * @throws LayoutDetectionException if no known layout can be recognized
     */
    public static WorldLayout detect(Path root, String worldName, String forcedLayout) {
        return detect(root, root, worldName, forcedLayout);
    }

    private static WorldLayout detect(
            Path searchRoot, Path originalRoot, String worldName, String forcedLayout) {
        Optional<WorldLayout> vanilla = detectVanilla(searchRoot, worldName);
        Optional<WorldLayout> bukkit = detectBukkit(searchRoot, worldName);

        Optional<WorldLayout> match = select(vanilla, bukkit, forcedLayout);
        if (match.isPresent()) {
            return match.get();
        }

        Optional<Path> nestedChild = singleSubdirectory(searchRoot);
        if (nestedChild.isPresent()) {
            return detect(nestedChild.get(), originalRoot, worldName, forcedLayout);
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

    private static Optional<WorldLayout> detectVanilla(Path searchRoot, String worldName) {
        Path worldDir = searchRoot.resolve(worldName);
        Path overworld = worldDir.resolve(REGION_DIR);
        if (!isRegionDir(overworld)) {
            return Optional.empty();
        }
        Map<String, Path> dimensions = new LinkedHashMap<>();
        dimensions.put(DIM_OVERWORLD, overworld);
        putIfRegionDir(dimensions, DIM_THE_NETHER, worldDir.resolve(NETHER_SUBDIR).resolve(REGION_DIR));
        putIfRegionDir(dimensions, DIM_THE_END, worldDir.resolve(END_SUBDIR).resolve(REGION_DIR));
        return Optional.of(new WorldLayout(KIND_VANILLA, dimensions));
    }

    private static Optional<WorldLayout> detectBukkit(Path searchRoot, String worldName) {
        Path overworld = searchRoot.resolve(worldName).resolve(REGION_DIR);
        if (!isRegionDir(overworld)) {
            return Optional.empty();
        }
        Path netherRegion =
                searchRoot.resolve(worldName + "_nether").resolve(NETHER_SUBDIR).resolve(REGION_DIR);
        Path endRegion =
                searchRoot.resolve(worldName + "_the_end").resolve(END_SUBDIR).resolve(REGION_DIR);
        boolean netherPresent = isRegionDir(netherRegion);
        boolean endPresent = isRegionDir(endRegion);
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

    private static void putIfRegionDir(Map<String, Path> dimensions, String key, Path candidate) {
        if (isRegionDir(candidate)) {
            dimensions.put(key, candidate);
        }
    }

    private static boolean isRegionDir(Path path) {
        return Files.isDirectory(path);
    }

    private static Optional<Path> singleSubdirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> subdirectories = entries.filter(Files::isDirectory).collect(Collectors.toList());
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
