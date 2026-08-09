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
package net.onelitefeather.apus.api.rest.worldsource;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import net.onelitefeather.apus.api.rest.support.ConditionResponse;
import net.onelitefeather.apus.operator.api.WorldSource;

/**
 * A {@link WorldSource}, as {@code /api/sources} exposes it. Deliberately omits {@code
 * s3.credentialsSecretRef}/{@code pterodactyl.credentialsSecretRef} entirely -- those are Secret
 * *names*, and the brief is explicit that no response may carry one, even though a name alone is
 * not a credential's value (see task-2-brief.md / the design plan's tenant-isolation section).
 */
@Serdeable
public record WorldSourceResponse(
        String name,
        String type,
        String poll,
        List<WorldSelectorResponse> worlds,
        int keepVersions,
        String lastSeenVersion,
        BundleResponse latestBundle,
        String lastPollTime,
        List<ConditionResponse> conditions) {

    public static WorldSourceResponse from(WorldSource source) {
        var spec = source.getSpec();
        var status = source.getStatus();
        List<WorldSelectorResponse> worlds = spec.getWorlds().stream()
                .map(w -> new WorldSelectorResponse(w.getName(), w.getLayout(), w.getMinecraftVersion()))
                .toList();
        BundleResponse latestBundle = status.getLatestBundle() == null
                ? null
                : new BundleResponse(
                        status.getLatestBundle().getPath(), status.getLatestBundle().getVersion());
        return new WorldSourceResponse(
                source.getMetadata().getName(),
                spec.getType(),
                spec.getPoll(),
                worlds,
                spec.getRetention().getKeepVersions(),
                status.getLastSeenVersion(),
                latestBundle,
                status.getLastPollTime(),
                status.getConditions().stream().map(ConditionResponse::from).toList());
    }

    @Serdeable
    public record WorldSelectorResponse(String name, String layout, String minecraftVersion) {}

    /** Which bundle version this source last produced -- path and version only. */
    @Serdeable
    public record BundleResponse(String path, String version) {}
}
