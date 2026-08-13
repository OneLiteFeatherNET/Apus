# Apus Phase 9 — Produktionshärte: Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die fünf Punkte abarbeiten, die die Design-Spec in §15 selbst als ungeklärt führt — damit ein produktiver Betrieb nicht auf Heuristiken, ungetesteten Annahmen und zu breiten Berechtigungen steht.

**Architecture:** Fünf voneinander unabhängige Härtungen am bestehenden Code. Zwei davon ersetzen Rateverfahren durch Verträge (Quota-Exit-Code, Push-Token-Lookup), zwei schließen Testlücken gegen echte Fremdsysteme (Identity-Broker, Paper-Server), eine ist eine Messung, deren Ergebnis einen Default in der CR festlegt.

**Tech Stack:** Java 25, JOSDK 5.5.1, Micronaut Security, Testcontainers (Keycloak, k3s, MinIO), MockBukkit, Bash (Runner-Entrypoint).

## Global Constraints

- **Java-Toolchain 25**, AGPL-Lizenzheader über jede neue Java-Datei, Spotless erzwingt ihn.
- **Neue Abhängigkeiten kommen in den Inline-Version-Catalog** in `settings.gradle.kts`, mit Kommentar, wogegen die Version geprüft wurde.
- **Credentials und Token erscheinen nie in CR-Status, Events, Logs oder Metriken** (Design-Spec §12).
- **Der Zeitvergleich in `FabricPushTokenRepository` bleibt konstant-zeitig und erschöpfend.** Er vergleicht heute per `MessageDigest.isEqual` gegen *jeden* Kandidaten ohne früh zurückzukehren — beides ist Absicht (Timing-Leck über Token-Präfix bzw. über die Anzahl existierender Secrets) und darf durch Task 2 nicht verlorengehen.
- **Die Tasks sind unabhängig** und können in beliebiger Reihenfolge oder parallel ausgeführt werden. Einzige Ausnahme: Task 2 sollte vor einem produktiven Ausrollen der Manifeste aus Phase 8 fertig sein, weil deren API-`ClusterRole` heute den weiten Zugriff festschreibt.

---

### Task 1: Belastbares Quota-Signal aus dem Runner

