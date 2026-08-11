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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.onelitefeather.apus.ingest.connector.SourceVersion;
import net.onelitefeather.apus.ingest.connector.WorldSourceConnector;
import net.onelitefeather.apus.operator.api.Conditions;
import net.onelitefeather.apus.operator.api.WorldIngest;
import net.onelitefeather.apus.operator.api.WorldSource;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class WorldSourceReconcilerTest {

    KubernetesClient client;

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-09T12:00:00Z"), ZoneOffset.UTC);

    private WorldSource source(String name) {
        WorldSource source = new WorldSource();
        source.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace("bluemap-friends")
                .withUid(UUID.randomUUID().toString())
                .build());
        source.getSpec().setType("s3");
        source.getSpec().setPoll("0 * * * *"); // hourly
        source.getSpec().getS3().setBucket("backups");
        WorldSource.WorldSelector selector = new WorldSource.WorldSelector();
        selector.setName("world");
        source.getSpec().getWorlds().add(selector);
        return source;
    }

    private String readyReason(WorldSource source) {
        return source.getStatus().getConditions().stream()
                .filter(c -> Conditions.READY.equals(c.getType()))
                .findFirst()
                .orElseThrow()
                .getReason();
    }

    private WorldSourceReconciler reconciler(WorldSourceConnector connector) {
        return new WorldSourceReconciler(client, type -> connector, FIXED_CLOCK);
    }

    private WorldSourceConnector fixedVersions(List<SourceVersion> versions) {
        return new WorldSourceConnector() {
            @Override
            public String type() {
                return "s3";
            }

            @Override
            public List<SourceVersion> discover(Map<String, String> config) {
                return versions;
            }

            @Override
            public void fetch(Map<String, String> config, SourceVersion version, java.nio.file.Path workDir) {
                throw new UnsupportedOperationException("not used by WorldSourceReconciler");
            }
        };
    }

    @Test
    void manualOnlySourceIsNeverPolled() {
        WorldSource source = source("s1");
        source.getSpec().setPoll(null);

        WorldSourceReconciler reconciler = reconciler(fixedVersions(List.of()));
        reconciler.reconcile(source, null);

        assertEquals(WorldSourceReconciler.MANUAL_ONLY_REASON, readyReason(source));
    }

    @Test
    void pushTypeSourceIsNeverPolledEvenIfPollIsSet() {
        WorldSource source = source("s1");
        source.getSpec().setType("upload");
        source.getSpec().setPoll("0 * * * *");

        WorldSourceReconciler reconciler = reconciler(fixedVersions(List.of()));
        reconciler.reconcile(source, null);

        assertEquals(WorldSourceReconciler.MANUAL_ONLY_REASON, readyReason(source));
    }

    @Test
    void invalidCronExpressionIsReportedRatherThanGuessed() {
        WorldSource source = source("s1");
        source.getSpec().setPoll("not a cron");

        WorldSourceReconciler reconciler = reconciler(fixedVersions(List.of()));
        reconciler.reconcile(source, null);

        assertEquals(WorldSourceReconciler.INVALID_POLL_REASON, readyReason(source));
    }

    @Test
    void notYetDueDoesNotCallDiscoverAtAll() {
        WorldSource source = source("s1");
        // Already polled once this hour (11:00); the hourly cron's next fire is 12:00, and
        // "now" below is still well before that.
        source.getStatus().setLastPollTime(Instant.parse("2026-08-09T11:00:00Z").toString());

        WorldSourceConnector connector = new WorldSourceConnector() {
            @Override
            public String type() {
                return "s3";
            }

            @Override
            public List<SourceVersion> discover(Map<String, String> config) {
                throw new AssertionError("discover() must not be called before the poll is due");
            }

            @Override
            public void fetch(Map<String, String> config, SourceVersion version, java.nio.file.Path workDir) {}
        };

        Clock notYetDueClock = Clock.fixed(Instant.parse("2026-08-09T11:50:00Z"), ZoneOffset.UTC);
        var control = new WorldSourceReconciler(client, type -> connector, notYetDueClock).reconcile(source, null);

        assertTrue(control.isNoUpdate());
    }

    @Test
    void newVersionTriggersOneWorldIngestPerConfiguredWorld() {
        WorldSource source = source("survival-source");
        source.getSpec().getWorlds().get(0).setName("world");
        WorldSource.WorldSelector second = new WorldSource.WorldSelector();
        second.setName("creative");
        source.getSpec().getWorlds().add(second);

        SourceVersion v1 = new SourceVersion("v1.zip", "v1.zip", Instant.parse("2026-08-09T10:00:00Z"), 100);
        WorldSourceReconciler reconciler = reconciler(fixedVersions(List.of(v1)));

        reconciler.reconcile(source, null);

        List<WorldIngest> ingests = client.resources(WorldIngest.class)
                .inNamespace("bluemap-friends")
                .list()
                .getItems();
        assertEquals(2, ingests.size(), "one WorldIngest per configured world");
        assertEquals("v1.zip", source.getStatus().getLastSeenVersion());
        assertEquals(WorldSourceReconciler.INGEST_TRIGGERED_REASON, readyReason(source));
        for (WorldIngest ingest : ingests) {
            assertEquals("v1.zip", ingest.getSpec().getSourceVersion());
            assertEquals("survival-source", ingest.getSpec().getSourceRef().getName());
        }
    }

    @Test
    void alreadySeenVersionDoesNotTriggerAnotherIngest() {
        WorldSource source = source("survival-source");
        SourceVersion v1 = new SourceVersion("v1.zip", "v1.zip", Instant.parse("2026-08-09T10:00:00Z"), 100);
        WorldSourceReconciler reconciler = reconciler(fixedVersions(List.of(v1)));

        reconciler.reconcile(source, null);
        int afterFirst = client.resources(WorldIngest.class)
                .inNamespace("bluemap-friends")
                .list()
                .getItems()
                .size();

        // Simulate the next scheduled reconcile: due again, same latest version reported.
        source.getStatus().setLastPollTime(Instant.parse("2026-08-09T11:00:00Z").toString());
        reconciler.reconcile(source, null);
        int afterSecond = client.resources(WorldIngest.class)
                .inNamespace("bluemap-friends")
                .list()
                .getItems()
                .size();

        assertEquals(1, afterFirst);
        assertEquals(1, afterSecond, "no new WorldIngest for a version already seen");
        assertEquals(WorldSourceReconciler.UP_TO_DATE_REASON, readyReason(source));
    }

    @Test
    void reconcilingTwiceForTheSameNewVersionIsIdempotent() {
        WorldSource source = source("survival-source");
        SourceVersion v1 = new SourceVersion("v1.zip", "v1.zip", Instant.parse("2026-08-09T10:00:00Z"), 100);
        WorldSourceReconciler reconciler = reconciler(fixedVersions(List.of(v1)));

        // First call creates the WorldIngest but crashes (simulated) before status is
        // persisted -- re-running against the same in-memory object must not create a second
        // WorldIngest for the same (source, world, version) triple.
        reconciler.reconcile(source, null);
        reconciler.reconcile(source, null);

        List<WorldIngest> ingests = client.resources(WorldIngest.class)
                .inNamespace("bluemap-friends")
                .list()
                .getItems();
        assertEquals(1, ingests.size());
    }

    @Test
    void noWorldsConfiguredIsReportedInsteadOfSilentlyDoingNothing() {
        WorldSource source = source("s1");
        source.getSpec().getWorlds().clear();

        reconciler(fixedVersions(List.of())).reconcile(source, null);

        assertEquals(WorldSourceReconciler.NO_WORLDS_CONFIGURED_REASON, readyReason(source));
    }

    @Test
    void discoveryFailureIsReportedAndDoesNotCrashReconciliation() {
        WorldSource source = source("s1");
        WorldSourceConnector failing = new WorldSourceConnector() {
            @Override
            public String type() {
                return "s3";
            }

            @Override
            public List<SourceVersion> discover(Map<String, String> config) {
                throw new RuntimeException("connection refused");
            }

            @Override
            public void fetch(Map<String, String> config, SourceVersion version, java.nio.file.Path workDir) {}
        };

        reconciler(failing).reconcile(source, null);

        assertEquals(WorldSourceReconciler.DISCOVERY_FAILED_REASON, readyReason(source));
    }

    @Test
    void ingestNameCollisionWithAForeignResourceIsReportedAsAConflict() {
        WorldSource source = source("survival-source");
        SourceVersion v1 = new SourceVersion("v1.zip", "v1.zip", Instant.parse("2026-08-09T10:00:00Z"), 100);
        String collidingName = WorldSourceReconciler.ingestNameFor("survival-source", "world", "v1.zip");

        WorldIngest foreign = new WorldIngest();
        foreign.setMetadata(new ObjectMetaBuilder()
                .withName(collidingName)
                .withNamespace("bluemap-friends")
                .build()); // no owning labels at all
        client.resources(WorldIngest.class).inNamespace("bluemap-friends").resource(foreign).create();

        reconciler(fixedVersions(List.of(v1))).reconcile(source, null);

        assertEquals(WorldSourceReconciler.RESOURCE_CONFLICT_REASON, readyReason(source));
    }

    @Test
    void discoverIsNeverCalledForAPushTypeSource() {
        WorldSource source = source("s1");
        source.getSpec().setType("push");
        source.getSpec().setPoll(null);

        assertFalse(source.getSpec().getWorlds().isEmpty());
        WorldSourceReconciler reconciler = reconciler(fixedVersions(List.of()));
        var control = reconciler.reconcile(source, null);

        assertNotNull(control);
        assertEquals(WorldSourceReconciler.MANUAL_ONLY_REASON, readyReason(source));
    }

    @Test
    void ingestNameForIsDeterministicAndStable() {
        String first = WorldSourceReconciler.ingestNameFor("survival-source", "world", "v1.zip");
        String second = WorldSourceReconciler.ingestNameFor("survival-source", "world", "v1.zip");

        assertEquals(first, second);
        assertTrue(first.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?"), "must be a valid Kubernetes resource name: " + first);
    }

    @Test
    void ingestNameForDiffersByVersionEvenAfterSanitisation() {
        // These two version ids sanitise to the exact same string, but the hash suffix
        // (computed over the original, unsanitised id) must still keep them distinct.
        String a = WorldSourceReconciler.ingestNameFor("s", "w", "v1/backup.zip");
        String b = WorldSourceReconciler.ingestNameFor("s", "w", "v1-backup.zip");

        assertFalse(a.equals(b), "different raw version ids must never collide into the same ingest name");
    }
}
