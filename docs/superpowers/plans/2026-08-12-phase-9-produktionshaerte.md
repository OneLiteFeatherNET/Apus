# Apus Phase 9 — Production Hardening: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Work through the five points the design spec itself lists as unresolved in §15 — so that production operation does not rest on heuristics, untested assumptions and overly broad permissions.

**Architecture:** Five independent hardening changes to the existing code. Two of them replace guesswork with contracts (quota exit code, push-token lookup), two close test gaps against real external systems (identity broker, Paper server), and one is a measurement whose result fixes a default in the CR.

**Tech Stack:** Java 25, JOSDK 5.5.1, Micronaut Security, Testcontainers (Keycloak, k3s, MinIO), MockBukkit, Bash (runner entrypoint).

## Global Constraints

- **Java toolchain 25**, AGPL license header on every new Java file; Spotless enforces it.
- **New dependencies go into the inline version catalog** in `settings.gradle.kts`, with a comment stating what the version was checked against.
- **Credentials and tokens never appear in CR status, events, logs or metrics** (design spec §12).
- **The comparison in `FabricPushTokenRepository` stays constant-time and exhaustive.** Today it compares against *every* candidate via `MessageDigest.isEqual` without returning early — both are deliberate (a timing leak via the token prefix, or via the number of existing secrets) and must not be lost through Task 2.
- **The tasks are independent** and can be worked in any order or in parallel. The one exception: Task 2 should be finished before rolling the Phase 8 manifests out to production, because their API `ClusterRole` currently locks in the broad access.

---

### Task 1: Reliable quota signal from the runner

**Open point §15.7.** Today, `BlueMapRenderReconciler` detects an exhausted storage quota by checking whether the pod's termination message contains certain strings (`UNAMBIGUOUS_QUOTA_TOKENS`, plus "quota" together with `bucket`/`rgw`/`ceph`). Kubelet's own vocabulary never contains "quota", and the message is a log excerpt with no contract behind it.

**Files:**

- Modify: `runner/entrypoint.sh`
- Modify: `runner/README.md` (exit-code table)
- Modify: `operator/src/main/java/net/onelitefeather/apus/operator/render/BlueMapRenderReconciler.java`
- Modify: `operator/src/test/java/net/onelitefeather/apus/operator/render/BlueMapRenderReconcilerTest.java`

**Interfaces:**

- Produces: exit code `6` from the runner container, as the contract "storage quota exhausted". The existing `quotaExceededMessage(Pod)` heuristic stays in place as a fallback, but becomes secondary.

- [ ] **Step 1: Find out where the quota error actually occurs**

Run: `grep -n 'exit\|bluemap' runner/entrypoint.sh`
Expected: the place where the BlueMap CLI call ends and its exit code gets evaluated.

Run: `grep -rn 'QuotaExceeded\|quota' runner/bin/*.sh runner/README.md`
Expected: nothing today. This confirms that the runner currently produces this signal nowhere — exactly the gap from §15.7.

The error occurs while writing to the map bucket, i.e. inside BlueMap via `BlueMapS3Storage`, not in `bundle-sync` (which reads). It therefore shows up in BlueMap's output, not as a separate process exit.

- [ ] **Step 2: Write a failing test on the reconciler side**

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

Add `podTerminatedWith(int exitCode, String message)` as a helper method that builds a `Pod` with `status.containerStatuses[0].state.terminated.exitCode` and `.message` — following the pattern of the class's existing Pod fixtures.

- [ ] **Step 3: Run the test and confirm the failure**

Run: `./gradlew :operator:test --tests '*BlueMapRenderReconcilerTest*'`
Expected: FAIL — the first test, because exit code 6 currently means nothing.

- [ ] **Step 4: Adjust the reconciler**

`quotaExceededMessage(Pod)` first checks the exit code:

```java
/** Exit code the runner image uses for "the tenant's storage quota is exhausted". */
public static final int RUNNER_EXIT_QUOTA_EXCEEDED = 6;
```

