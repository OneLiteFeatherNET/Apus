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
package net.onelitefeather.apus.api.rest.map;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import net.onelitefeather.apus.api.rest.support.ConditionResponse;
import net.onelitefeather.apus.operator.api.BlueMapMap;

/**
 * A {@link BlueMapMap}, as {@code /api/maps} exposes it. {@code bucket} carries only the bucket
 * name and endpoint -- never {@code BlueMapMapStatus.Bucket#getSecretName()}, which names the
 * Secret holding that bucket's credentials and is exactly the kind of value task-2-brief.md
 * forbids in a response.
 */
@Serdeable
public record BlueMapMapResponse(
        String name,
        SourceResponse source,
        TriggerResponse trigger,
        BlueMapSettingsResponse bluemap,
        int shards,
        int historyLimit,
        boolean purgeOnDelete,
        BucketResponse bucket,
        LatestRenderResponse latestRender,
        List<ConditionResponse> conditions) {

    public static BlueMapMapResponse from(BlueMapMap map) {
        var spec = map.getSpec();
        var status = map.getStatus();
        var source = spec.getSource();
        var trigger = spec.getTrigger();
        var bluemap = spec.getBluemap();
        var bucket = status.getBucket();
        var latestRender = status.getLatestRender();
        return new BlueMapMapResponse(
                map.getMetadata().getName(),
                new SourceResponse(
                        source.getSourceRef() == null ? null : source.getSourceRef().getName(),
                        source.getWorld(),
                        source.getDimension()),
                new TriggerResponse(trigger.isOnNewBundle(), trigger.getSchedule(), trigger.getConcurrencyPolicy()),
                new BlueMapSettingsResponse(bluemap.getVersion(), bluemap.getMinecraftVersion()),
                spec.getShards(),
                spec.getHistoryLimit(),
                spec.isPurgeOnDelete(),
                new BucketResponse(bucket.getName(), bucket.getEndpoint()),
                new LatestRenderResponse(latestRender.getName(), latestRender.getPhase()),
                status.getConditions().stream().map(ConditionResponse::from).toList());
    }

    @Serdeable
    public record SourceResponse(String sourceRef, String world, String dimension) {}

    @Serdeable
    public record TriggerResponse(boolean onNewBundle, String schedule, String concurrencyPolicy) {}

    @Serdeable
    public record BlueMapSettingsResponse(String version, String minecraftVersion) {}

    /** Bucket name and endpoint only -- never the Secret name holding its credentials. */
    @Serdeable
    public record BucketResponse(String name, String endpoint) {}

    @Serdeable
    public record LatestRenderResponse(String name, String phase) {}
}
