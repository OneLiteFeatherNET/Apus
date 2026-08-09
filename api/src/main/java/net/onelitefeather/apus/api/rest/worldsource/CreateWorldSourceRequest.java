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

/**
 * Request body for {@code POST /api/sources}. Unlike {@link WorldSourceResponse}, this request
 * *does* carry {@code credentialsSecretName} for the S3/Pterodactyl connection types -- the
 * caller is naming a Secret they already created in their own namespace, not something this API
 * discloses back to them (the no-secret-names rule in task-2-brief.md is about responses).
 * {@code credentialsSecretName} becomes a {@code Ref} in the caller's own namespace only,
 * exactly like every other reference in this data model (design spec §10.1).
 */
@Serdeable
public record CreateWorldSourceRequest(
        String name,
        String type,
        S3Request s3,
        PterodactylRequest pterodactyl,
        String poll,
        List<WorldSelectorRequest> worlds,
        Integer keepVersions) {

    @Serdeable
    public record S3Request(String endpoint, String bucket, String prefix, String credentialsSecretName) {}

    @Serdeable
    public record PterodactylRequest(
            String panelUrl, String serverId, String credentialsSecretName, String select) {}

    @Serdeable
    public record WorldSelectorRequest(String name, String layout, String minecraftVersion) {}
}