and, when `exitCode == 6`, returns a message immediately, without text analysis. Only after that does the existing pattern check kick in. Update the class Javadoc (lines 82–89) to match: the approach is now contract-based with the heuristic as a fallback, not the other way around.

- [ ] **Step 5: Tests pass**

Run: `./gradlew :operator:test --tests '*BlueMapRenderReconcilerTest*'`
Expected: PASS

- [ ] **Step 6: Have the runner actually set the exit code**

In `runner/entrypoint.sh`, capture BlueMap's output and evaluate it after the run:

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

Exactly where this goes depends on the location found in Step 1; the condition "only if BlueMap failed anyway" is essential — otherwise a render that merely logged the word in passing gets tipped over too.

- [ ] **Step 7: Check the script's exit-code behavior**

```bash
docker build -f runner/Dockerfile -t apus-runner:quota-test .
docker run --rm --entrypoint bash apus-runner:quota-test -c '
  echo "software.amazon.awssdk: QuotaExceeded" > /tmp/bluemap.log
  if grep -qiE "quotaexceeded|exceededquota|quota.*(bucket|rgw|ceph)" /tmp/bluemap.log; then exit 6; fi
  exit 0'
echo "exit=$?"
```

Expected: `exit=6`

- [ ] **Step 8: Add the exit-code table to `runner/README.md`**

| Code | Meaning | Retryable |
| --- | --- | --- |
| 0 | Render succeeded | — |
| 1 | Generic failure | yes |
| 3 | Bundle sync failed | yes |
| 4 | Bundle or manifest not found | no |
| 5 | Invalid configuration | no |
| 6 | Storage quota exhausted | **no** |

- [ ] **Step 9: Commit**

```bash
git add runner/entrypoint.sh runner/README.md operator/src/main/java/net/onelitefeather/apus/operator/render/BlueMapRenderReconciler.java operator/src/test/java/net/onelitefeather/apus/operator/render/BlueMapRenderReconcilerTest.java
git commit -m "feat: give the runner a dedicated exit code for exhausted storage quota"
```

---

### Task 2: Push-token lookup without cluster-wide secret read access

**Open point §15.9.** `FabricPushTokenRepository#resolveNamespace` searches by label across all namespaces. RBAC cannot restrict a label filter, so the API currently needs `get`/`list` on **all** secrets in the cluster. The class's Javadoc already sketches the narrower approach — it just was never implemented.

**Files:**

- Modify: `api/src/main/java/net/onelitefeather/apus/api/rest/push/FabricPushTokenRepository.java`
- Modify: `api/src/test/java/net/onelitefeather/apus/api/rest/push/FabricPushTokenRepositoryTest.java`
- Modify: `deploy/charts/apus-platform/templates/api-rbac.yaml` — the Kustomize overlay
  `deploy/base/api-rbac.yaml` from the original phase 8 plan is no longer created; since the
  Helm charts, the API's RBAC lives only in this template, whose comment points explicitly
  at "phase 9 task 2".

**Interfaces:**

- Consumes: `PushTokenSecrets.SECRET_NAME` (fixed name), `TenantRepository` (lists the cluster-scoped `Tenant` resources), `TenantReconciler.namespaceFor(...)` (namespace convention).
- Produces: `Optional<String> resolveNamespace(String rawToken)`, unchanged — the signature stays, only the path behind it changes.

- [ ] **Step 1: Record the class's existing guarantees before changing anything**

Run: `sed -n '60,140p' api/src/main/java/net/onelitefeather/apus/api/rest/push/FabricPushTokenRepository.java`
Expected: the Javadoc describes three properties that must be preserved: constant-time comparison via `MessageDigest.isEqual`, an exhaustive check with no early exit, and that a failure gives no indication of which tenants exist.

