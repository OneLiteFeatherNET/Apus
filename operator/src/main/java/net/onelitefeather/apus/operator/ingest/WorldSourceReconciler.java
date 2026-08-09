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
package net.onelitefeather.apus.operator.ingest;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.onelitefeather.apus.ingest.connector.PterodactylConnector;
import net.onelitefeather.apus.ingest.connector.S3SourceConnector;
import net.onelitefeather.apus.ingest.connector.SourceVersion;
import net.onelitefeather.apus.ingest.connector.WorldSourceConnector;
import net.onelitefeather.apus.operator.api.Conditions;
import net.onelitefeather.apus.operator.api.Labels;
import net.onelitefeather.apus.operator.api.WorldIngest;
import net.onelitefeather.apus.operator.api.WorldSource;

/**
 * Evaluates {@code WorldSource.spec.poll} against {@code status.lastSeenVersion} and, when the
 * source reports a version not seen before, creates one {@link WorldIngest} per configured world
 * to pull it.
 *
 * <p><b>Only pull sources are pollable.</b> {@code s3} and {@code pterodactyl} report their own
 * available versions via {@link WorldSourceConnector#discover}; {@code upload}/{@code push} have
 * no connector yet (phase 6 -- see {@code ingest/README.md}) and are always treated as manual
 * only, regardless of whether {@code spec.poll} happens to be set.
 *
 * <p><b>{@code discover()} runs here, not inside the ingest Job.</b> This mirrors {@code
 * ingest/README.md}'s own "Design notes" section: a single ingest run only ever fetches the one
 * version it was told to (deterministic, reproducible), and "is there anything new" is resolved
 * exactly once, here, on a schedule.
 *
 * <p><b>Idempotent by construction.</b> The {@link WorldIngest} created for a given (source,
 * world, discovered version) triple always gets the same deterministic name ({@link
 * #ingestNameFor}); a reconcile that runs again after already having created it for the current
 * {@code lastSeenVersion} finds nothing new to do (the version comparison short-circuits before
 * ever calling {@link #ingestNameFor}), and even a retry mid-way through creating several worlds'
 * ingests just re-attempts the ones it hasn't created yet -- creating an object that already
 * exists is a deliberate no-op here, not an error, exactly like {@code createOr(update)}
 * elsewhere in this module is idempotent by design.
 *
 * <p><b>Ownership check</b>, mirroring {@code BlueMapMapReconciler}: before this reconciler ever
 * treats an existing {@link WorldIngest} of the name it is about to create as "already
 * triggered", it checks that resource's labels for one naming this exact source by name
 * <em>and</em> UID. A mismatch means the name collided with something unrelated; that world is
 * skipped and the source is left with a {@code ResourceConflict} condition instead of silently
 * treating a foreign resource as its own ingest run.
 */
@ControllerConfiguration
public class WorldSourceReconciler implements Reconciler<WorldSource> {

    /** Reason set on the {@code Ready} condition for a source with no {@code spec.poll} (or an unpollable type). */
    public static final String MANUAL_ONLY_REASON = "ManualOnly";

    /** Reason set on the {@code Ready} condition when {@code spec.poll} is not a valid Cron expression. */
    public static final String INVALID_POLL_REASON = "InvalidPollExpression";

    /** Reason set on the {@code Ready} condition when no world is configured to ingest. */
    public static final String NO_WORLDS_CONFIGURED_REASON = "NoWorldsConfigured";

    /** Reason set on the {@code Ready} condition when the source's connector reported an error. */
    public static final String DISCOVERY_FAILED_REASON = "SourceDiscoveryFailed";

    /** Reason set on the {@code Ready} condition when the latest discovered version is already ingested. */
    public static final String UP_TO_DATE_REASON = "UpToDate";

    /** Reason set on the {@code Ready} condition once a new version triggered at least one {@link WorldIngest}. */
    public static final String INGEST_TRIGGERED_REASON = "IngestTriggered";

