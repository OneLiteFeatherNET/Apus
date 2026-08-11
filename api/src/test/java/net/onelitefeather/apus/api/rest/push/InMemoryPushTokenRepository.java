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
package net.onelitefeather.apus.api.rest.push;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link PushTokenRepository} fake for {@code PushControllerTest} -- a plain map is
 * enough here (unlike {@link FabricPushTokenRepository}) since proving the constant-time,
 * exhaustive-scan comparison itself is that class's own test's job, not the controller's.
 */
final class InMemoryPushTokenRepository implements PushTokenRepository {

    private final Map<String, String> tokenToNamespace = new HashMap<>();

    void put(String token, String namespace) {
        tokenToNamespace.put(token, namespace);
    }

    @Override
    public Optional<String> resolveNamespace(String rawToken) {
        return Optional.ofNullable(rawToken == null ? null : tokenToNamespace.get(rawToken));
    }
}