Run: `grep -c '@Test' api/src/test/java/net/onelitefeather/apus/api/rest/push/FabricPushTokenRepositoryTest.java`
Expected: a number > 0. These tests are the safety net for the rework — they must still pass unchanged afterwards.

- [ ] **Step 2: Write a failing test for the new access path**

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

- [ ] **Step 3: Run the test and confirm the failure**

Run: `./gradlew :api:test --tests '*FabricPushTokenRepositoryTest*'`
Expected: FAIL — the new tests, because the implementation still does a label-based, cluster-wide search.

- [ ] **Step 4: Rework `resolveNamespace`**

New flow: list the cluster-scoped `Tenant` resources, derive each one's namespace via the existing convention, and issue a `get` there for the secret with the fixed name. Compare across all results exhaustively and in constant time, as before. A `404` per namespace is a normal case.

Replace the Javadoc section that describes the broad access as a deliberate trade-off with a description of the now-implemented approach — including the RBAC rule it enables.

- [ ] **Step 5: All of the class's tests pass, including the old ones**

Run: `./gradlew :api:test --tests '*FabricPushTokenRepositoryTest*' --tests '*PushControllerTest*'`
Expected: PASS, without any pre-existing test needing adjustment. If an adjustment was necessary, that is a signal that observable behavior changed — check whether that was intended.

- [ ] **Step 6: Narrow the RBAC**

In `deploy/charts/apus-platform/templates/api-rbac.yaml`, replace the broad secrets rule:

```yaml
  # Service-token lookup, narrowed in phase 9: the API only ever reads the one Secret
  # literally named apus-push-token, in tenant namespaces it discovers through the
  # cluster-scoped Tenant resources. It can no longer read any other secret anywhere.
  - apiGroups: [""]
    resources: ["secrets"]
    resourceNames: ["apus-push-token"]
    verbs: ["get"]
```

Remove the warning comment that pointed to §15.9.

- [ ] **Step 7: Check that `resourceNames` matches the actual secret name**

Run: `grep -n 'SECRET_NAME' api/src/main/java/net/onelitefeather/apus/api/rest/push/PushTokenSecrets.java`
Expected: the value matches `resourceNames` exactly. If it differs, the API can no longer read anything in the cluster and every push fails with 403.

- [ ] **Step 8: Mark design spec §15, point 9, as resolved**

```markdown
9. ~~**RBAC for the API's push-token lookup is broader than ideal.**~~ **Resolved (Phase 9).**
   `FabricPushTokenRepository#resolveNamespace` enumerates the cluster-scoped
   `Tenant` resources and, per tenant namespace, reads specifically the secret with the
   fixed name `apus-push-token` — never `list` across all secrets again. The API's
   permission is narrowed accordingly to `resourceNames: ["apus-push-token"]`,
   `verbs: ["get"]`. The constant-time, exhaustive comparison remains unchanged.
