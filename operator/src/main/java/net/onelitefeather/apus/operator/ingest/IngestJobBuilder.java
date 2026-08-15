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

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.Labels;
import net.onelitefeather.apus.operator.api.WorldIngest;
import net.onelitefeather.apus.operator.api.WorldSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a {@link WorldIngest} plus the {@link WorldSource} it targets into the Kubernetes {@link
 * Job} that actually performs the ingest, by driving the {@code apus/ingest} image (phase 2b,
 * task 5) through its environment-variable contract ({@code ingest/README.md}).
 *
 * <p>Pure function: no Kubernetes client, no side effects, exactly like {@code RenderJobBuilder}
 * -- the caller ({@link WorldIngestReconciler}) is responsible for actually submitting the
 * returned {@link Job} and for having already claimed the concurrency lock this builder assumes
 * is held.
 *
 * <p><b>Bundle destination is operator-wide, not per-source.</b> Neither {@link WorldSource} nor
 * {@link WorldIngest} carries a bundle-bucket field -- only {@code WorldSourceSpec.s3}/{@code
 * .pterodactyl}, which describe the raw data *source*, not where the resulting bundle is written.
 * The destination therefore comes from {@link OperatorConfig#bundleBucket()} and friends, the
 * same site-wide-setting pattern {@link OperatorConfig#runnerImage()} already established. See
 * that record's Javadoc for the full reasoning.
 *
 * <p><b>Bundle version identifier.</b> {@code APUS_BUNDLE_VERSION} must be distinct from {@code
 * APUS_SOURCE_VERSION} ({@code ingest/README.md}'s own run example uses {@code v1} for the
 * former and a source-specific, potentially messy id for the latter) -- a bundle version needs a
 * clean, unique-per-run identifier a render can reference and retention can enumerate. This
 * builder uses the {@link WorldIngest}'s own resource name: {@link WorldSourceReconciler} already
 * mints a fresh, unique name per discovered source version, so reusing it costs nothing extra and
 * keeps the ingest run and the bundle it produces traceable to each other by the same string.
 *
 * <p><b>Bounded ephemeral storage.</b> The ingest container mounts no volume for its work
 * directory or the archive it extracts -- both land on the container's writable layer, backed by
 * the node's own disk. {@code Archives}' own configurable total-bytes/entry-count limits (see
 * {@code ingest/README.md}) stop a hostile "archive bomb" from writing unbounded data, but without
 * a Kubernetes-level {@code ephemeral-storage} resource limit too, even a *legitimate* large world
 * could still starve the node's disk for every other pod scheduled on it. This builder therefore
 * always sets both a request and a limit for it, as defense in depth alongside the application-level
 * check, not instead of it.
 */
public final class IngestJobBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestJobBuilder.class);

    /** API group + version the owning {@link WorldIngest} is served under. */
    private static final String OWNER_API_VERSION = "bluemap.onelitefeather.net/v1alpha1";

    private static final String OWNER_KIND = "WorldIngest";

    /** Mirrors {@code RenderJobBuilder.BACKOFF_LIMIT}'s reasoning: fail fast, don't retry forever. */
    private static final int BACKOFF_LIMIT = 2;

    private static final String CONTAINER_NAME = "ingest";

    /** Mirrors {@code RenderJobBuilder.TERMINATION_MESSAGE_POLICY} -- see its Javadoc. */
    private static final String TERMINATION_MESSAGE_POLICY = "FallbackToLogsOnError";

    private static final String AUTO_LAYOUT = "auto";
    private static final String TYPE_S3 = "s3";
    private static final String TYPE_PTERODACTYL = "pterodactyl";

    /**
     * Ephemeral-storage request/limit for the ingest container -- see the class Javadoc's
     * "Bounded ephemeral storage" section for why this exists at all.
     */
    private static final String EPHEMERAL_STORAGE_REQUEST = "2Gi";

    private static final String EPHEMERAL_STORAGE_LIMIT = "10Gi";

    /** Secret data key expected on the bundle destination credentials secret. */
    private static final String BUNDLE_ACCESS_KEY = "AWS_ACCESS_KEY_ID";

    private static final String BUNDLE_SECRET_KEY = "AWS_SECRET_ACCESS_KEY";

    /**
     * Secret data key expected on {@code WorldSourceSpec.S3Source.credentialsSecretRef} when
     * set; matches the destination convention above so one Secret shape works for both roles.
     */
    private static final String SOURCE_S3_ACCESS_KEY = "AWS_ACCESS_KEY_ID";

    private static final String SOURCE_S3_SECRET_KEY = "AWS_SECRET_ACCESS_KEY";

    /** Secret data key expected on {@code WorldSourceSpec.Pterodactyl.credentialsSecretRef}. */
    private static final String PTERODACTYL_API_KEY = "API_KEY";

    private IngestJobBuilder() {}

    /**
     * Builds the ingest {@link Job} for one {@link WorldIngest} run.
     *
     * @param ingest the ingest run to execute; supplies the world name, source version and owns
     *     the returned job via an owner reference
     * @param source the {@link WorldSource} being pulled from; supplies the source type and
     *     connection details
     * @param config operator-wide settings: the ingest image and the bundle destination
     * @return the {@link Job} manifest, not yet submitted to the API server
     */
    public static Job build(WorldIngest ingest, WorldSource source, OperatorConfig config) {
        String namespace = ingest.getMetadata().getNamespace();
        LOGGER.debug(
                "building ingest job '{}' in namespace '{}' for source '{}' from image '{}'",
                ingest.getMetadata().getName(),
                namespace,
                source.getMetadata().getName(),
                config.ingestImage());
        Map<String, String> labels = labels(ingest, source);

        Container container = new ContainerBuilder()
                .withName(CONTAINER_NAME)
                .withImage(config.ingestImage())
                .withEnv(env(ingest, source, config))
                .withResources(resources())
                .withTerminationMessagePolicy(TERMINATION_MESSAGE_POLICY)
                .build();

        return new JobBuilder()
                .withNewMetadata()
                .withName(ingest.getMetadata().getName())
                .withNamespace(namespace)
                .withLabels(labels)
                .withOwnerReferences(ownerReference(ingest))
                .endMetadata()
                .withNewSpec()
                .withBackoffLimit(BACKOFF_LIMIT)
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withContainers(container)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    /**
     * The bundle's version identifier this job will write under -- see the class Javadoc's
     * "Bundle version identifier" section. Exposed so {@link WorldIngestReconciler} can compute
     * {@code WorldSource.status.latestBundle}/{@code WorldIngest.status.bundle} without having
     * to duplicate or parse it back out of the job.
     */
    public static String bundleVersion(WorldIngest ingest) {
        return ingest.getMetadata().getName();
    }

    private static Map<String, String> labels(WorldIngest ingest, WorldSource source) {
        Map<String, String> labels = Labels.standard("world-ingest", ingest.getMetadata().getName());
        labels.put(Labels.SOURCE, source.getMetadata().getName());
        if (source.getMetadata().getUid() != null) {
            labels.put(Labels.SOURCE_UID, source.getMetadata().getUid());
        }
        return labels;
    }

    /**
     * The ingest container's {@code ephemeral-storage} request/limit -- see the class Javadoc's
     * "Bounded ephemeral storage" section.
     */
    private static ResourceRequirements resources() {
        Map<String, Quantity> quantities = new LinkedHashMap<>();
        quantities.put("ephemeral-storage", new Quantity(EPHEMERAL_STORAGE_REQUEST));
        Map<String, Quantity> limits = new LinkedHashMap<>();
        limits.put("ephemeral-storage", new Quantity(EPHEMERAL_STORAGE_LIMIT));
        return new ResourceRequirementsBuilder()
                .withRequests(quantities)
                .withLimits(limits)
                .build();
    }

    private static OwnerReference ownerReference(WorldIngest ingest) {
        return new OwnerReferenceBuilder()
                .withApiVersion(OWNER_API_VERSION)
                .withKind(OWNER_KIND)
                .withName(ingest.getMetadata().getName())
                .withUid(ingest.getMetadata().getUid())
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build();
    }

    /**
     * Builds the environment for the {@code ingest} container to satisfy the phase 2b ingest
     * image's contract exactly ({@code ingest/README.md}). Every mandatory variable is always
     * set; optional ones are only added when the data model actually carries a value, so the
     * image's own defaults apply otherwise.
     */
    private static List<EnvVar> env(WorldIngest ingest, WorldSource source, OperatorConfig config) {
        String namespace = ingest.getMetadata().getNamespace();
        String worldName = ingest.getSpec().getWorldName();
        String bundleVersion = bundleVersion(ingest);

        List<EnvVar> env = new ArrayList<>();

        // Mandatory -- IngestConfig.fromEnv exits non-zero at startup if any of these is missing.
        env.add(literal("APUS_SOURCE_TYPE", source.getSpec().getType()));
        env.add(literal("APUS_WORLD_NAME", worldName));
        env.add(literal("APUS_SOURCE_VERSION", ingest.getSpec().getSourceVersion()));
        env.add(literal("APUS_BUNDLE_BUCKET", config.bundleBucket()));
        env.add(literal("APUS_BUNDLE_TENANT", tenantNameForNamespace(namespace)));
        // Scopes the bundle path by the owning source's name, not just worldId -- see
        // net.onelitefeather.apus.ingest.BundlePath's Javadoc for why worldId alone (the
        // Minecraft world's own directory name, commonly the vanilla default "world") is not
        // enough to keep two different sources' bundles from colliding on the same prefix.
        env.add(literal("APUS_BUNDLE_SOURCE_NAME", source.getMetadata().getName()));
        env.add(literal("APUS_BUNDLE_WORLD_ID", worldName));
        env.add(literal("APUS_BUNDLE_VERSION", bundleVersion));
        env.add(literal("APUS_S3_ENDPOINT", config.bundleS3Endpoint()));
        env.add(fromSecret("APUS_S3" + "_ACCESS_KEY", config.bundleCredentialsSecretName(), BUNDLE_ACCESS_KEY));
        env.add(fromSecret("APUS_S3" + "_SECRET_KEY", config.bundleCredentialsSecretName(), BUNDLE_SECRET_KEY));

        // Optional -- only set when the CR/config actually carries a non-default value.
        if (config.bundleS3Region() != null && !config.bundleS3Region().isBlank()) {
            env.add(literal("APUS_S3_REGION", config.bundleS3Region()));
        }
        env.add(literal("APUS_LAYOUT", layoutFor(source, worldName)));
        String minecraftVersion = minecraftVersionFor(source, worldName);
        if (minecraftVersion != null && !minecraftVersion.isBlank()) {
            env.add(literal("APUS_MC_VERSION", minecraftVersion));
        }

        switch (source.getSpec().getType()) {
            case TYPE_S3 -> env.addAll(s3SourceEnv(source));
            case TYPE_PTERODACTYL -> env.addAll(pterodactylSourceEnv(source, worldName));
            default -> {
                // WorldSourceReconciler never creates a WorldIngest for an unsupported source
                // type (upload/push have no connector yet -- phase 6), so reaching this means
                // the two disagree about which types are pollable/ingestible.
            }
        }

        env.addAll(telemetryEnv(namespace, worldName));

        return env;
    }

    /**
     * Passes this operator's OpenTelemetry configuration into the ingest job, plus the current
     * trace context.
     *
     * <p>Without this the ingest image would build an SDK with no endpoint and its spans would
     * go nowhere -- the instrumentation would be dead weight. Passing the endpoint through means
     * the job reports to the same collector the operator does, without anyone configuring the
     * job separately.
     *
     * <p>The {@code traceparent} entry is what makes the job's run a child of the reconciliation
     * that created it rather than an unrelated trace: the ingest image reads it through the
     * standard W3C propagator, so "why did this take forty minutes" is one trace from the
     * operator's reconcile down to the dimension being written.
     *
     * <p>Only variables that are actually set are forwarded, so an operator running without a
     * collector produces a job without one either.
     */
    private static List<EnvVar> telemetryEnv(String namespace, String worldName) {
        List<EnvVar> env = new ArrayList<>();

        for (String name : List.of(
                "OTEL_EXPORTER_OTLP_ENDPOINT",
                "OTEL_EXPORTER_OTLP_PROTOCOL",
                "OTEL_EXPORTER_OTLP_HEADERS",
                "OTEL_TRACES_SAMPLER",
                "OTEL_TRACES_SAMPLER_ARG")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                env.add(literal(name, value));
            }
        }

        // Named per job rather than inherited, so a trace shows "apus-ingest" doing the work
        // rather than the operator that asked for it.
        if (System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != null) {
            env.add(literal("OTEL_SERVICE_NAME", "apus-ingest"));
            env.add(literal(
                    "OTEL_RESOURCE_ATTRIBUTES",
                    "k8s.namespace.name=" + namespace + ",apus.world.name=" + worldName));
        }

        // W3C directly rather than through GlobalOpenTelemetry's propagators: the ingest image
        // reads the standard traceparent header, and going via the global instance would make
        // this silently produce nothing whenever that has not been registered -- which is
        // exactly the kind of failure nobody notices until a trace is missing.
        Map<String, String> carrier = new LinkedHashMap<>();
        W3CTraceContextPropagator.getInstance()
                .inject(Context.current(), carrier, (TextMapSetter<Map<String, String>>) (map, key, value) -> {
                    if (map != null) {
                        map.put(key, value);
                    }
                });
        carrier.forEach((key, value) -> env.add(literal(key.toUpperCase(Locale.ROOT).replace('-', '_'), value)));

        return env;
    }

    private static List<EnvVar> s3SourceEnv(WorldSource source) {
        List<EnvVar> env = new ArrayList<>();
        WorldSourceSpecAccess s3 = WorldSourceSpecAccess.s3(source);
        env.add(literal("APUS_SOURCE_S3_BUCKET", s3.bucket()));
        addIfPresent(env, "APUS_SOURCE_S3_ENDPOINT", s3.endpoint());
        addIfPresent(env, "APUS_SOURCE_S3_PREFIX", s3.prefix());
        if (s3.credentialsSecretName() != null && !s3.credentialsSecretName().isBlank()) {
            env.add(fromSecret("APUS_SOURCE_S3_ACCESS_KEY", s3.credentialsSecretName(), SOURCE_S3_ACCESS_KEY));
            env.add(fromSecret("APUS_SOURCE_S3_SECRET_KEY", s3.credentialsSecretName(), SOURCE_S3_SECRET_KEY));
        }
        return env;
    }

    /**
     * {@code APUS_PTERODACTYL_WORLD_PATHS} names the top-level backup archive paths that make up
     * one logical world -- needed because {@code PterodactylConnector.fetch} streams a whole-server
     * {@code tar.gz} exactly once and writes only matching entries (see its Javadoc). Neither
     * {@code WorldSourceSpec} nor {@code WorldIngestSpec} carries this breakdown explicitly, so
     * this derives it from the project's own established Bukkit split-world convention (the same
     * one {@code LayoutDetector}/the phase 2b plan document: {@code <world>}, {@code
     * <world>_nether}, {@code <world>_the_end}). A vanilla single-directory world is simply the
     * first of the three paths with the other two never matching anything in the archive, which
     * is harmless. A future {@code WorldSelector} field could override this if a server uses a
     * non-standard split -- not needed by any fixture or spec this phase defines.
     */
    private static List<EnvVar> pterodactylSourceEnv(WorldSource source, String worldName) {
        List<EnvVar> env = new ArrayList<>();
        WorldSourceSpecAccess pterodactyl = WorldSourceSpecAccess.pterodactyl(source);
        env.add(literal("APUS_PTERODACTYL_PANEL_URL", pterodactyl.panelUrl()));
        env.add(literal("APUS_PTERODACTYL_SERVER_ID", pterodactyl.serverId()));
        env.add(fromSecret("APUS_PTERODACTYL_API_KEY", pterodactyl.credentialsSecretName(), PTERODACTYL_API_KEY));
        env.add(literal(
                "APUS_PTERODACTYL_WORLD_PATHS",
                worldName + "," + worldName + "_nether" + "," + worldName + "_the_end"));
        return env;
    }

    private static void addIfPresent(List<EnvVar> env, String name, String value) {
        if (value != null && !value.isBlank()) {
            env.add(literal(name, value));
        }
    }

    private static String layoutFor(WorldSource source, String worldName) {
        for (WorldSource.WorldSelector selector : source.getSpec().getWorlds()) {
            if (worldName != null && worldName.equals(selector.getName())) {
                return selector.getLayout();
            }
        }
        return AUTO_LAYOUT;
    }

    /**
     * The Minecraft version configured on the matching {@link WorldSource.WorldSelector}, or
     * {@code null} if no selector matches or none was configured -- see {@link
     * WorldSource.WorldSelector#getMinecraftVersion()} for why this is a user-supplied field
     * rather than read from {@code level.dat}.
     */
    private static String minecraftVersionFor(WorldSource source, String worldName) {
        for (WorldSource.WorldSelector selector : source.getSpec().getWorlds()) {
            if (worldName != null && worldName.equals(selector.getName())) {
                return selector.getMinecraftVersion();
            }
        }
        return null;
    }

    /**
     * Recovers the tenant name from a namespace of the form {@code bluemap-<tenant>}, the same
     * inversion {@code BlueMapMapReconciler.cephUserForNamespace} performs for the identical
     * reason: neither {@link WorldSource} nor {@link WorldIngest} carries a direct tenant
     * reference, only the namespace they live in, and duplicating this narrow one-line inversion
     * is exactly what that class's Javadoc already explains is preferable to a shared helper.
     */
    private static String tenantNameForNamespace(String namespace) {
        String prefix = "bluemap-";
        return namespace != null && namespace.startsWith(prefix) ? namespace.substring(prefix.length()) : namespace;
    }

    private static EnvVar literal(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }

    /** Credentials must come from a Secret Kubernetes resolves at container start, never inlined. */
    private static EnvVar fromSecret(String name, String secretName, String key) {
        return new EnvVarBuilder()
                .withName(name)
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(secretName)
                .withKey(key)
                .endSecretKeyRef()
                .endValueFrom()
                .build();
    }

    /** Narrow read-only view over whichever of {@code WorldSourceSpec.s3}/{@code .pterodactyl} applies. */
    private record WorldSourceSpecAccess(
            String bucket, String endpoint, String prefix, String panelUrl, String serverId, String credentialsSecretName) {

        static WorldSourceSpecAccess s3(WorldSource source) {
            var s3 = source.getSpec().getS3();
            return new WorldSourceSpecAccess(
                    s3.getBucket(),
                    s3.getEndpoint(),
                    s3.getPrefix(),
                    null,
                    null,
                    s3.getCredentialsSecretRef().getName());
        }

        static WorldSourceSpecAccess pterodactyl(WorldSource source) {
            var pterodactyl = source.getSpec().getPterodactyl();
            return new WorldSourceSpecAccess(
                    null,
                    null,
                    null,
                    pterodactyl.getPanelUrl(),
                    pterodactyl.getServerId(),
                    pterodactyl.getCredentialsSecretRef().getName());
        }
    }
}