**Offener Punkt §15.7.** `BlueMapRenderReconciler` erkennt ein erschöpftes Speicherkontingent heute daran, dass die Terminierungsmeldung des Pods bestimmte Zeichenketten enthält (`UNAMBIGUOUS_QUOTA_TOKENS`, plus „quota" in Verbindung mit `bucket`/`rgw`/`ceph`). Das Kubelet-Vokabular enthält „quota" nie, und die Meldung ist ein Log-Ausschnitt ohne Vertrag.

**Files:**
- Modify: `runner/entrypoint.sh`
- Modify: `runner/README.md` (Exit-Code-Tabelle)
- Modify: `operator/src/main/java/net/onelitefeather/apus/operator/render/BlueMapRenderReconciler.java`
- Modify: `operator/src/test/java/net/onelitefeather/apus/operator/render/BlueMapRenderReconcilerTest.java`

**Interfaces:**
- Produces: Exit-Code `6` des Runner-Containers als Vertrag „Speicherkontingent erschöpft". Die bestehende `quotaExceededMessage(Pod)`-Heuristik bleibt als Fallback erhalten, wird aber nachrangig.

- [ ] **Schritt 1: Feststellen, wo der Quota-Fehler tatsächlich auftritt**

Run: `grep -n 'exit\|bluemap' runner/entrypoint.sh`
Expected: die Stelle, an der der BlueMap-CLI-Aufruf endet und sein Exit-Code ausgewertet wird.

Run: `grep -rn 'QuotaExceeded\|quota' runner/bin/*.sh runner/README.md`
Expected: heute nichts. Damit ist belegt, dass der Runner das Signal derzeit nirgends erzeugt — genau die Lücke aus §15.7.

Der Fehler entsteht beim Schreiben in den Map-Bucket, also innerhalb von BlueMap über `BlueMapS3Storage`, nicht im `bundle-sync` (der liest). Er erscheint folglich in BlueMaps Ausgabe, nicht als eigener Prozess-Exit.

- [ ] **Schritt 2: Failing test auf Reconciler-Seite schreiben**

In `BlueMapRenderReconcilerTest`:

```java
@Test
void treatsExitCode6AsAStorageQuotaFailure() {
    // Exit code 6 is the runner's contract for "the tenant's storage quota is exhausted"
    // (runner/README.md). Unlike the log-text heuristic this is a promise the image makes,
    // so it must win over any message parsing.
    Pod pod = podTerminatedWith(6, "");

    Optional<String> message = reconciler.quotaExceededMessage(pod);

    assertTrue(message.isPresent());
    assertTrue(message.get().contains("quota"), message.get());
}

@Test
void doesNotTreatOtherExitCodesAsQuotaFailures() {
    // Exit code 3 is "bundle sync failed", 4 "bundle not found", 5 "invalid configuration".
    // None of them must end the render as StorageQuotaExceeded, because all three are
    // retryable and a quota failure deliberately is not.
    for (int code : new int[] {1, 3, 4, 5}) {
        assertTrue(reconciler.quotaExceededMessage(podTerminatedWith(code, "")).isEmpty(), "exit " + code);
    }
}

@Test
void stillFallsBackToTheMessageHeuristicForOlderRunnerImages() {
    // A cluster can be running an older runner image than the operator; the heuristic
    // stays as a fallback rather than being deleted.
    Pod pod = podTerminatedWith(1, "software.amazon.awssdk...: QuotaExceeded");

    assertTrue(reconciler.quotaExceededMessage(pod).isPresent());
}
```

`podTerminatedWith(int exitCode, String message)` als Hilfsmethode ergänzen, die einen `Pod` mit `status.containerStatuses[0].state.terminated.exitCode` und `.message` baut — analog zu den bereits vorhandenen Pod-Fixtures der Klasse.

- [ ] **Schritt 3: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :operator:test --tests '*BlueMapRenderReconcilerTest*'`
Expected: FAIL — der erste Test, weil Exit-Code 6 heute nichts bedeutet.

- [ ] **Schritt 4: Reconciler anpassen**

`quotaExceededMessage(Pod)` prüft zuerst den Exit-Code:

```java
/** Exit code the runner image uses for "the tenant's storage quota is exhausted". */
public static final int RUNNER_EXIT_QUOTA_EXCEEDED = 6;
```

und liefert bei `exitCode == 6` unmittelbar eine Meldung zurück, ohne Textanalyse. Erst danach greift die bestehende Musterprüfung. Den Klassen-Javadoc (Zeilen 82–89) entsprechend aktualisieren: Das Verfahren ist ab jetzt vertragsbasiert mit Heuristik als Rückfallebene, nicht mehr umgekehrt.

- [ ] **Schritt 5: Tests grün**

Run: `./gradlew :operator:test --tests '*BlueMapRenderReconcilerTest*'`
Expected: PASS

- [ ] **Schritt 6: Runner den Exit-Code tatsächlich setzen lassen**

In `runner/entrypoint.sh` die BlueMap-Ausgabe mitschreiben und nach dem Lauf auswerten:

```bash
# BlueMap exits non-zero for every failure alike. A storage-quota failure, though, must not
# be retried (design spec §12) -- so it gets its own exit code rather than being inferred
# from log text by the operator. The patterns are S3 error codes RGW returns once a user
# quota is exhausted; they are matched only when BlueMap itself failed.
readonly EXIT_QUOTA_EXCEEDED=6

java -jar /opt/bluemap/cli.jar "${bluemap_args[@]}" 2>&1 | tee /tmp/bluemap.log
bluemap_status="${PIPESTATUS[0]}"

if [ "$bluemap_status" -ne 0 ] \
   && grep -qiE 'quotaexceeded|exceededquota|quota.*(bucket|rgw|ceph)' /tmp/bluemap.log; then
  echo "storage quota exhausted while writing the map output" >&2
  exit "$EXIT_QUOTA_EXCEEDED"
fi

exit "$bluemap_status"
```

Der genaue Einbau richtet sich nach der in Schritt 1 gefundenen Stelle; die Bedingung „nur wenn BlueMap ohnehin fehlgeschlagen ist" ist wesentlich, sonst kippt ein Render, der das Wort nur beiläufig geloggt hat.

- [ ] **Schritt 7: Exit-Code-Verhalten des Skripts prüfen**

```bash
docker build -f runner/Dockerfile -t apus-runner:quota-test .
docker run --rm --entrypoint bash apus-runner:quota-test -c '
  echo "software.amazon.awssdk: QuotaExceeded" > /tmp/bluemap.log
  if grep -qiE "quotaexceeded|exceededquota|quota.*(bucket|rgw|ceph)" /tmp/bluemap.log; then exit 6; fi
  exit 0'
echo "exit=$?"
```

Expected: `exit=6`

- [ ] **Schritt 8: `runner/README.md` um die Exit-Code-Tabelle ergänzen**

| Code | Bedeutung | Wiederholbar |
|---|---|---|
| 0 | Render erfolgreich | — |
| 1 | Allgemeiner Fehler | ja |
| 3 | Bundle-Sync fehlgeschlagen | ja |
| 4 | Bundle oder Manifest nicht gefunden | nein |
| 5 | Ungültige Konfiguration | nein |
| 6 | Speicherkontingent erschöpft | **nein** |

- [ ] **Schritt 9: Commit**

```bash
git add runner/entrypoint.sh runner/README.md operator/src/main/java/net/onelitefeather/apus/operator/render/BlueMapRenderReconciler.java operator/src/test/java/net/onelitefeather/apus/operator/render/BlueMapRenderReconcilerTest.java
git commit -m "feat: give the runner a dedicated exit code for exhausted storage quota"
```

---

### Task 2: Push-Token-Lookup ohne clusterweites Secret-Leserecht

**Offener Punkt §15.9.** `FabricPushTokenRepository#resolveNamespace` sucht per Label über alle Namespaces. RBAC kann einen Label-Filter nicht einschränken, also braucht die API heute `get`/`list` auf **alle** Secrets im Cluster. Der Klassen-Javadoc skizziert den schmaleren Weg bereits — er wurde nur nicht umgesetzt.

**Files:**
- Modify: `api/src/main/java/net/onelitefeather/apus/api/rest/push/FabricPushTokenRepository.java`
- Modify: `api/src/test/java/net/onelitefeather/apus/api/rest/push/FabricPushTokenRepositoryTest.java`
- Modify: `deploy/base/api-rbac.yaml` (aus Phase 8, Task 3)

**Interfaces:**
- Consumes: `PushTokenSecrets.SECRET_NAME` (fester Name), `TenantRepository` (listet die cluster-scoped `Tenant`-Ressourcen), `TenantReconciler.namespaceFor(...)` (Namespace-Konvention).
- Produces: unverändert `Optional<String> resolveNamespace(String rawToken)` — die Signatur bleibt, nur der Weg dahinter ändert sich.

- [ ] **Schritt 1: Bestehende Zusicherungen der Klasse dokumentiert festhalten**

Run: `sed -n '60,140p' api/src/main/java/net/onelitefeather/apus/api/rest/push/FabricPushTokenRepository.java`
Expected: Der Javadoc beschreibt drei Eigenschaften, die erhalten bleiben müssen: konstantzeitiger Vergleich über `MessageDigest.isEqual`, erschöpfende Prüfung ohne frühen Ausstieg, und dass ein Fehlschlag keinen Hinweis auf existierende Mandanten gibt.

Run: `grep -c '@Test' api/src/test/java/net/onelitefeather/apus/api/rest/push/FabricPushTokenRepositoryTest.java`
Expected: eine Zahl > 0. Diese Tests sind die Absicherung des Umbaus — sie müssen nach dem Umbau unverändert grün sein.

- [ ] **Schritt 2: Failing test für den neuen Zugriffsweg schreiben**

```java
@Test
void readsOnlyTheFixedNameSecretInTenantNamespaces() {
    // The whole point of the rewrite: no cluster-wide secret listing. The fake client
    // records every request; a `list` on secrets means the RBAC grant could not be
    // narrowed and the change failed its purpose.
    server.expect()
            .get()
            .withPath("/apis/bluemap.onelitefeather.net/v1alpha1/tenants")
            .andReturn(200, tenantList("friends-server", "other-server"))
            .always();
    server.expect()
            .get()
            .withPath("/api/v1/namespaces/bluemap-friends-server/secrets/" + PushTokenSecrets.SECRET_NAME)
            .andReturn(200, secretWithToken("the-token"))
            .always();
    server.expect()
            .get()
            .withPath("/api/v1/namespaces/bluemap-other-server/secrets/" + PushTokenSecrets.SECRET_NAME)
            .andReturn(404, null)
            .always();

    assertEquals(Optional.of("bluemap-friends-server"), repository.resolveNamespace("the-token"));

    assertTrue(
            server.getRequestCount() > 0
                    && requestPaths(server).stream().noneMatch(p -> p.matches("/api/v1/secrets.*")),
            "must not list secrets cluster-wide");
}

@Test
void keepsCheckingEveryTenantAfterAMatch() {
    // Stopping at the first match would leak, through response timing, how many tenants
    // exist before the caller's own -- the property the current implementation protects.
    // Three tenants, the match sitting on the first one: all three secrets must still
    // have been fetched.
    server.expect()
            .get()
            .withPath("/apis/bluemap.onelitefeather.net/v1alpha1/tenants")
            .andReturn(200, tenantList("a-server", "b-server", "c-server"))
            .always();
    expectSecret("bluemap-a-server", "the-token");
    expectSecret("bluemap-b-server", "other-token");
    expectSecret("bluemap-c-server", "third-token");

    assertEquals(Optional.of("bluemap-a-server"), repository.resolveNamespace("the-token"));

    List<String> paths = requestPaths(server);
    for (String namespace : List.of("bluemap-a-server", "bluemap-b-server", "bluemap-c-server")) {
        assertTrue(
                paths.contains("/api/v1/namespaces/" + namespace + "/secrets/" + PushTokenSecrets.SECRET_NAME),
                "did not read the secret in " + namespace + "; the scan returned early");
    }
}

@Test
void returnsEmptyForAnUnknownTokenWithoutRevealingTenants() {
    assertEquals(Optional.empty(), repository.resolveNamespace("wrong-token"));
}

@Test
void toleratesATenantWithoutAPushTokenSecret() {
    // A tenant that never had a service token issued yields 404 on the get; that is a
    // normal state, not an error, and must not abort the scan for the remaining tenants.
    server.expect()
            .get()
            .withPath("/apis/bluemap.onelitefeather.net/v1alpha1/tenants")
            .andReturn(200, tenantList("no-token-server", "friends-server"))
            .always();
    server.expect()
            .get()
            .withPath("/api/v1/namespaces/bluemap-no-token-server/secrets/" + PushTokenSecrets.SECRET_NAME)
            .andReturn(404, null)
            .always();
    expectSecret("bluemap-friends-server", "the-token");

    assertEquals(Optional.of("bluemap-friends-server"), repository.resolveNamespace("the-token"));
}
```

- [ ] **Schritt 3: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :api:test --tests '*FabricPushTokenRepositoryTest*'`
Expected: FAIL — die neuen Tests, weil die Implementierung noch labelbasiert clusterweit sucht.

- [ ] **Schritt 4: `resolveNamespace` umbauen**

Neuer Ablauf: die cluster-scoped `Tenant`-Ressourcen listen, für jeden den Namespace über die bestehende Konvention bilden, und dort ein `get` auf das Secret mit festem Namen absetzen. Über alle Ergebnisse erschöpfend und konstantzeitig vergleichen, wie bisher. `404` je Namespace ist ein regulärer Fall.

Den Javadoc-Abschnitt, der den weiten Zugriff als bewusste Abwägung beschreibt, durch die Beschreibung des jetzt umgesetzten Wegs ersetzen — inklusive der RBAC-Regel, die er ermöglicht.

- [ ] **Schritt 5: Alle Tests der Klasse grün, auch die alten**

Run: `./gradlew :api:test --tests '*FabricPushTokenRepositoryTest*' --tests '*PushControllerTest*'`
Expected: PASS, ohne dass ein vorbestehender Test angepasst werden musste. War eine Anpassung nötig, ist das ein Signal, dass sich beobachtbares Verhalten geändert hat — dann prüfen, ob das beabsichtigt ist.

- [ ] **Schritt 6: RBAC verengen**

In `deploy/base/api-rbac.yaml` die weite Secret-Regel ersetzen:

```yaml
  # Service-token lookup, narrowed in phase 9: the API only ever reads the one Secret
  # literally named apus-push-token, in tenant namespaces it discovers through the
  # cluster-scoped Tenant resources. It can no longer read any other secret anywhere.
  - apiGroups: [""]
    resources: ["secrets"]
    resourceNames: ["apus-push-token"]
    verbs: ["get"]
```

Den Warnhinweis-Kommentar, der auf §15.9 verwies, entfernen.

- [ ] **Schritt 7: Prüfen, dass `resourceNames` den tatsächlichen Secret-Namen trifft**

Run: `grep -n 'SECRET_NAME' api/src/main/java/net/onelitefeather/apus/api/rest/push/PushTokenSecrets.java`
Expected: der Wert stimmt exakt mit `resourceNames` überein. Weicht er ab, liest die API im Cluster gar nichts mehr und jeder Push schlägt mit 403 fehl.

- [ ] **Schritt 8: Design-Spec §15, Punkt 9 als erledigt markieren**

```markdown
9. ~~**RBAC für den Push-Token-Lookup der API breiter als ideal.**~~ **Erledigt (Phase 9).**
   `FabricPushTokenRepository#resolveNamespace` enumeriert die cluster-scoped
   `Tenant`-Ressourcen und liest je Mandanten-Namespace gezielt das Secret mit festem Namen
   `apus-push-token` — nie mehr `list` über alle Secrets. Die Berechtigung der API ist
   entsprechend auf `resourceNames: ["apus-push-token"]`, `verbs: ["get"]` verengt. Der
   konstantzeitige, erschöpfende Vergleich bleibt unverändert erhalten.
```

- [ ] **Schritt 9: Commit**

```bash
git add api/ deploy/base/api-rbac.yaml docs/superpowers/specs/2026-08-08-apus-design.md
git commit -m "fix: read only the fixed-name push token secret instead of listing all secrets"
```

---

### Task 3: Identity-Broker auswählen und die Anmeldung gegen einen echten Broker prüfen

**Offene Punkte §0 und §15.3.** Die API validiert JWTs gegen einen konfigurierbaren Issuer, aber welches Produkt davor steht, ist nicht entschieden, und ein Lauf gegen einen echten Broker hat nie stattgefunden — die Auth-Tests arbeiten mit selbst ausgestellten Test-JWTs.

**Files:**
- Create: `docs/superpowers/specs/2026-08-12-identity-broker-entscheidung.md`
- Modify: `settings.gradle.kts` (Keycloak-Testcontainer)
- Modify: `api/build.gradle.kts`
- Create: `api/src/test/java/net/onelitefeather/apus/api/security/RealBrokerAuthIntegrationTest.java`
- Create: `api/src/test/resources/keycloak/apus-realm.json`
- Create: `api/src/test/resources/keycloak/upstream-realm.json` (spielt den eigenen IdP eines Mandanten)
- Modify: `docs/superpowers/specs/2026-08-08-apus-design.md`

- [ ] **Schritt 1: Entscheidungsvorlage schreiben**

`docs/superpowers/specs/2026-08-12-identity-broker-entscheidung.md`, mit Struktur:

- **Anforderung** aus §10.3: Organisationen mit eigenem Identity-Provider je Organisation, Einladungs-Flows, ein einziger Issuer für Apus, Organisations-Claim bestimmt den Mandanten, Rollen `platform-admin`/`tenant-owner`/`tenant-operator`/`tenant-viewer`, mandantengebundene Service-Tokens mit Scope `world:push`.

- **K.-o.-Kriterium: Rollenvergabe je Mandant, in beiden Anmeldewegen gleichermaßen.** Ein Mandant, der seinen eigenen IdP föderiert, und ein Mandant mit lokalen Accounts im Broker müssen **dieselbe** Rollenstruktur bekommen — dieselben vier Rollen, mandantenspezifisch vergeben, im Token an derselben Stelle und in derselben Form. Nur dann kommt die API mit einer einzigen Auswertung aus, statt zwei Token-Formate unterscheiden zu müssen. Das ist die Anforderung, an der die Produktwahl hängt, und sie ist kein Selbstläufer:
  - Ein Broker, dessen Rollen realm- oder mandantenweit definiert sind statt je Organisation, erzwingt eine Behelfslösung über Gruppen oder Attribute. Die ist machbar, aber sie muss dann für den föderierten und den lokalen Weg identisch aussehen — sonst trägt ein föderierter Nutzer seine Rolle in einem anderen Claim als ein lokaler.
  - Bei Föderation entscheidet zusätzlich das Mapping vom fremden IdP: Rollen dürfen **nicht** aus dem föderierten Token übernommen werden, sonst bestimmt der Mandant selbst, wer bei ihm `tenant-owner` ist — und nichts hindert einen fremden IdP daran, `platform-admin` zu behaupten. Die Rolle muss im Apus-Broker vergeben und dort in den ausgestellten Token geschrieben werden.
- **Kandidaten:** Keycloak ab 26 und Zitadel — beide in §10.3 bereits genannt. Beide bringen ein Organisationskonzept mit; sie unterscheiden sich darin, wie eng Rollen an eine Organisation gebunden werden können. Genau dieser Unterschied ist am K.-o.-Kriterium zu messen, nicht aus der Produktdokumentation abzuschreiben: In Schritt 3 steht ein Test bereit, der die Frage praktisch beantwortet.
- **Bewertungskriterien**, je Kandidat zu belegen statt zu behaupten: Rollenvergabe je Organisation im föderierten *und* im lokalen Weg (K.-o., siehe oben); Organisationsmodell und dessen Abbildung auf einen Token-Claim; Föderation je Organisation; Einladungs-Flow; Service-Accounts mit engem Scope; Betriebsaufwand im bestehenden Cluster (der laut §2 bereits einen OIDC-Provider für Outline, Grafana und Dependency-Track betreibt — welcher, ist zu ermitteln und wiegt schwer); Upgrade-Pfad.
- **Entscheidung** mit Begründung.
- **Konsequenz:** der konkrete Claim-Name, aus dem der Mandant abgeleitet wird, und wie Rollen im Token erscheinen.

Run: `gh api repos/OneLiteFeatherNET/Kubernetes-FLUX/contents --jq '.[].name' 2>/dev/null | head -30`
Expected: eine Verzeichnisliste des Cluster-Repositories. Darin nach dem heute betriebenen OIDC-Provider suchen — die Entscheidung sollte ihn schwer gewichten, weil ein zweiter Broker im selben Cluster dauerhaft Betriebsaufwand ist.

- [ ] **Schritt 2: Testcontainer in den Katalog aufnehmen**

```kotlin
// Keycloak Testcontainer: proves the auth path against a real broker rather than
// self-issued test JWTs (design spec §15, point 3). Version verified against Maven
// Central on 2026-08-12.
version("keycloak-testcontainer", "3.7.0")
library("testcontainers.keycloak", "com.github.dasniko", "testcontainers-keycloak")
    .versionRef("keycloak-testcontainer")
```

In `api/build.gradle.kts`: `testImplementation(libs.testcontainers.keycloak)`.

Fällt die Entscheidung in Schritt 1 auf Zitadel, tritt an diese Stelle dessen Container-Image über `GenericContainer`; der Rest des Tasks bleibt unverändert, weil beide OIDC sprechen.

- [ ] **Schritt 3: Failing test schreiben**

```java
package net.onelitefeather.apus.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dasniko.testcontainers.keycloak.KeycloakContainer;
// ... weitere Importe

/**
 * Proves the authentication path end to end against a real identity broker.
 *
 * <p>Every other auth test in this module signs its own JWTs, which means they prove that
 * the code accepts tokens it minted itself -- not that it accepts the tokens the broker in
 * front of Apus actually issues. Discovery, JWKS rotation, claim shape and audience
 * handling are exactly the parts a self-signed token cannot exercise.
 *
 * <p>Named {@code *IntegrationTest} so it stays out of the pull-request build; it needs
 * Docker.
 */
class RealBrokerAuthIntegrationTest {

    static KeycloakContainer keycloak = new KeycloakContainer()
            .withRealmImportFile("keycloak/apus-realm.json");

    @Test
    void acceptsATokenIssuedByTheBrokerAndDerivesTheTenantFromIt() {
        String token = obtainToken("friends-user", "secret");

        HttpResponse<String> response = get("/api/sources", token);

        assertEquals(200, response.statusCode());
        // The tenant comes from the organisation claim, never from the request.
        assertTrue(response.body().contains("friends-server"), response.body());
    }

    @Test
    void rejectsATokenFromADifferentIssuer() {
        // A token that is structurally valid and correctly signed, but by someone else.
        assertEquals(401, get("/api/sources", foreignIssuerToken()).statusCode());
    }

    @Test
    void rejectsAnExpiredToken() {
        assertEquals(401, get("/api/sources", expiredToken()).statusCode());
    }

    @Test
    void mapsBrokerRolesOntoApusRoles() {
        // platform-admin may list tenants; tenant-viewer may not.
        assertEquals(200, get("/api/tenants", obtainToken("platform-admin-user", "secret")).statusCode());
        assertEquals(403, get("/api/tenants", obtainToken("friends-viewer", "secret")).statusCode());
    }

    @Test
    void grantsTheSameRoleStructureToLocalAndFederatedUsers() {
        // The core requirement: a tenant with its own federated IdP and a tenant with local
        // broker accounts must yield the same four roles, tenant-scoped, in the same claim.
        // If these two tokens differ in shape, the API needs two evaluation paths -- which
        // is exactly what the product choice is meant to avoid.
        String local = obtainToken("friends-operator", "secret");            // local account
        String federated = obtainFederatedToken("other-operator", "secret"); // via the org's IdP

        assertEquals(rolesOf(local), rolesOf(federated), "role claim differs between login paths");
        assertEquals(Set.of("tenant-operator"), rolesOf(local));

        // Same role, same permissions, different tenants.
        assertEquals(200, post("/api/maps/survival-overworld/render", local).statusCode());
        assertEquals(200, post("/api/maps/other-overworld/render", federated).statusCode());
    }

    @Test
    void scopesRolesToTheOwnTenantOnly() {
        // A tenant-owner of one tenant is nobody in another. Without per-tenant role
        // scoping, an owner role granted anywhere would be an owner role everywhere.
        String friendsOwner = obtainToken("friends-owner", "secret");

        assertEquals(200, get("/api/sources", friendsOwner).statusCode());
        // The other tenant's map is treated as non-existent, not as forbidden -- otherwise
        // the API is a directory of foreign tenants (design spec §11.1).
        assertEquals(404, post("/api/maps/other-overworld/render", friendsOwner).statusCode());
    }

    @Test
    void ignoresRolesClaimedByAFederatedIdentityProvider() {
        // The federated IdP is controlled by the tenant. If Apus took roles from the
        // upstream token, a tenant could declare itself platform-admin. Roles must come
        // from the Apus broker's own grant, never from the federated assertion.
        String token = obtainFederatedTokenClaiming("other-operator", "secret", "platform-admin");

        assertEquals(Set.of("tenant-operator"), rolesOf(token), "upstream role claim leaked through");
        assertEquals(403, get("/api/tenants", token).statusCode());
    }

    @Test
    void survivesAKeyRotation() {
        // The JWKS endpoint is re-fetched rather than cached forever: after the broker
        // rotates its signing key, previously working tokens fail and new ones work.
        String beforeRotation = obtainToken("friends-user", "secret");
        assertEquals(200, get("/api/sources", beforeRotation).statusCode());

        rotateRealmSigningKey();

        assertEquals(401, get("/api/sources", beforeRotation).statusCode());
        assertEquals(200, get("/api/sources", obtainToken("friends-user", "secret")).statusCode());
    }
}
```

Dazu zwei Realm-Definitionen unter `api/src/test/resources/keycloak/`:

- `apus-realm.json` — der Apus-Realm: zwei Organisationen (`friends-server`, `other-server`), die vier Rollen, und die lokalen Testnutzer `platform-admin-user`, `friends-owner`, `friends-operator`, `friends-viewer`. `other-server` bekommt **keine** lokalen Nutzer; seine Mitglieder kommen aus der Föderation.
- `upstream-realm.json` — spielt den eigenen IdP des Mandanten `other-server`. Er enthält `other-operator` und ist im Apus-Realm als Identity-Provider der Organisation `other-server` eingetragen. Genau diese Föderationsstrecke ist der zweite Anmeldeweg, den das K.-o.-Kriterium verlangt.

Beide Realms laufen im selben Keycloak-Container — ein zweiter Container wäre realistischer, kostet aber Laufzeit ohne die Frage besser zu beantworten: Für Apus ist der Upstream ohnehin nur ein OIDC-Endpunkt.

`rolesOf(String token)` dekodiert den Token und liefert die Apus-Rollen als `Set<String>` — an der Stelle, an der der Produktivcode sie liest, damit der Test nicht seine eigene Auswertung mitbringt und dabei am Code vorbeitestet.

- [ ] **Schritt 4: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :api:integrationTest --tests '*RealBrokerAuthIntegrationTest*'`
Expected: FAIL. Existiert im `api`-Modul noch kein `integrationTest`-Task, ihn im selben Muster wie in `operator/build.gradle.kts` anlegen (Ausschluss von `**/*IntegrationTest.class` aus `test`, eigener Task mit denselben Source-Sets).

- [ ] **Schritt 5: Auth-Konfiguration so lange anpassen, bis der Test grün ist**

Erwartbare Befunde — jeder davon ist ein echter Fund, den die Test-JWTs bisher verdeckt haben: Der Claim-Name für die Organisation weicht vom angenommenen ab; die Rollen stecken verschachtelt in `realm_access.roles` statt flach in `roles`; die Audience-Prüfung ist zu lax oder zu streng; die JWKS-URL wird nur einmal geholt. Jeder dieser Punkte wird in `api/src/main/java/net/onelitefeather/apus/api/security/` behoben, nicht im Test weggemappt.

**Der wahrscheinlichste harte Befund betrifft `grantsTheSameRoleStructureToLocalAndFederatedUsers`.** Wenn die Rollen des gewählten Brokers realm- statt organisationsweit definiert sind, lässt sich „`tenant-operator` bei `friends-server`, aber nirgends sonst" nicht direkt ausdrücken. Zwei Auswege, in dieser Reihenfolge zu prüfen:

1. **Der Broker kann es nativ** — Rollen bzw. Grants je Organisation. Dann ist nichts zu tun außer sie so anzulegen.
2. **Der Broker kann es nicht** — dann Rollen als Gruppen der Form `<tenant>:<rolle>` modellieren und über einen Protocol-Mapper in einen flachen Claim schreiben. Der Mapper muss für lokale und föderierte Nutzer **derselbe** sein; nur so bleibt der Token in beiden Wegen gleich geformt. Diese Behelfslösung gehört dann ausdrücklich in die Entscheidungsvorlage aus Schritt 1 und in §10.3 der Spec — sie ist Betriebswissen, das sonst nur im Realm-Export steht.

Fällt der Broker in Fall 2, ist das ein starkes Argument für den jeweils anderen Kandidaten. Der Test ist der Ort, an dem sich das entscheidet, bevor Betriebsaufwand entsteht.

- [ ] **Schritt 6: Tests grün**

Run: `./gradlew :api:test :api:integrationTest`
Expected: BUILD SUCCESSFUL

- [ ] **Schritt 7: Spec nachziehen**

§0 und §15 Punkt 3: Produktwahl eintragen, den Verweis auf „nie gegen einen echten Broker getestet" streichen und durch den Test verweisen.

§10.3 um drei Angaben ergänzen, die dort heute fehlen und ohne die niemand einen zweiten Mandanten anlegen kann:
1. Der konkrete Claim-Name, aus dem der Mandant abgeleitet wird, und die Form, in der die Rollen im Token stehen.
2. Dass die Rollenstruktur für beide Anmeldewege identisch ist — föderierter IdP des Mandanten und lokale Accounts im Broker — samt der Modellierung, die das erreicht (nativ organisationsgebundene Rollen oder die Gruppen-Behelfslösung aus Schritt 5).
3. Dass Rollen **niemals** aus einem föderierten Token übernommen werden, sondern ausschließlich im Apus-Broker vergeben werden. Das ist keine Feinheit, sondern die Grenze, ab der ein Mandant sich sonst selbst zum `platform-admin` erklären könnte.

- [ ] **Schritt 8: Commit**

```bash
git add docs/superpowers/specs/ settings.gradle.kts api/
git commit -m "feat: verify the auth path against a real identity broker"
```

---

### Task 4: Das Save-Fenster von `paper-worldpush` prüfen

**Offener Punkt §15.8.** `BukkitSaveCoordinator` pausiert das Autosave und erzwingt einen Save, bevor kopiert wird. Ob das kurze Fenster auf einem laufenden Server einen konsistenten Snapshot liefert, wurde nie geprüft — es existiert nur Unit-Abdeckung für Kopierlogik, Konfiguration und den HTTP-Report-Weg.

**Files:**
- Modify: `settings.gradle.kts` (MockBukkit)
- Modify: `paper-worldpush/build.gradle.kts`
- Create: `paper-worldpush/src/test/java/net/onelitefeather/apus/paper/BukkitSaveCoordinatorTest.java`
- Create: `paper-worldpush/src/test/java/net/onelitefeather/apus/paper/PushCycleConsistencyTest.java`
- Modify: `docs/superpowers/specs/2026-08-08-apus-design.md`

- [ ] **Schritt 1: Die tatsächliche Reihenfolge im Code feststellen**

Run: `cat paper-worldpush/src/main/java/net/onelitefeather/apus/paper/BukkitSaveCoordinator.java`
Expected: die exakte Abfolge aus `disableAutoSave()`, `save()`/`forceSave()` und Wiederaktivierung, sowie auf welchem Thread sie läuft. Der Test muss genau diese Abfolge prüfen, nicht eine vermutete.

Run: `grep -n 'SaveCoordinator\|copier' paper-worldpush/src/main/java/net/onelitefeather/apus/paper/PushCycleRunner.java`
Expected: wo der Koordinator im Zyklus aufgerufen wird — das ist die Naht, an der die Konsistenzfrage hängt.

- [ ] **Schritt 2: MockBukkit aufnehmen**

```kotlin
// MockBukkit: the design spec (§13.2) called for it from the start; without it
// BukkitSaveCoordinator -- the one class that talks to the running server -- has no test
// at all. Version must match the Paper API generation this module builds against.
version("mockbukkit", "4.62.2")
library("mockbukkit", "org.mockbukkit.mockbukkit", "mockbukkit-v1.21").versionRef("mockbukkit")
```

Die passende Artefakt- und Versionskombination gegen die im Katalog gepinnte Paper-API prüfen:

Run: `grep -n 'paper' settings.gradle.kts | head`
Expected: die Paper-API-Version. MockBukkits Artefaktname trägt die Minecraft-Generation im Namen; sie muss dazu passen, sonst startet der Mock-Server mit einer Registry-Fehlermeldung.

- [ ] **Schritt 3: Failing test für den Koordinator schreiben**

```java
/**
 * Covers the save window itself -- the one part of paper-worldpush that talks to a running
 * server and, until now, had no test at all (design spec §15, point 8).
 */
class BukkitSaveCoordinatorTest {

    ServerMock server;
    World world;
    List<String> calls;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        // MockBukkit's World does not record the call order by itself; a recording wrapper
        // is what turns "both happened" into "they happened in this order".
        calls = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void disablesAutoSaveBeforeForcingASave() {
        // Order matters: forcing a save while autosave is still running can interleave two
        // writers on the same region file, which is precisely the corruption this class exists
        // to prevent.
        world.setAutoSave(true);

        new BukkitSaveCoordinator(server).withSaveWindow(world, () -> calls.add("copy"));

        assertEquals(List.of("autoSaveOff", "save", "copy", "autoSaveOn"), calls);
    }

    @Test
    void restoresAutoSaveEvenWhenTheCopyFails() {
        // A push that throws must not leave the server with autosave permanently off --
        // that would silently stop persisting player progress.
        world.setAutoSave(true);

        assertThrows(
                IllegalStateException.class,
                () -> new BukkitSaveCoordinator(server).withSaveWindow(world, () -> {
                    throw new IllegalStateException("copy failed");
                }));

        assertTrue(world.isAutoSave(), "autosave stayed off after a failed push");
    }

    @Test
    void restoresTheOriginalAutoSaveStateRatherThanForcingItOn() {
        // A server that deliberately runs with autosave disabled must stay that way.
        world.setAutoSave(false);

        new BukkitSaveCoordinator(server).withSaveWindow(world, () -> {});

        assertFalse(world.isAutoSave(), "autosave was switched on by a push");
    }

    @Test
    void runsTheSaveOnTheMainThread() {
        // Bukkit's world save API is main-thread only; calling it from the async push
        // thread throws at runtime on a real server but silently passes against a mock
        // that does not enforce it -- so assert the thread explicitly.
        AtomicReference<Thread> saveThread = new AtomicReference<>();
        world.setAutoSave(true);

        CompletableFuture
                .runAsync(() -> new BukkitSaveCoordinator(server)
                        .withSaveWindow(world, () -> saveThread.set(Thread.currentThread())))
                .join();

        assertTrue(
                server.isOnMainThread(saveThread.get()),
                "the save ran on " + saveThread.get() + " instead of the server main thread");
    }
}
```

- [ ] **Schritt 4: Fehlschlag bestätigen**

Run: `./gradlew :paper-worldpush:test --tests '*BukkitSaveCoordinatorTest*'`
Expected: FAIL

- [ ] **Schritt 5: Konsistenztest über den ganzen Zyklus**

`PushCycleConsistencyTest`: MockBukkit-Server mit einer Welt, während des Kopierens werden Region-Dateien fortlaufend verändert. Geprüft wird, dass das kopierte Ergebnis dem Stand zum Zeitpunkt des erzwungenen Saves entspricht und keine halb geschriebene Region enthält.

Dieser Test kann echte Befunde produzieren. Findet er Inkonsistenzen, ist das **das erwartete Ergebnis dieses Tasks**, nicht sein Scheitern: §15.8 fragt genau danach. Der Fund gehört dann als eigener Abschnitt in die Spec und die Behebung in einen Folge-Task — nicht durch Abschwächen der Assertion aus der Welt geschafft.

- [ ] **Schritt 6: Tests grün, Befunde dokumentiert**

Run: `./gradlew :paper-worldpush:test`
Expected: BUILD SUCCESSFUL — bzw. ein dokumentierter, in der Spec festgehaltener Befund.

- [ ] **Schritt 7: Spec nachziehen**

§13.2 (Zeile `paper-worldpush`), §15 Punkt 8 und §0: den „Offen"-Vermerk durch das Testergebnis ersetzen. Bleibt der Lauf gegen einen echten Paper-Server unter Last aus (MockBukkit ersetzt ihn nicht vollständig), muss das ausdrücklich stehen bleiben — mit der Angabe, was MockBukkit abdeckt und was nicht.

- [ ] **Schritt 8: Commit**

```bash
git add settings.gradle.kts paper-worldpush/ docs/superpowers/specs/2026-08-08-apus-design.md
git commit -m "test: cover the paper-worldpush save window with MockBukkit"
```

---

### Task 5: Die `emptyDir`-Grenze messen

**Offener Punkt §15.6.** „`emptyDir` genügt bis zu einer Größe, die von der Node-Ausstattung abhängt; darüber ist ein PVC nötig." Die Grenze wurde nie gemessen, obwohl sie laut Spec vor Phase 2 nachzuholen war — und der Operator legt heute trotzdem einen Default fest.

**Files:**
- Create: `docs/superpowers/spikes/2026-08-12-emptydir-grenze.md`
- Create: `docs/superpowers/spikes/2026-08-12-emptydir-grenze/run-spike.sh`
- Modify: `operator/src/main/java/net/onelitefeather/apus/operator/render/RenderJobBuilder.java`
- Modify: `operator/src/test/java/net/onelitefeather/apus/operator/render/RenderJobBuilderTest.java`
- Modify: `docs/superpowers/specs/2026-08-08-apus-design.md`

- [ ] **Schritt 1: Den heutigen Default feststellen**

Run: `grep -n -B3 -A10 'emptyDir\|EmptyDir' operator/src/main/java/net/onelitefeather/apus/operator/render/RenderJobBuilder.java`
Expected: der aktuell erzeugte Volume-Typ und, falls vorhanden, ein `sizeLimit`. Das ist der Wert, den die Messung bestätigen oder widerlegen soll.

- [ ] **Schritt 2: Messskript schreiben**

`run-spike.sh` im Stil des vorhandenen Sharding-Spikes (`docs/superpowers/spikes/2026-08-09-lowres-sharding-spike/run-spike.sh` als Vorlage lesen). Es fährt gegen einen Cluster:
1. Node-Ausstattung erheben: `kubectl get nodes -o json | jq '.items[].status.allocatable["ephemeral-storage"]'`.
2. Render-Jobs mit `emptyDir` und wachsenden Welt-Größen starten (1, 5, 10, 20, 40 GiB Bundle).
3. Je Lauf festhalten: Erfolg/Misserfolg, Grund bei Misserfolg (`Evicted` mit `ephemeral-storage`-Bezug ist der gesuchte Fall), Spitzenverbrauch über `kubectl top pod`.
4. Die kleinste Größe ermitteln, bei der ein Lauf durch Eviction scheitert.

- [ ] **Schritt 3: Messung durchführen und Bericht schreiben**

`2026-08-12-emptydir-grenze.md` nach dem Muster des Sharding-Spike-Berichts: Aufbau, Messwerte als Tabelle, Rohdaten unter `evidence/`, Auswertung, Entscheidung.

Die Auswertung muss beantworten: Ab welcher Bundle-Größe reicht `emptyDir` auf der real vorhandenen Node-Ausstattung nicht mehr? Welcher Default folgt daraus? Ab welcher Größe soll der Operator automatisch auf ein PVC wechseln — oder soll er es nie automatisch tun und stattdessen ein Feld in der CR verlangen?

- [ ] **Schritt 4: Failing test für das Ergebnis schreiben**

Sobald die Entscheidung feststeht, in `RenderJobBuilderTest`:

`MEASURED_LIMIT_GIB` ist der in Schritt 3 gemessene Wert; er wird als benannte Konstante in `RenderJobBuilder` geführt, damit im Test und im Produktivcode derselbe Wert steht.

```java
@Test
void usesAPersistentVolumeClaimForBundlesAboveTheMeasuredLimit() {
    // The limit comes from the phase 9 spike (docs/superpowers/spikes/2026-08-12-emptydir-grenze.md),
    // not from a guess: below it emptyDir is faster and cheaper, above it the pod gets
    // evicted mid-render.
    long aboveLimit = (RenderJobBuilder.EMPTY_DIR_LIMIT_BYTES) + 1;

    Job job = builder.build(renderWithBundleSize(aboveLimit), mapFixture(), configFixture());

    Volume volume = workVolumeOf(job);
    assertNotNull(volume.getPersistentVolumeClaim(), "expected a PVC above the measured limit");
    assertNull(volume.getEmptyDir());
}

@Test
void usesEmptyDirBelowTheLimit() {
    long belowLimit = RenderJobBuilder.EMPTY_DIR_LIMIT_BYTES - 1;

    Job job = builder.build(renderWithBundleSize(belowLimit), mapFixture(), configFixture());

    Volume volume = workVolumeOf(job);
    assertNotNull(volume.getEmptyDir(), "expected an emptyDir below the measured limit");
    assertNull(volume.getPersistentVolumeClaim());
}

@Test
void setsASizeLimitOnTheEmptyDirSoAnOverrunEvictsPredictably() {
    // Without sizeLimit an overrunning pod can fill the node's disk and take unrelated
    // workloads down with it.
    Job job = builder.build(renderWithBundleSize(1_000_000L), mapFixture(), configFixture());

    assertNotNull(workVolumeOf(job).getEmptyDir().getSizeLimit(), "emptyDir has no sizeLimit");
}
```

`renderWithBundleSize(long)` baut einen `BlueMapRender`, dessen referenziertes Bundle-Manifest die angegebene `sizeBytes` trägt (Feld aus Design-Spec §5), `workVolumeOf(Job)` liest das Volume heraus, auf das der `bluemap`-Container mountet. Beide als Hilfsmethoden der Testklasse ergänzen, im Stil der dort bereits vorhandenen Fixtures.

- [ ] **Schritt 5: `RenderJobBuilder` anpassen und Tests grün bekommen**

Run: `./gradlew :operator:test --tests '*RenderJobBuilderTest*'`
Expected: PASS

- [ ] **Schritt 6: Spec nachziehen**

§15 Punkt 6 durch das Messergebnis ersetzen, mit Verweis auf den Spike-Bericht — im selben Stil, in dem §14 Phase 4 auf den Sharding-Spike verweist. §7.1 („`emptyDir` (oder PVC bei großen Welten)") um die konkrete Grenze ergänzen.

- [ ] **Schritt 7: Commit**

```bash
git add docs/superpowers/spikes/2026-08-12-emptydir-grenze* operator/ docs/superpowers/specs/2026-08-08-apus-design.md
git commit -m "feat: pick the render volume type from a measured limit instead of a guess"
```

---

## Reihenfolge und Abhängigkeiten

| Task | Blockiert von | Kann parallel zu |
|---|---|---|
| 1 — Quota-Exit-Code | — | 2, 3, 4, 5 |
| 2 — Push-Token-RBAC | Phase 8 Task 3 (die Datei, die verengt wird) | 1, 3, 4, 5 |
| 3 — Identity-Broker | — (die Produktentscheidung in Schritt 1 ist der einzige Blocker) | 1, 2, 4, 5 |
| 4 — Save-Fenster | — | 1, 2, 3, 5 |
| 5 — `emptyDir`-Grenze | Zugang zu einem Cluster mit realistischer Node-Ausstattung | 1, 2, 3, 4 |

Task 3 und Task 5 tragen echte Unsicherheit: Beide können ein Ergebnis liefern, das Folgearbeit auslöst — ein Claim-Format, das die Rollenabbildung ändert, oder eine Grenze, die einen PVC-Pfad im Operator nötig macht, den es heute nicht gibt. Das ist kein Planungsfehler, sondern der Grund, warum diese Punkte offen sind.

## Was dieser Plan bewusst nicht abdeckt

- **§15 Punkt 5 (`render-mask` und Kanten).** Er ist ausdrücklich nur relevant, falls in Phase 4 der Maskenweg gewählt worden wäre. Nach der Absage an Sharding (§14, Phase 4) hat er keinen Gegenstand mehr und sollte in der Spec als gegenstandslos markiert statt abgearbeitet werden.
- **§15 Punkt 2 (Bucket-Notifications als zweiter Erkennungsweg).** Die Spec führt ihn selbst nicht mehr als offen, sondern als mögliche spätere Härtung für den Fall, dass ein Schreiber seinen Completion-Callback verliert. Das ist ein eigenes Feature, keine Härtung des Bestehenden.
- **Eine CI-Matrix über mehrere BlueMap-Versionen.** Braucht zuerst einen parametrierbaren Contract-Test; siehe den Abschluss des Phase-7-Plans.