```

- [ ] **Step 9: Commit**

```bash
git add api/ deploy/charts/apus-platform/templates/api-rbac.yaml docs/superpowers/specs/2026-08-08-apus-design.md
git commit -m "fix: read only the fixed-name push token secret instead of listing all secrets"
```

---

### Task 3: Select an identity broker and verify login against a real broker

**Open points §0 and §15.3.** The API validates JWTs against a configurable issuer, but which product sits in front of it has not been decided, and a run against a real broker has never happened — the auth tests work with self-issued test JWTs.

**Files:**

- Create: `docs/superpowers/specs/2026-08-12-identity-broker-entscheidung.md`
- Modify: `settings.gradle.kts` (Keycloak Testcontainer)
- Modify: `api/build.gradle.kts`
- Create: `api/src/test/java/net/onelitefeather/apus/api/security/RealBrokerAuthIntegrationTest.java`
- Create: `api/src/test/resources/keycloak/apus-realm.json`
- Create: `api/src/test/resources/keycloak/upstream-realm.json` (plays the role of a tenant's own IdP)
- Modify: `docs/superpowers/specs/2026-08-08-apus-design.md`

- [ ] **Step 1: Write the decision document**

`docs/superpowers/specs/2026-08-12-identity-broker-entscheidung.md`, structured as:

- **Requirement**, from §10.3: organizations with their own identity provider per organization, invitation flows, a single issuer for Apus, an organization claim that determines the tenant, roles `platform-admin`/`tenant-owner`/`tenant-operator`/`tenant-viewer`, tenant-bound service tokens with scope `world:push`.

- **Knockout criterion: role assignment per tenant, the same way across both login paths.** A tenant that federates its own IdP and a tenant with local accounts in the broker must get **the same** role structure — the same four roles, assigned per tenant, in the same place and shape in the token. Only then can the API get by with a single evaluation instead of having to distinguish two token formats. This is the requirement the product choice hinges on, and it is not automatic:
  - A broker whose roles are defined realm-wide or tenant-wide rather than per organization forces a workaround via groups or attributes. That is doable, but it then has to look identical for the federated and the local path — otherwise a federated user carries their role in a different claim than a local one.
  - With federation, the mapping from the foreign IdP is an additional decision point: roles must **not** be taken over from the federated token, or a tenant could determine for itself who is `tenant-owner` on its own side — and nothing would stop a foreign IdP from claiming `platform-admin`. The role has to be assigned in the Apus broker and written into the token it issues.
- **Candidates:** Keycloak 26+ and Zitadel — both already named in §10.3. Both bring an organization concept; they differ in how tightly roles can be bound to an organization. This exact difference has to be measured against the knockout criterion, not copied from product documentation — Step 3 provides a test that answers the question in practice.
- **Evaluation criteria**, to be demonstrated per candidate rather than asserted: role assignment per organization in *both* the federated *and* the local path (knockout, see above); the organization model and how it maps onto a token claim; federation per organization; invitation flow; service accounts with a narrow scope; operational cost within the existing cluster (which, per §2, already runs an OIDC provider for Outline, Grafana and Dependency-Track — which one needs to be determined, and it weighs heavily); upgrade path.
- **Decision**, with reasoning.
- **Consequence:** the concrete claim name the tenant gets derived from, and how roles appear in the token.

Run: `gh api repos/OneLiteFeatherNET/Kubernetes-FLUX/contents --jq '.[].name' 2>/dev/null | head -30`
Expected: a directory listing of the cluster repository. Search it for the OIDC provider currently in operation — the decision should weight it heavily, because a second broker in the same cluster is ongoing operational overhead.

- [ ] **Step 2: Add the Testcontainer to the catalog**

```kotlin
// Keycloak Testcontainer: proves the auth path against a real broker rather than
// self-issued test JWTs (design spec §15, point 3). Version verified against Maven
// Central on 2026-08-12.
version("keycloak-testcontainer", "3.7.0")
library("testcontainers.keycloak", "com.github.dasniko", "testcontainers-keycloak")
    .versionRef("keycloak-testcontainer")
```

In `api/build.gradle.kts`: `testImplementation(libs.testcontainers.keycloak)`.

If the decision in Step 1 falls on Zitadel, its container image goes here instead, via `GenericContainer`; the rest of the task stays unchanged, because both speak OIDC.

- [ ] **Step 3: Write a failing test**

```java
package net.onelitefeather.apus.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dasniko.testcontainers.keycloak.KeycloakContainer;
// ... additional imports

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

Alongside this, two realm definitions under `api/src/test/resources/keycloak/`:

- `apus-realm.json` — the Apus realm: two organizations (`friends-server`, `other-server`), the four roles, and the local test users `platform-admin-user`, `friends-owner`, `friends-operator`, `friends-viewer`. `other-server` gets **no** local users; its members come from federation.
- `upstream-realm.json` — plays the role of tenant `other-server`'s own IdP. It contains `other-operator` and is registered in the Apus realm as the identity provider for organization `other-server`. This exact federation path is the second login path the knockout criterion requires.

