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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.Labels;
import net.onelitefeather.apus.operator.api.WorldIngest;
import net.onelitefeather.apus.operator.api.WorldSource;
import org.junit.jupiter.api.Test;

class IngestJobBuilderTest {

    private WorldSource s3Source(String name) {
        WorldSource source = new WorldSource();
        source.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace("bluemap-friends")
                .withUid(UUID.randomUUID().toString())
                .build());
        source.getSpec().setType("s3");
        source.getSpec().getS3().setBucket("backups");
        source.getSpec().getS3().setPrefix("survival/");
        source.getSpec().getS3().setEndpoint("http://minio.example.svc:9000");
        source.getSpec().getS3().getCredentialsSecretRef().setName("source-creds");
        WorldSource.WorldSelector selector = new WorldSource.WorldSelector();
        selector.setName("world");
        selector.setLayout("bukkit");
        source.getSpec().getWorlds().add(selector);
        return source;
    }

    private WorldSource pterodactylSource(String name) {
        WorldSource source = new WorldSource();
        source.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace("bluemap-friends")
                .withUid(UUID.randomUUID().toString())
                .build());
        source.getSpec().setType("pterodactyl");
        source.getSpec().getPterodactyl().setPanelUrl("https://panel.example.com");
        source.getSpec().getPterodactyl().setServerId("abc123");
        source.getSpec().getPterodactyl().getCredentialsSecretRef().setName("panel-creds");
        return source;
    }

    private WorldIngest ingest(String name, String sourceVersion) {
        WorldIngest ingest = new WorldIngest();
        ingest.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace("bluemap-friends")
                .withUid(UUID.randomUUID().toString())
                .build());
        ingest.getSpec().getSourceRef().setName("survival-source");
        ingest.getSpec().setSourceVersion(sourceVersion);
        ingest.getSpec().setWorldName("world");
        return ingest;
    }

    private Map<String, EnvVar> envOf(Job job) {
        List<EnvVar> env =
                job.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        return env.stream().collect(Collectors.toMap(EnvVar::getName, Function.identity()));
    }

    @Test
    void suppliesEveryMandatoryEnvironmentVariableForAnS3Source() {
        WorldIngest ingest = ingest("survival-source-world-v1", "2026-08-01T00-00-00Z.zip");
        Job job = IngestJobBuilder.build(ingest, s3Source("survival-source"), OperatorConfig.defaults());

        Map<String, EnvVar> env = envOf(job);

        for (String required : List.of(
                "APUS_SOURCE_TYPE",
                "APUS_WORLD_NAME",
                "APUS_SOURCE_VERSION",
                "APUS_BUNDLE_BUCKET",
                "APUS_BUNDLE_TENANT",
                "APUS_BUNDLE_SOURCE_NAME",
                "APUS_BUNDLE_WORLD_ID",
                "APUS_BUNDLE_VERSION",
                "APUS_S3_ENDPOINT",
                "APUS_S3_ACCESS_KEY",
                "APUS_S3_SECRET_KEY",
                "APUS_SOURCE_S3_BUCKET")) {
            assertNotNull(env.get(required), "missing mandatory variable " + required);
        }

        assertEquals("s3", env.get("APUS_SOURCE_TYPE").getValue());
        assertEquals("world", env.get("APUS_WORLD_NAME").getValue());
        assertEquals("2026-08-01T00-00-00Z.zip", env.get("APUS_SOURCE_VERSION").getValue());
        assertEquals("friends", env.get("APUS_BUNDLE_TENANT").getValue(), "tenant recovered from the namespace");
        assertEquals(
                "survival-source",
                env.get("APUS_BUNDLE_SOURCE_NAME").getValue(),
                "bundle path must be scoped by the owning source's name (see BundlePath)");
        assertEquals("world", env.get("APUS_BUNDLE_WORLD_ID").getValue());
        assertEquals(
                "survival-source-world-v1",
                env.get("APUS_BUNDLE_VERSION").getValue(),
                "bundle version is the WorldIngest's own name, distinct from the source version");
        assertEquals("backups", env.get("APUS_SOURCE_S3_BUCKET").getValue());
        assertEquals("survival/", env.get("APUS_SOURCE_S3_PREFIX").getValue());
        assertEquals("http://minio.example.svc:9000", env.get("APUS_SOURCE_S3_ENDPOINT").getValue());
        assertEquals("bukkit", env.get("APUS_LAYOUT").getValue(), "layout comes from the matching WorldSelector");
    }

    @Test
    void mcVersionIsOmittedWhenTheMatchingWorldSelectorDoesNotConfigureOne() {
        Job job = IngestJobBuilder.build(
                ingest("i1", "v1"), s3Source("survival-source"), OperatorConfig.defaults());

        assertNull(envOf(job).get("APUS_MC_VERSION"), "no minecraftVersion configured on the WorldSelector");
    }

    @Test
    void mcVersionIsPassedThroughFromTheMatchingWorldSelector() {
        WorldSource source = s3Source("survival-source");
        source.getSpec().getWorlds().get(0).setMinecraftVersion("1.21.10");

        Job job = IngestJobBuilder.build(ingest("i1", "v1"), source, OperatorConfig.defaults());

        assertEquals("1.21.10", envOf(job).get("APUS_MC_VERSION").getValue());
    }

    @Test
    void theIngestContainerHasAnEphemeralStorageRequestAndLimit() {
        Job job = IngestJobBuilder.build(
                ingest("i1", "v1"), s3Source("survival-source"), OperatorConfig.defaults());

        var resources = job.getSpec().getTemplate().getSpec().getContainers().get(0).getResources();
        assertNotNull(resources.getRequests().get("ephemeral-storage"), "no volume is mounted for the work directory");
        assertNotNull(resources.getLimits().get("ephemeral-storage"));
    }

    @Test
    void bundleCredentialsComeFromASecretReferenceNeverALiteralValue() {
        Job job = IngestJobBuilder.build(
                ingest("i1", "v1"), s3Source("survival-source"), OperatorConfig.defaults());
        EnvVar accessKey = envOf(job).get("APUS_S3_ACCESS_KEY");

        assertNull(accessKey.getValue(), "must never inline the credential");
        assertNotNull(accessKey.getValueFrom().getSecretKeyRef());
        assertEquals("apus-bundle-credentials", accessKey.getValueFrom().getSecretKeyRef().getName());
        assertEquals("AWS_ACCESS_KEY_ID", accessKey.getValueFrom().getSecretKeyRef().getKey());
    }

    @Test
    void sourceCredentialsAreOmittedWhenNoSecretIsReferenced() {
        WorldSource source = s3Source("survival-source");
        source.getSpec().getS3().setCredentialsSecretRef(new net.onelitefeather.apus.operator.api.Ref());

        Job job = IngestJobBuilder.build(ingest("i1", "v1"), source, OperatorConfig.defaults());

        assertNull(
                envOf(job).get("APUS_SOURCE_S3_ACCESS_KEY"),
                "no secret configured -- the connector falls back to the AWS default credentials chain");
    }

    @Test
    void suppliesPterodactylSpecificVariablesDerivedFromTheBukkitWorldSplitConvention() {
        Job job = IngestJobBuilder.build(
                ingest("i1", "backup-uuid"), pterodactylSource("survival-source"), OperatorConfig.defaults());

        Map<String, EnvVar> env = envOf(job);

        assertEquals("pterodactyl", env.get("APUS_SOURCE_TYPE").getValue());
        assertEquals("https://panel.example.com", env.get("APUS_PTERODACTYL_PANEL_URL").getValue());
        assertEquals("abc123", env.get("APUS_PTERODACTYL_SERVER_ID").getValue());
        assertEquals("panel-creds", env.get("APUS_PTERODACTYL_API_KEY").getValueFrom().getSecretKeyRef().getName());
        assertEquals("world,world_nether,world_the_end", env.get("APUS_PTERODACTYL_WORLD_PATHS").getValue());
        assertNull(env.get("APUS_SOURCE_S3_BUCKET"), "an S3-only variable must not leak into a pterodactyl job");
    }

    @Test
    void layoutDefaultsToAutoWhenNoSelectorMatchesTheWorldName() {
        WorldSource source = s3Source("survival-source");
        source.getSpec().getWorlds().clear();

        Job job = IngestJobBuilder.build(ingest("i1", "v1"), source, OperatorConfig.defaults());

        assertEquals("auto", envOf(job).get("APUS_LAYOUT").getValue());
    }

    @Test
    void placesTheContainerImageFromTheOperatorConfig() {
        OperatorConfig config = new OperatorConfig(
                "rook-ceph-fr01",
                "feather-s3",
                "ceph-bucket-fr01",
                "apus/runner:dev",
                "apus/ingest:1.2.3",
                "apus-bundles",
                "http://rgw.example.svc:80",
                "us-east-1",
                "apus-bundle-credentials");

        Job job = IngestJobBuilder.build(ingest("i1", "v1"), s3Source("survival-source"), config);

        assertEquals(
                "apus/ingest:1.2.3",
                job.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
    }

    @Test
    void ownsTheJobViaAnOwnerReferenceToTheIngestNameAndUid() {
        WorldIngest ingest = ingest("survival-source-world-v1", "v1");

        Job job = IngestJobBuilder.build(ingest, s3Source("survival-source"), OperatorConfig.defaults());

        OwnerReference owner =
                job.getMetadata().getOwnerReferences().get(0);
        assertEquals("WorldIngest", owner.getKind());
        assertEquals(ingest.getMetadata().getName(), owner.getName());
        assertEquals(ingest.getMetadata().getUid(), owner.getUid());
    }

    @Test
    void labelsRecordTheOwningSourceForCrossResourceQueries() {
        WorldSource source = s3Source("survival-source");
        Job job = IngestJobBuilder.build(ingest("i1", "v1"), source, OperatorConfig.defaults());

        Map<String, String> labels = job.getMetadata().getLabels();
        assertEquals(Labels.MANAGED_BY_VALUE, labels.get(Labels.MANAGED_BY));
        assertEquals("survival-source", labels.get(Labels.SOURCE));
        assertEquals(source.getMetadata().getUid(), labels.get(Labels.SOURCE_UID));
    }
}
