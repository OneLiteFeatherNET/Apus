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
package net.onelitefeather.apus.api.events;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

/**
 * The Loki-vs-Kubernetes-client decision itself (task 3 report has the full reasoning): presence
 * of a configured Loki URL picks {@link LokiLogSource}; its absence falls back to {@link
 * KubernetesPodLogSource}. Neither implementation is exercised here against a live backend --
 * only which one gets chosen.
 */
class LogSourceFactoryTest {

    @Test
    void picksLokiWhenAUrlIsConfigured() {
        LogSource logSource = LogSourceFactory.select("http://loki.observability.svc:3100", null);

        assertInstanceOf(LokiLogSource.class, logSource);
    }

    @Test
    void fallsBackToTheKubernetesClientWhenNoUrlIsConfigured() {
        LogSource logSource = LogSourceFactory.select("", null);

        assertInstanceOf(KubernetesPodLogSource.class, logSource);
    }

    @Test
    void fallsBackToTheKubernetesClientWhenTheUrlIsNull() {
        LogSource logSource = LogSourceFactory.select(null, null);

        assertInstanceOf(KubernetesPodLogSource.class, logSource);
    }

    @Test
    void fallsBackToTheKubernetesClientWhenTheUrlIsBlank() {
        LogSource logSource = LogSourceFactory.select("   ", null);

        assertInstanceOf(KubernetesPodLogSource.class, logSource);
    }
}
