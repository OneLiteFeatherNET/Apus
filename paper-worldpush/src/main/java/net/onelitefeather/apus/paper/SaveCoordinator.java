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

/**
 * The three save-related steps a push cycle needs from the live world, kept as their own
 * interface so {@link PushCycleRunner} can be exercised without a running Paper server -- the
 * copy logic itself is Bukkit-free, but the "pause autosave, force one save, resume autosave"
 * sequence around it is inherently Bukkit-API-shaped, and PaperMC's guidance is that all such
 * calls belong on the main thread. See {@link BukkitSaveCoordinator} for the real, main-thread
 * bridging implementation, and the phase 6 task report for why that implementation itself has no
 * automated test -- it needs a live server.
 *
 * <p>Every method is expected to block the calling thread until the underlying main-thread step
 * has actually completed (not merely been scheduled), so that {@link PushCycleRunner} can treat
 * this as a simple synchronous sequence despite the work happening on a different thread.
 */
public interface SaveCoordinator {

    /** Disables the world's automatic periodic saving, so it cannot race the forced save below. */
    void disableAutoSave();

    /** Forces one synchronous save of the world's current state to disk. */
    void forceSave();

    /** Re-enables automatic periodic saving. Always called, even if {@link #forceSave()} failed. */
    void enableAutoSave();
}