    /** Reason set on the {@code Ready} condition when a deterministic ingest name collided with a foreign resource. */
    public static final String RESOURCE_CONFLICT_REASON = "ResourceConflict";

    private static final Set<String> POLLABLE_TYPES = Set.of("s3", "pterodactyl");

    private static final String TYPE_S3 = "s3";
    private static final String TYPE_PTERODACTYL = "pterodactyl";

    /** API group + version the owning {@link WorldSource} is served under. */
    private static final String OWNER_API_VERSION = "bluemap.onelitefeather.net/v1alpha1";

    private static final String OWNER_KIND = "WorldSource";

    private final KubernetesClient client;
    private final ConnectorResolver connectorResolver;
    private final Clock clock;

    public WorldSourceReconciler(KubernetesClient client) {
        this(client, WorldSourceReconciler::defaultConnector, Clock.systemUTC());
    }

    /** Test seam: a fake connector/clock make discovery and cron due-ness deterministic. */
    WorldSourceReconciler(KubernetesClient client, ConnectorResolver connectorResolver, Clock clock) {
        this.client = client;
        this.connectorResolver = connectorResolver;
        this.clock = clock;
    }

    @Override
    public UpdateControl<WorldSource> reconcile(WorldSource source, Context<WorldSource> context) {
        String type = source.getSpec().getType();
        String poll = source.getSpec().getPoll();

        if (poll == null || poll.isBlank() || !POLLABLE_TYPES.contains(type)) {
            return manualOnly(source);
        }

        CronSchedule schedule;
        try {
            schedule = CronSchedule.parse(poll);
        } catch (CronSchedule.InvalidCronExpressionException e) {
            return terminalCondition(source, INVALID_POLL_REASON, e.getMessage());
        }

        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime lastPoll = parseInstant(source.getStatus().getLastPollTime());
        if (!schedule.isDue(lastPoll, now)) {
            return UpdateControl.<WorldSource>noUpdate().rescheduleAfter(schedule.timeToNext(now));
        }

        if (source.getSpec().getWorlds().isEmpty()) {
            return pending(source, NO_WORLDS_CONFIGURED_REASON, "no worlds configured to ingest", schedule, now);
        }

        WorldSourceConnector connector = connectorResolver.resolve(type);
        Map<String, String> config = sourceConfig(source);

        List<SourceVersion> versions;
        try {
            versions = connector.discover(config);
        } catch (RuntimeException e) {
            // Never let a connection/credential problem surface with its raw message here --
            // it may embed request details; discover() implementations already keep secrets
            // out of exception messages, but this is the one place in the operator that talks
            // to an external source directly, so the boundary is defended explicitly too.
            return pending(
                    source,
                    DISCOVERY_FAILED_REASON,
                    "failed to list versions at the source: " + e.getClass().getSimpleName(),
                    schedule,
                    now);
        }

        source.getStatus().setLastPollTime(now.toInstant().toString());

        Optional<SourceVersion> latest = versions.stream().max(Comparator.comparing(SourceVersion::createdAt));
        if (latest.isEmpty()) {
            Conditions.set(
                    source.getStatus().getConditions(),
                    Conditions.ready(true, UP_TO_DATE_REASON, "no versions available at the source yet"));
            return UpdateControl.patchStatus(source).rescheduleAfter(schedule.timeToNext(now));
        }

        String latestId = latest.get().id();
        if (latestId.equals(source.getStatus().getLastSeenVersion())) {
            Conditions.set(
                    source.getStatus().getConditions(),
                    Conditions.ready(true, UP_TO_DATE_REASON, "already ingested version '" + latestId + "'"));
            return UpdateControl.patchStatus(source).rescheduleAfter(schedule.timeToNext(now));
        }

        boolean conflict = triggerIngests(source, latestId);
        source.getStatus().setLastSeenVersion(latestId);
        Conditions.set(
                source.getStatus().getConditions(),
                conflict
                        ? Conditions.ready(
                                false,
                                RESOURCE_CONFLICT_REASON,
                                "an ingest name for version '" + latestId
                                        + "' collided with a resource not owned by this source")
                        : Conditions.ready(true, INGEST_TRIGGERED_REASON, "triggered ingest for version '" + latestId + "'"));
        return UpdateControl.patchStatus(source).rescheduleAfter(schedule.timeToNext(now));
    }