Both realms run in the same Keycloak container — a second container would be more realistic, but costs runtime without answering the question any better: for Apus, the upstream is just an OIDC endpoint either way.

`rolesOf(String token)` decodes the token and returns the Apus roles as `Set<String>` — at the point where the production code reads them, so the test does not bring its own evaluation logic and end up testing past the code.

- [ ] **Step 4: Run the test and confirm the failure**

Run: `./gradlew :api:integrationTest --tests '*RealBrokerAuthIntegrationTest*'`
Expected: FAIL. If the `api` module does not yet have an `integrationTest` task, create it following the same pattern as in `operator/build.gradle.kts` (excluding `**/*IntegrationTest.class` from `test`, a separate task with the same source sets).

- [ ] **Step 5: Adjust the auth configuration until the test passes**

Findings to expect — each one is a real finding that the test JWTs have concealed until now: the claim name for the organization differs from what was assumed; the roles are nested in `realm_access.roles` instead of flat in `roles`; the audience check is too lax or too strict; the JWKS URL is only ever fetched once. Every one of these gets fixed in `api/src/main/java/net/onelitefeather/apus/api/security/`, not mapped away in the test.

**The most likely hard finding concerns `grantsTheSameRoleStructureToLocalAndFederatedUsers`.** If the chosen broker's roles are defined realm-wide rather than per organization, "`tenant-operator` at `friends-server`, but nowhere else" cannot be expressed directly. Two ways out, to be checked in this order:

1. **The broker can do it natively** — roles or grants per organization. Then there is nothing to do except set them up that way.
2. **The broker cannot do it** — then model roles as groups of the form `<tenant>:<role>` and write them into a flat claim via a protocol mapper. The mapper has to be **the same** one for local and federated users; only that way does the token stay the same shape across both paths. This workaround then belongs explicitly in the decision document from Step 1 and in §10.3 of the spec — it is operational knowledge that would otherwise only live in the realm export.

If the broker falls into case 2, that is a strong argument for the other candidate. The test is where this gets decided, before operational cost is incurred.

- [ ] **Step 6: Tests pass**

