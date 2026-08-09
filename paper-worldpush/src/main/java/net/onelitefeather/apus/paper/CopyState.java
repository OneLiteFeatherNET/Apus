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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The persisted record of every region file {@link IncrementalWorldCopier} has seen, keyed by its
 * relative path (e.g. {@code "world/region/r.0.0.mca"}). This is what makes a push cycle
 * incremental across separate runs -- without it, every region file would look "new" every time.
 *
 * <p>Stored as a flat {@code key=size:mtime:checksum} properties file rather than a structured
 * format: it is written and read only by this class, never by a human or another tool, so there
 * is nothing a richer format would buy here that a one-line-per-entry format doesn't already
 * give for free (human-readable, trivially diffable, zero extra dependency).
 *
 * <p><b>Crash safety.</b> {@link #save(Path)} writes to a sibling temporary file and atomically
 * renames it onto the target -- readers of the state file (the next push cycle) therefore only
 * ever see either the previous complete state or the new complete state, never a half-written
 * one. A crash before {@link #save(Path)} is called simply means the next cycle re-checksums
 * (and, if actually changed, re-copies) a few files it did not strictly need to -- redundant
 * work, never data loss or a corrupt state file. See {@link IncrementalWorldCopier} for the
 * matching guarantee on the copy side.
 */
public final class CopyState {

    private static final Logger LOGGER = Logger.getLogger(CopyState.class.getName());

    private final Map<String, RegionFileState> entries;

    private CopyState(Map<String, RegionFileState> entries) {
        this.entries = entries;
    }

    /** An empty state, as used before the very first push cycle. */
    public static CopyState empty() {
        return new CopyState(new HashMap<>());
    }

    /**
     * Loads state from {@code stateFile}. Returns an empty state (never throws) if the file does
     * not exist yet, or if it exists but cannot be parsed -- a corrupt state file must never
     * block push cycles from running; worst case it costs one fully non-incremental cycle.
     */
    public static CopyState load(Path stateFile) {
        if (!Files.isRegularFile(stateFile)) {
            return empty();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(stateFile)) {
            properties.load(in);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not read push state file " + stateFile + ", starting with empty state.", e);
            return empty();
        }
        Map<String, RegionFileState> entries = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            RegionFileState state = RegionFileState.decode(properties.getProperty(key));
            if (state != null) {
                entries.put(key, state);
            }
        }
        return new CopyState(entries);
    }

    /** The recorded state for {@code relativePath}, or {@code null} if this file has never been seen. */
    public RegionFileState get(String relativePath) {
        return entries.get(relativePath);
    }

    /** Records (or replaces) the state for {@code relativePath}. */
    public void put(String relativePath, RegionFileState state) {
        entries.put(relativePath, state);
    }

    /** The number of region files currently tracked. */
    public int size() {
        return entries.size();
    }

    /**
     * Persists this state to {@code stateFile}, atomically. See the class Javadoc for the crash
     * safety this provides.
     */
    public void save(Path stateFile) throws IOException {
        Path parent = stateFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties properties = new Properties();
        for (Map.Entry<String, RegionFileState> entry : entries.entrySet()) {
            properties.setProperty(entry.getKey(), entry.getValue().encode());
        }

        Path tmp = Files.createTempFile(parent, stateFile.getFileName().toString(), ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                properties.store(out, "Apus world push -- region file copy state. Do not edit by hand.");
            }
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