    /**
     * Creates one {@link WorldIngest} per configured world for {@code latestVersion}, skipping
     * any that already exist (idempotent retry) and any name collision with a foreign resource.
     *
     * @return {@code true} if at least one collision with a foreign resource was found
     */
    private boolean triggerIngests(WorldSource source, String latestVersion) {
        String namespace = source.getMetadata().getNamespace();
        String sourceName = source.getMetadata().getName();
        String sourceUid = source.getMetadata().getUid();

        boolean conflict = false;
        for (WorldSource.WorldSelector selector : source.getSpec().getWorlds()) {
            String ingestName = ingestNameFor(sourceName, selector.getName(), latestVersion);
            WorldIngest existing =
                    client.resources(WorldIngest.class).inNamespace(namespace).withName(ingestName).get();
            if (existing != null) {
                if (!ownedBySameSource(existing.getMetadata().getLabels(), sourceName, sourceUid)) {
                    conflict = true;
                }
                continue; // already triggered for this version -- idempotent no-op either way
            }

            WorldIngest ingest = new WorldIngest();
            ingest.setMetadata(new ObjectMetaBuilder()
                    .withName(ingestName)
                    .withNamespace(namespace)
                    .withLabels(ingestLabels(sourceName, sourceUid))
                    .withOwnerReferences(ownerReference(source))
                    .build());
            ingest.getSpec().getSourceRef().setName(sourceName);
            ingest.getSpec().setSourceVersion(latestVersion);
            ingest.getSpec().setWorldName(selector.getName());
            client.resources(WorldIngest.class).inNamespace(namespace).resource(ingest).create();
        }
        return conflict;
    }

    /**
     * Builds the connector configuration map for {@code source.spec.type}, resolving the
     * referenced credentials Secret (if any) to its decoded value -- never logged, never
     * written to status; see {@link Secrets}.
     */
    private Map<String, String> sourceConfig(WorldSource source) {
        String namespace = source.getMetadata().getNamespace();
        Map<String, String> config = new LinkedHashMap<>();
        if (TYPE_S3.equals(source.getSpec().getType())) {
            var s3 = source.getSpec().getS3();
            putIfPresent(config, S3SourceConnector.CONFIG_BUCKET, s3.getBucket());
            putIfPresent(config, S3SourceConnector.CONFIG_ENDPOINT, s3.getEndpoint());
            putIfPresent(config, S3SourceConnector.CONFIG_PREFIX, s3.getPrefix());
            String secretName = s3.getCredentialsSecretRef().getName();
            putIfPresent(
                    config,
                    S3SourceConnector.CONFIG_ACCESS_KEY_ID,
                    Secrets.value(client, namespace, secretName, "AWS_ACCESS_KEY_ID"));
            putIfPresent(
                    config,
                    S3SourceConnector.CONFIG_SECRET_ACCESS_KEY,
                    Secrets.value(client, namespace, secretName, "AWS_SECRET_ACCESS_KEY"));
        } else if (TYPE_PTERODACTYL.equals(source.getSpec().getType())) {
            var pterodactyl = source.getSpec().getPterodactyl();
            putIfPresent(config, PterodactylConnector.CONFIG_PANEL_URL, pterodactyl.getPanelUrl());
            putIfPresent(config, PterodactylConnector.CONFIG_SERVER_ID, pterodactyl.getServerId());
            String secretName = pterodactyl.getCredentialsSecretRef().getName();
            putIfPresent(
                    config, PterodactylConnector.CONFIG_API_KEY, Secrets.value(client, namespace, secretName, "API_KEY"));
        }
        return config;
    }