Run: `./gradlew :api:test :api:integrationTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Update the spec**

§0 and §15, point 3: record the product choice, remove the reference to "never tested against a real broker" and point to the test instead.

Add three pieces of information to §10.3 that are currently missing there, and without which nobody can set up a second tenant:

1. The concrete claim name the tenant gets derived from, and the shape the roles take in the token.
2. That the role structure is identical across both login paths — a tenant's federated IdP and local accounts in the broker — together with the modeling that achieves it (natively organization-bound roles, or the group-based workaround from Step 5).
3. That roles are **never** taken over from a federated token, but assigned exclusively in the Apus broker. This is not a nuance — it is the line beyond which a tenant could otherwise declare itself `platform-admin`.

- [ ] **Step 8: Commit**

```bash
git add docs/superpowers/specs/ settings.gradle.kts api/
git commit -m "feat: verify the auth path against a real identity broker"
```

---

### Task 4: Check the save window of `paper-worldpush`

**Open point §15.8.** `BukkitSaveCoordinator` pauses autosave and forces a save before copying. Whether that short window delivers a consistent snapshot on a running server has never been checked — only unit coverage exists for the copy logic, configuration and the HTTP report path.

**Files:**

- Modify: `settings.gradle.kts` (MockBukkit)
- Modify: `paper-worldpush/build.gradle.kts`
- Create: `paper-worldpush/src/test/java/net/onelitefeather/apus/paper/BukkitSaveCoordinatorTest.java`
- Create: `paper-worldpush/src/test/java/net/onelitefeather/apus/paper/PushCycleConsistencyTest.java`
- Modify: `docs/superpowers/specs/2026-08-08-apus-design.md`

- [ ] **Step 1: Determine the actual sequence in the code**

Run: `cat paper-worldpush/src/main/java/net/onelitefeather/apus/paper/BukkitSaveCoordinator.java`
Expected: the exact sequence of `disableAutoSave()`, `save()`/`forceSave()` and re-enabling, plus which thread it runs on. The test has to check exactly this sequence, not an assumed one.

Run: `grep -n 'SaveCoordinator\|copier' paper-worldpush/src/main/java/net/onelitefeather/apus/paper/PushCycleRunner.java`
Expected: where the coordinator is called within the cycle — that is the seam the consistency question hangs on.

- [ ] **Step 2: Add MockBukkit**

```kotlin
// MockBukkit: the design spec (§13.2) called for it from the start; without it
// BukkitSaveCoordinator -- the one class that talks to the running server -- has no test
// at all. Version must match the Paper API generation this module builds against.
version("mockbukkit", "4.62.2")
library("mockbukkit", "org.mockbukkit.mockbukkit", "mockbukkit-v1.21").versionRef("mockbukkit")
```

Check the matching artifact and version combination against the Paper API version pinned in the catalog:

Run: `grep -n 'paper' settings.gradle.kts | head`
Expected: the Paper API version. MockBukkit's artifact name carries the Minecraft generation in its name; it has to match, or the mock server starts up with a registry error.

- [ ] **Step 3: Write a failing test for the coordinator**

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

- [ ] **Step 4: Confirm the failure**

Run: `./gradlew :paper-worldpush:test --tests '*BukkitSaveCoordinatorTest*'`
Expected: FAIL

- [ ] **Step 5: Consistency test across the whole cycle**

`PushCycleConsistencyTest`: a MockBukkit server with a world, where region files keep changing while the copy runs. It checks that the copied result matches the state at the moment of the forced save and contains no half-written region.

This test can produce real findings. If it finds inconsistencies, that is **the expected outcome of this task**, not its failure: §15.8 asks exactly this question. The finding then belongs in the spec as its own section, and the fix in a follow-up task — not made to disappear by weakening the assertion.

- [ ] **Step 6: Tests pass, findings documented**

Run: `./gradlew :paper-worldpush:test`
Expected: BUILD SUCCESSFUL — or a documented finding recorded in the spec.

- [ ] **Step 7: Update the spec**

§13.2 (the `paper-worldpush` line), §15 point 8, and §0: replace the "open" note with the test result. If a run against a real Paper server under load is still missing (MockBukkit does not fully replace it), that has to remain stated explicitly — along with what MockBukkit covers and what it does not.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts paper-worldpush/ docs/superpowers/specs/2026-08-08-apus-design.md
git commit -m "test: cover the paper-worldpush save window with MockBukkit"
```

---

### Task 5: Measure the `emptyDir` limit

**Open point §15.6.** "`emptyDir` is sufficient up to a size that depends on node capacity; above that a PVC is needed." The limit was never measured, even though the spec called for catching up on it before Phase 2 — and yet the operator sets a default today regardless.

**Files:**

- Create: `docs/superpowers/spikes/2026-08-12-emptydir-grenze.md`
- Create: `docs/superpowers/spikes/2026-08-12-emptydir-grenze/run-spike.sh`
- Modify: `operator/src/main/java/net/onelitefeather/apus/operator/render/RenderJobBuilder.java`
- Modify: `operator/src/test/java/net/onelitefeather/apus/operator/render/RenderJobBuilderTest.java`
- Modify: `docs/superpowers/specs/2026-08-08-apus-design.md`

- [ ] **Step 1: Determine today's default**

Run: `grep -n -B3 -A10 'emptyDir\|EmptyDir' operator/src/main/java/net/onelitefeather/apus/operator/render/RenderJobBuilder.java`
Expected: the volume type currently produced and, if present, a `sizeLimit`. That is the value the measurement is meant to confirm or disprove.

