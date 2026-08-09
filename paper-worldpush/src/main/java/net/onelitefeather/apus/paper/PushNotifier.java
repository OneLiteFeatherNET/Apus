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
 * Reports a completed push cycle to the Apus API ({@code POST /api/push/{token}}, design spec
 * §11.1), so the {@code push} ingest connector knows a new version is waiting in the staging
 * prefix. Kept as its own interface so {@link PushCycleRunner} can be tested with a fake instead
 * of a real HTTP call -- see {@link HttpPushNotifier} for the real implementation.
 */
public interface PushNotifier {

    /**
     * Reports {@code summary} as a completed push. Implementations are expected to throw on
     * failure (network error, non-2xx response) rather than swallow it -- {@link PushCycleRunner}
     * relies on that to decide whether the cycle's state may be persisted as "done".
     */
    void notifyPushComplete(PushSummary summary);
}