    private static void putIfPresent(Map<String, String> config, String key, String value) {
        if (value != null && !value.isBlank()) {
            config.put(key, value);
        }
    }

    private static WorldSourceConnector defaultConnector(String type) {
        return switch (type) {
            case TYPE_S3 -> new S3SourceConnector();
            case TYPE_PTERODACTYL -> new PterodactylConnector();
            default -> throw new IllegalStateException("unsupported source type: " + type);
        };
    }

    /**
     * Deterministic name for the {@link WorldIngest} triggered for one (source, world, source
     * version) triple -- what makes {@link #triggerIngests} idempotent. Kubernetes resource
     * names must be valid RFC 1123 DNS subdomain labels (lowercase alphanumeric and {@code -},
     * max 253 characters); a raw source version id (an S3 key, a Pterodactyl backup UUID, ...)
     * is not guaranteed to satisfy that, so it is never used verbatim. Instead: a sanitised,
     * truncated version of it, plus a short hash of the *original, unsanitised* id so that two
     * different version ids which happen to sanitise to the same string (e.g. differing only in
     * characters {@link #sanitize} strips) still get different, non-colliding names.
     */
    static String ingestNameFor(String sourceName, String worldName, String version) {
        String hash = shortHash(version);
        String base = sanitize(sourceName) + "-" + sanitize(worldName) + "-" + sanitize(version);
        int maxBaseLength = 253 - 1 - hash.length();
        if (base.length() > maxBaseLength) {
            base = base.substring(0, maxBaseLength);
        }
        return base + "-" + hash;
    }

    private static String sanitize(String value) {
        String lowered = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        String replaced = lowered.replaceAll("[^a-z0-9]+", "-");
        String trimmed = replaced.replaceAll("^-+|-+$", "");
        return trimmed.isEmpty() ? "x" : trimmed;
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static Map<String, String> ingestLabels(String sourceName, String sourceUid) {
        Map<String, String> labels = Labels.standard("world-ingest", sourceName);
        labels.put(Labels.SOURCE, sourceName);
        if (sourceUid != null && !sourceUid.isBlank()) {
            labels.put(Labels.SOURCE_UID, sourceUid);
        }
        return labels;
    }

    private static boolean ownedBySameSource(Map<String, String> labels, String sourceName, String sourceUid) {
        if (labels == null || sourceUid == null) {
            return false;
        }
        return Objects.equals(sourceName, labels.get(Labels.SOURCE)) && Objects.equals(sourceUid, labels.get(Labels.SOURCE_UID));
    }

    private static OwnerReference ownerReference(WorldSource source) {
        return new OwnerReferenceBuilder()
                .withApiVersion(OWNER_API_VERSION)
                .withKind(OWNER_KIND)
                .withName(source.getMetadata().getName())
                .withUid(source.getMetadata().getUid())
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build();
    }

    private static ZonedDateTime parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value).atZone(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static UpdateControl<WorldSource> manualOnly(WorldSource source) {
        Conditions.set(
                source.getStatus().getConditions(),
                Conditions.ready(true, MANUAL_ONLY_REASON, "no automatic poll configured for this source"));
        return UpdateControl.patchStatus(source);
    }

    private static UpdateControl<WorldSource> terminalCondition(WorldSource source, String reason, String message) {
        Conditions.set(source.getStatus().getConditions(), Conditions.ready(false, reason, message));
        return UpdateControl.patchStatus(source);
    }

    private static UpdateControl<WorldSource> pending(
            WorldSource source, String reason, String message, CronSchedule schedule, ZonedDateTime now) {
        Conditions.set(source.getStatus().getConditions(), Conditions.ready(false, reason, message));
        return UpdateControl.patchStatus(source).rescheduleAfter(schedule.timeToNext(now));
    }

    /** Resolves the {@link WorldSourceConnector} implementation for a {@code spec.type} value. */
    @FunctionalInterface
    interface ConnectorResolver {
        WorldSourceConnector resolve(String type);
    }
}
