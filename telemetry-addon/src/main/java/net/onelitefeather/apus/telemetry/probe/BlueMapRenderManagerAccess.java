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
package net.onelitefeather.apus.telemetry.probe;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.common.plugin.Plugin;
import de.bluecolored.bluemap.common.rendermanager.MapRenderTask;
import de.bluecolored.bluemap.common.rendermanager.RenderManager;
import de.bluecolored.bluemap.common.rendermanager.RenderTask;

/**
 * Adapts BlueMap's internal {@code RenderManager} to {@link RenderManagerAccess}.
 *
 * <p>The route used here is the one BlueMap documents for addons: {@code BlueMapAPIImpl}
 * exposes {@code plugin()} with a javadoc comment explicitly recommending it for addons
 * that depend on BlueMapCommon, and {@code Plugin} exposes {@code getRenderManager()}.
 * No reflection is involved.
 */
public final class BlueMapRenderManagerAccess implements RenderManagerAccess {

    private final RenderManager renderManager;

    private BlueMapRenderManagerAccess(RenderManager renderManager) {
        this.renderManager = renderManager;
    }

    /**
     * @return an access instance, or {@code null} when this platform exposes no plugin
     *         (in which case there is no internal render manager to read)
     */
    public static BlueMapRenderManagerAccess createOrNull(BlueMapAPI api) {
        if (!(api instanceof BlueMapAPIImpl impl)) {
            return null;
        }
        Plugin plugin = impl.plugin();
        if (plugin == null) {
            return null;
        }
        RenderManager renderManager = plugin.getRenderManager();
        return renderManager == null ? null : new BlueMapRenderManagerAccess(renderManager);
    }

    @Override
    public boolean isRunning() {
        return renderManager.isRunning();
    }

    @Override
    public int queuedTasks() {
        return renderManager.getScheduledRenderTaskCount();
    }

    @Override
    public int renderThreads() {
        return renderManager.getWorkerThreadCount();
    }

    @Override
    public TaskInfo currentTask() {
        RenderTask task = renderManager.getCurrentRenderTask();
        if (task == null) {
            return null;
        }
        String mapId = null;
        if (task instanceof MapRenderTask mapTask && mapTask.getMap() != null) {
            mapId = mapTask.getMap().getId();
        }
        return new TaskInfo(
                mapId,
                task.getDescription(),
                task.estimateProgress(),
                renderManager.estimateCurrentRenderTaskTimeRemaining());
    }
}