- [ ] **Step 2: Write the measurement script**

`run-spike.sh`, in the style of the existing sharding spike (read `docs/superpowers/spikes/2026-08-09-lowres-sharding-spike/run-spike.sh` as a template). It runs against a cluster:

1. Collect node capacity: `kubectl get nodes -o json | jq '.items[].status.allocatable["ephemeral-storage"]'`.
2. Start render jobs with `emptyDir` and growing world sizes (1, 5, 10, 20, 40 GiB bundle).
3. Record per run: success/failure, the reason on failure (`Evicted` referencing `ephemeral-storage` is the case being looked for), peak usage via `kubectl top pod`.
4. Determine the smallest size at which a run fails through eviction.

- [ ] **Step 3: Run the measurement and write the report**

`2026-08-12-emptydir-grenze.md`, following the pattern of the sharding-spike report: setup, measurements as a table, raw data under `evidence/`, analysis, decision.

The analysis has to answer: above which bundle size does `emptyDir` stop being sufficient on the node capacity actually available? What default follows from that? Above which size should the operator switch to a PVC automatically — or should it never do so automatically and instead require a field in the CR?

- [ ] **Step 4: Write a failing test for the result**

Once the decision is made, in `RenderJobBuilderTest`:

`MEASURED_LIMIT_GIB` is the value measured in Step 3; it is kept as a named constant in `RenderJobBuilder`, so the test and the production code share the same value.

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

`renderWithBundleSize(long)` builds a `BlueMapRender` whose referenced bundle manifest carries the given `sizeBytes` (a field from design spec §5); `workVolumeOf(Job)` reads out the volume the `bluemap` container mounts. Add both as helper methods on the test class, following the style of the fixtures already there.

- [ ] **Step 5: Adjust `RenderJobBuilder` and get the tests passing**

Run: `./gradlew :operator:test --tests '*RenderJobBuilderTest*'`
Expected: PASS

- [ ] **Step 6: Update the spec**

Replace §15 point 6 with the measurement result, pointing to the spike report — in the same style §14 Phase 4 points to the sharding spike. Add the concrete limit to §7.1 ("`emptyDir` (or PVC for large worlds)").

- [ ] **Step 7: Commit**

```bash
git add docs/superpowers/spikes/2026-08-12-emptydir-grenze* operator/ docs/superpowers/specs/2026-08-08-apus-design.md
git commit -m "feat: pick the render volume type from a measured limit instead of a guess"
```

---

## Order and dependencies

| Task | Blocked by | Can run in parallel with |
| --- | --- | --- |
| 1 — Quota exit code | — | 2, 3, 4, 5 |
| 2 — Push-token RBAC | Phase 8 Task 3 (the file being narrowed) | 1, 3, 4, 5 |
| 3 — Identity broker | — (the product decision in Step 1 is the only blocker) | 1, 2, 4, 5 |
| 4 — Save window | — | 1, 2, 3, 5 |
| 5 — `emptyDir` limit | Access to a cluster with realistic node capacity | 1, 2, 3, 4 |

Task 3 and Task 5 carry genuine uncertainty: both can produce a result that triggers follow-up work — a claim format that changes the role mapping, or a limit that requires a PVC path in the operator that does not exist today. That is not a planning mistake, but the reason these points are open in the first place.

## What this plan deliberately does not cover

- **§15 point 5 (`render-mask` and edges).** It is explicitly only relevant if Phase 4 had chosen the mask-based approach. After sharding was called off (§14, Phase 4) it has no remaining subject and should be marked moot in the spec rather than worked through.
- **§15 point 2 (bucket notifications as a second detection path).** The spec itself no longer lists it as open, but as a possible later hardening for the case where a writer loses its completion callback. That is a feature of its own, not a hardening of what already exists.
- **A CI matrix across multiple BlueMap versions.** Needs a parameterizable contract test first; see the closing section of the Phase 7 plan.
