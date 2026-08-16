# Tenant Options: Override and Lock — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A platform administrator sets options on a tenant from the console and locks the ones the tenant must not deviate from; the API refuses a tenant write that breaks a locked option it knows how to enforce.

**Architecture:** A typed key/value list on the `Tenant` custom resource, a registry in the `api` module naming the keys it can actually enforce, one pure function deciding whether a requested value is refused, and two controllers consulting it. Every entry the API returns carries an `enforced` flag so no interface can imply a lock that does not bite.

**Tech Stack:** Java 25, Micronaut 4 + Micronaut Security 5.3, Fabric8 CRD generator, JUnit 5, Nuxt 4 / Vue 3 / Vitest.

**Spec:** `docs/superpowers/specs/2026-08-16-tenant-policy-design.md`

## Global Constraints

- **`value` is always a `String` in the CRD schema**, parsed according to the entry's `type`. Never widen it to a typed union or `x-kubernetes-preserve-unknown-fields` — the spec explains why (§2).
- **Five types, no more:** `string`, `integer`, `boolean`, `duration`, `stringList`.
- **Four enforced keys, exactly:** `source.types.allowed` (stringList), `source.poll.minimum` (duration), `source.keepVersions.maximum` (integer), `render.force.allowed` (boolean).
- **An unknown key is stored, returned and never enforced.** Storing it is not an error; a `400` for an unknown key would defeat the whole design.
- **`locked: false` enforces nothing.** It is a recommendation the interfaces pre-fill with.
- **Policy never widens access.** It narrows choices inside access the caller already has; `TenantAccess` and the role checks are untouched.
- **The backend is the enforcement point.** UI hiding is convenience, exactly as `ui/README.md`'s "Role logic" section already states for roles.
- **`pnpm` runs as `npx --yes pnpm@11.20.0 <args>` from `ui/`** — pnpm and corepack are not installed in this environment.
- **Gradle needs `JAVA_HOME=/usr/lib/jvm/java-25-openjdk`** prefixed on every invocation in this environment.
- **Commit signing:** if `git commit` fails with `No private key found`, re-run with `--no-gpg-sign` and say so in the report.

---

## File Structure

```text
operator/src/main/java/net/onelitefeather/apus/operator/api/
  TenantSpec.java                     modified: + List<PolicyEntry> policy
  PolicyEntry.java                    NEW: key, type, value, locked -- the CRD shape

deploy/crds/tenants.*.yaml            regenerated
deploy/charts/apus-operator/files/crds/  re-synced by sync-crds.sh

api/src/main/java/net/onelitefeather/apus/api/policy/
  PolicyType.java                     NEW: the five types and their parsing
  PolicyKey.java                      NEW: the registry of enforceable keys
  TenantPolicy.java                   NEW: reject(policy, key, requested) -- pure

api/src/main/java/net/onelitefeather/apus/api/rest/tenant/
  PolicyEntryResponse.java            NEW: wire shape, carries `enforced`
  TenantResponse.java                 modified: + policy
  UpdateTenantRequest.java            modified: + policy
  TenantController.java               modified: writes policy, validates it
  PolicyKeyController.java            NEW: GET /api/policy-keys
  TenantPolicyController.java         NEW: GET /api/tenant/policy (own tenant)

api/src/main/java/net/onelitefeather/apus/api/rest/worldsource/
  WorldSourceController.java          modified: three policy checks on create

api/src/main/java/net/onelitefeather/apus/api/rest/map/
  BlueMapMapController.java           modified: force-render policy check

ui/layers/core/app/utils/
  apiTypes.ts                         modified: PolicyEntryResponse, PolicyKeyResponse
  apiClient.ts                        modified: listPolicyKeys, getTenantPolicy, policy in update

ui/apps/console/app/components/platform/
  PolicyEditor.vue                    NEW: the table, add/remove, enforced badge

ui/apps/console/app/pages/tenants/[name].vue   modified: renders PolicyEditor
ui/apps/app/app/composables/useTenantPolicy.ts NEW: reads own policy
ui/apps/app/app/pages/sources/new.vue          modified: honours the policy
```

---

## Task 1: The policy list on the Tenant custom resource

**Files:**

- Create: `operator/src/main/java/net/onelitefeather/apus/operator/api/PolicyEntry.java`
- Modify: `operator/src/main/java/net/onelitefeather/apus/operator/api/TenantSpec.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/api/PolicyEntryTest.java`

**Interfaces:**

- Consumes: nothing.
- Produces: `TenantSpec.getPolicy()` / `setPolicy(List<PolicyEntry>)`, never `null` (empty list default). `PolicyEntry` with `getKey/setKey`, `getType/setType`, `getValue/setValue`, `isLocked/setLocked`.

- [ ] **Step 1: Write the failing test**

`operator/src/test/java/net/onelitefeather/apus/operator/api/PolicyEntryTest.java` — copy the AGPL header from any existing test in that module verbatim, then:

```java
package net.onelitefeather.apus.operator.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyEntryTest {

    @Test
    void aFreshSpecHasAnEmptyPolicyRatherThanNull() {
        // Every reader treats "no policy" as "unregulated"; a null here would make each of them
        // write the same null check, and one of them would eventually forget.
        assertNotNull(new TenantSpec().getPolicy());
        assertTrue(new TenantSpec().getPolicy().isEmpty());
    }

    @Test
    void anEntryCarriesKeyTypeValueAndLock() {
        PolicyEntry entry = new PolicyEntry();
        entry.setKey("source.poll.minimum");
        entry.setType("duration");
        entry.setValue("5m");
        entry.setLocked(true);

        assertEquals("source.poll.minimum", entry.getKey());
        assertEquals("duration", entry.getType());
        assertEquals("5m", entry.getValue());
        assertTrue(entry.isLocked());
    }

    @Test
    void anEntryIsUnlockedUntilSaid() {
        // The safe default: adding an option records intent without silently starting to refuse
        // a tenant's existing requests.
        assertFalse(new PolicyEntry().isLocked());
    }

    @Test
    void theSpecRoundTripsAPolicyList() {
        PolicyEntry entry = new PolicyEntry();
        entry.setKey("render.force.allowed");
        entry.setType("boolean");
        entry.setValue("false");

        TenantSpec spec = new TenantSpec();
        spec.setPolicy(List.of(entry));

        assertEquals(1, spec.getPolicy().size());
        assertEquals("render.force.allowed", spec.getPolicy().get(0).getKey());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :operator:test --tests "*PolicyEntryTest*"
```

Expected: FAIL — `PolicyEntry` does not exist.

- [ ] **Step 3: Write `PolicyEntry`**

`operator/src/main/java/net/onelitefeather/apus/operator/api/PolicyEntry.java` — AGPL header copied from `TenantSpec.java`, then:

```java
package net.onelitefeather.apus.operator.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.generator.annotation.Required;

/**
 * One platform-set option on a tenant.
 *
 * <p>Deliberately a free-form {@code key} with a declared {@code type} rather than a fixed set of
 * typed fields: options must be addable without changing this CRD's schema. {@code value} is
 * always a string and is parsed according to {@code type} by whoever reads it -- the alternative,
 * a genuinely polymorphic value, costs either schema validation for the whole subtree
 * ({@code x-kubernetes-preserve-unknown-fields}) or a union of typed fields where exactly one is
 * populated. See the design doc 2026-08-16, §2.
 *
 * <p><b>The api module enforces only the keys it has code for</b> and reports which those are.
 * An entry outside that set is stored and shown, and changes nothing. That is a property of the
 * design, not an oversight: it lets an administrator record an intended rule before the code that
 * applies it exists, as long as nobody is misled into thinking it already bites.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PolicyEntry {

    @Required
    private String key;

    @Required
    private String type;

    @Required
    private String value;

    /** Whether the api module refuses a tenant write that deviates from {@code value}. */
    private boolean locked;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }
}
```

- [ ] **Step 4: Add the list to `TenantSpec`**

In `operator/src/main/java/net/onelitefeather/apus/operator/api/TenantSpec.java`, beside the existing fields:

```java
    /**
     * Platform-set options for this tenant, and which of them the tenant may not deviate from.
     * Never {@code null}: an empty list means unregulated, and every reader is spared a null
     * check it would eventually forget.
     */
    private List<PolicyEntry> policy = new ArrayList<>();
```

and the accessors:

```java
    public List<PolicyEntry> getPolicy() {
        return policy;
    }

    public void setPolicy(List<PolicyEntry> policy) {
        this.policy = policy == null ? new ArrayList<>() : policy;
    }
```

Add `java.util.ArrayList` and `java.util.List` to the imports if they are not already there.

- [ ] **Step 5: Run the test and watch it pass**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :operator:test --tests "*PolicyEntryTest*"
```

Expected: PASS, four tests.

- [ ] **Step 6: Regenerate and sync the CRDs**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :operator:generateCrds
cp operator/build/crds/tenants.bluemap.onelitefeather.net-v1.yaml deploy/crds/
./deploy/charts/apus-operator/sync-crds.sh
grep -A 20 "policy:" deploy/crds/tenants.bluemap.onelitefeather.net-v1.yaml | head -25
```

Expected: the `policy` array appears with `key`, `type`, `value` and `locked` properties, and `key`/`type`/`value` listed as required. If `generateCrds` writes elsewhere, follow the path the task prints rather than this one.

- [ ] **Step 7: Run the operator's whole suite**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :operator:test
```

Expected: PASS. A CRD change can break the reconciler tests that build a `Tenant`; if one does, it is a real signal — read it before adjusting anything.

- [ ] **Step 8: Commit**

```bash
git add operator deploy/crds deploy/charts/apus-operator/files/crds
git commit -m "feat(operator): a policy list on the Tenant custom resource

Free-form key with a declared type and a string value, so options can be added
without changing this schema. Locked says whether the api module refuses a
tenant write that deviates; an entry defaults to unlocked, so recording an
intended rule never silently starts refusing requests."
```

---

## Task 2: Types and parsing

**Files:**

- Create: `api/src/main/java/net/onelitefeather/apus/api/policy/PolicyType.java`
- Test: `api/src/test/java/net/onelitefeather/apus/api/policy/PolicyTypeTest.java`

**Interfaces:**

- Consumes: nothing.
- Produces:

```java
public enum PolicyType {
    STRING, INTEGER, BOOLEAN, DURATION, STRING_LIST;

    /** The wire name: lowercase for the scalars, camelCase for the list. */
    public String wireName();
    /** The type named on the wire, or empty if it is not one of the five. */
    public static Optional<PolicyType> fromWireName(String name);
    /** True when `value` parses as this type. */
    public boolean accepts(String value);
    public long parseDurationSeconds(String value);   // DURATION only
    public long parseInteger(String value);           // INTEGER only
    public boolean parseBoolean(String value);        // BOOLEAN only
    public List<String> parseStringList(String value); // STRING_LIST only
}
```

- [ ] **Step 1: Write the failing test**

`api/src/test/java/net/onelitefeather/apus/api/policy/PolicyTypeTest.java` — AGPL header copied from an existing api test, then:

```java
package net.onelitefeather.apus.api.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyTypeTest {

    @Test
    void wireNamesAreStableBecauseTheyAreStoredInCustomResources() {
        // These strings live in Tenant manifests in Git. Renaming one silently reinterprets
        // every entry already written with it.
        assertEquals("string", PolicyType.STRING.wireName());
        assertEquals("integer", PolicyType.INTEGER.wireName());
        assertEquals("boolean", PolicyType.BOOLEAN.wireName());
        assertEquals("duration", PolicyType.DURATION.wireName());
        assertEquals("stringList", PolicyType.STRING_LIST.wireName());
    }

    @Test
    void anUnknownTypeNameIsEmptyRatherThanAGuess() {
        assertEquals(Optional.of(PolicyType.DURATION), PolicyType.fromWireName("duration"));
        assertEquals(Optional.empty(), PolicyType.fromWireName("timespan"));
        assertEquals(Optional.empty(), PolicyType.fromWireName(null));
        assertEquals(Optional.empty(), PolicyType.fromWireName(""));
    }

    @Test
    void integersAcceptWholeNumbersOnly() {
        assertTrue(PolicyType.INTEGER.accepts("3"));
        assertTrue(PolicyType.INTEGER.accepts("-1"));
        assertFalse(PolicyType.INTEGER.accepts("3.5"));
        assertFalse(PolicyType.INTEGER.accepts("three"));
        assertFalse(PolicyType.INTEGER.accepts(""));
        assertEquals(3L, PolicyType.INTEGER.parseInteger("3"));
    }

    @Test
    void booleansAcceptOnlyTrueAndFalse() {
        // Not Boolean.parseBoolean, which answers false for "yes", "1" and "maybe" alike -- an
        // administrator typing "yes" would silently get the opposite of what they meant.
        assertTrue(PolicyType.BOOLEAN.accepts("true"));
        assertTrue(PolicyType.BOOLEAN.accepts("false"));
        assertFalse(PolicyType.BOOLEAN.accepts("yes"));
        assertFalse(PolicyType.BOOLEAN.accepts("1"));
        assertTrue(PolicyType.BOOLEAN.parseBoolean("true"));
        assertFalse(PolicyType.BOOLEAN.parseBoolean("false"));
    }

    @Test
    void durationsUseTheSameSpellingTheRestOfApusUses() {
        // WorldSourceSpec.poll is a Go-style duration ("5m", "1h30m"), which is what a tenant
        // types into the source form -- the policy has to speak the same language or comparing
        // the two means nothing.
        assertTrue(PolicyType.DURATION.accepts("5m"));
        assertTrue(PolicyType.DURATION.accepts("1h"));
        assertTrue(PolicyType.DURATION.accepts("1h30m"));
        assertTrue(PolicyType.DURATION.accepts("45s"));
        assertFalse(PolicyType.DURATION.accepts("5"));
        assertFalse(PolicyType.DURATION.accepts("soon"));
        assertEquals(300L, PolicyType.DURATION.parseDurationSeconds("5m"));
        assertEquals(5400L, PolicyType.DURATION.parseDurationSeconds("1h30m"));
        assertEquals(45L, PolicyType.DURATION.parseDurationSeconds("45s"));
    }

    @Test
    void stringListsAreCommaSeparatedAndTrimmed() {
        assertTrue(PolicyType.STRING_LIST.accepts("s3,push"));
        assertEquals(List.of("s3", "push"), PolicyType.STRING_LIST.parseStringList("s3, push"));
        assertEquals(List.of("s3"), PolicyType.STRING_LIST.parseStringList("s3"));
        // An empty list is a meaningful policy -- "no source type is allowed" -- so it parses
        // rather than being rejected as malformed.
        assertTrue(PolicyType.STRING_LIST.accepts(""));
        assertEquals(List.of(), PolicyType.STRING_LIST.parseStringList(""));
    }

    @Test
    void everyTypeRejectsNull() {
        for (PolicyType type : PolicyType.values()) {
            assertFalse(type.accepts(null), type + " accepted null");
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :api:test --tests "*PolicyTypeTest*"
```

Expected: FAIL — `PolicyType` does not exist.

- [ ] **Step 3: Write `PolicyType`**

`api/src/main/java/net/onelitefeather/apus/api/policy/PolicyType.java` — AGPL header, then:

```java
package net.onelitefeather.apus.api.policy;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The five types a policy entry's value may declare.
 *
 * <p>The wire names are stored inside {@code Tenant} manifests in Git; renaming one silently
 * reinterprets every entry already written with it, so they are pinned by
 * {@code PolicyTypeTest}.
 *
 * <p>Durations use Go's spelling ({@code 5m}, {@code 1h30m}) rather than ISO-8601, because that
 * is what {@code WorldSourceSpec.poll} already uses and what a tenant types into the source form.
 * A policy that spoke a different dialect could not be compared with the value it governs.
 */
public enum PolicyType {
    STRING("string"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
    DURATION("duration"),
    STRING_LIST("stringList");

    private static final Pattern DURATION = Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$");

    private final String wireName;

    PolicyType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<PolicyType> fromWireName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(type -> type.wireName.equals(name)).findFirst();
    }

    public boolean accepts(String value) {
        if (value == null) {
            return false;
        }
        return switch (this) {
            case STRING, STRING_LIST -> true;
            case INTEGER -> {
                try {
                    Long.parseLong(value.trim());
                    yield true;
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            // Not Boolean.parseBoolean: it answers false for "yes" as readily as for "false",
            // so an administrator's typo would become the opposite of what they meant.
            case BOOLEAN -> "true".equals(value.trim()) || "false".equals(value.trim());
            case DURATION -> {
                String trimmed = value.trim();
                Matcher matcher = DURATION.matcher(trimmed);
                // The pattern matches the empty string too, so a bare "" or "5" must not pass.
                yield !trimmed.isEmpty() && matcher.matches() && matcher.group(0).length() > 0
                        && (matcher.group(1) != null || matcher.group(2) != null || matcher.group(3) != null);
            }
        };
    }

    public long parseInteger(String value) {
        return Long.parseLong(value.trim());
    }

    public boolean parseBoolean(String value) {
        return "true".equals(value.trim());
    }

    public long parseDurationSeconds(String value) {
        Matcher matcher = DURATION.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("not a duration: " + value);
        }
        long hours = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
        long minutes = matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2));
        long seconds = matcher.group(3) == null ? 0 : Long.parseLong(matcher.group(3));
        return hours * 3600 + minutes * 60 + seconds;
    }

    public List<String> parseStringList(String value) {
        if (value.isBlank()) {
            // Deliberately a valid policy: "no source type is allowed at all".
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isEmpty()).toList();
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :api:test --tests "*PolicyTypeTest*"
```

Expected: PASS, seven tests.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/net/onelitefeather/apus/api/policy api/src/test/java/net/onelitefeather/apus/api/policy
git commit -m "feat(api): the five policy value types and their parsing

Durations use Go's spelling because WorldSourceSpec.poll already does, and a
policy that could not be compared with the value it governs would be decoration.
Booleans accept only true and false: Boolean.parseBoolean answers false for
\"yes\" as readily as for \"false\", which would turn a typo into its opposite."
```

---

## Task 3: The registry of enforceable keys

**Files:**

- Create: `api/src/main/java/net/onelitefeather/apus/api/policy/PolicyKey.java`
- Test: `api/src/test/java/net/onelitefeather/apus/api/policy/PolicyKeyTest.java`

**Interfaces:**

- Consumes: `PolicyType` (Task 2).
- Produces:

```java
public enum PolicyKey {
    SOURCE_TYPES_ALLOWED, SOURCE_POLL_MINIMUM, SOURCE_KEEP_VERSIONS_MAXIMUM, RENDER_FORCE_ALLOWED;

    public String key();            // e.g. "source.types.allowed"
    public PolicyType type();
    public String description();    // one sentence, shown by the console
    public static Optional<PolicyKey> fromKey(String key);
    public static boolean isEnforced(String key);
}
```

- [ ] **Step 1: Write the failing test**

```java
package net.onelitefeather.apus.api.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyKeyTest {

    @Test
    void theFourEnforceableKeysAreNamedAndTypedAsTheSpecSays() {
        // These strings are stored in Tenant manifests and typed into the console. Changing one
        // silently un-enforces every entry already written with the old spelling.
        assertEquals("source.types.allowed", PolicyKey.SOURCE_TYPES_ALLOWED.key());
        assertEquals(PolicyType.STRING_LIST, PolicyKey.SOURCE_TYPES_ALLOWED.type());

        assertEquals("source.poll.minimum", PolicyKey.SOURCE_POLL_MINIMUM.key());
        assertEquals(PolicyType.DURATION, PolicyKey.SOURCE_POLL_MINIMUM.type());

        assertEquals("source.keepVersions.maximum", PolicyKey.SOURCE_KEEP_VERSIONS_MAXIMUM.key());
        assertEquals(PolicyType.INTEGER, PolicyKey.SOURCE_KEEP_VERSIONS_MAXIMUM.type());

        assertEquals("render.force.allowed", PolicyKey.RENDER_FORCE_ALLOWED.key());
        assertEquals(PolicyType.BOOLEAN, PolicyKey.RENDER_FORCE_ALLOWED.type());
    }

    @Test
    void anUnknownKeyIsNotEnforcedAndIsNotAnError() {
        // The whole point of the generic bag: recording an intended rule ahead of the code that
        // applies it must be possible, and must be visibly unenforced rather than rejected.
        assertEquals(Optional.empty(), PolicyKey.fromKey("render.concurrency.maximum"));
        assertFalse(PolicyKey.isEnforced("render.concurrency.maximum"));
        assertFalse(PolicyKey.isEnforced(null));
        assertTrue(PolicyKey.isEnforced("render.force.allowed"));
    }

    @Test
    void everyKeyExplainsItself() {
        // The console shows this next to the input. A key with no sentence is a key nobody can
        // use correctly without reading Java.
        for (PolicyKey key : PolicyKey.values()) {
            assertFalse(key.description().isBlank(), key + " has no description");
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :api:test --tests "*PolicyKeyTest*"
```

Expected: FAIL — `PolicyKey` does not exist.

- [ ] **Step 3: Write `PolicyKey`**

```java
package net.onelitefeather.apus.api.policy;

import java.util.Arrays;
import java.util.Optional;

/**
 * The policy keys this module knows how to enforce.
 *
 * <p>Four, and they are exactly the four choices a tenant can make today: which source type to
 * create, how often it is polled, how many snapshots are kept, and whether a render may be
 * forced. Every other key a tenant's policy may carry is stored, returned and displayed, and
 * changes nothing -- {@code isEnforced} is what lets the interfaces say so out loud rather than
 * implying a lock that does not bite (design doc 2026-08-16, §4).
 *
 * <p>Adding a key here is a code change by construction: enforcement lives in the controller that
 * accepts the value, and no amount of generality in the storage removes that.
 */
public enum PolicyKey {
    SOURCE_TYPES_ALLOWED(
            "source.types.allowed",
            PolicyType.STRING_LIST,
            "Which source types a tenant may create. A type outside this list is refused."),
    SOURCE_POLL_MINIMUM(
            "source.poll.minimum",
            PolicyType.DURATION,
            "The shortest polling interval a tenant may set on a source."),
    SOURCE_KEEP_VERSIONS_MAXIMUM(
            "source.keepVersions.maximum",
            PolicyType.INTEGER,
            "The most snapshots a tenant may keep per source."),
    RENDER_FORCE_ALLOWED(
            "render.force.allowed",
            PolicyType.BOOLEAN,
            "Whether a tenant may force a full re-render, discarding existing tiles.");

    private final String key;
    private final PolicyType type;
    private final String description;

    PolicyKey(String key, PolicyType type, String description) {
        this.key = key;
        this.type = type;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public PolicyType type() {
        return type;
    }

    public String description() {
        return description;
    }

    public static Optional<PolicyKey> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(candidate -> candidate.key.equals(key)).findFirst();
    }

    public static boolean isEnforced(String key) {
        return fromKey(key).isPresent();
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :api:test --tests "*PolicyKeyTest*"
```

Expected: PASS, three tests.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/net/onelitefeather/apus/api/policy/PolicyKey.java api/src/test/java/net/onelitefeather/apus/api/policy/PolicyKeyTest.java
git commit -m "feat(api): the registry of policy keys this module can enforce

Four keys, the four choices a tenant can actually make today. isEnforced is what
lets the interfaces admit that any other key is stored and shown but changes
nothing, instead of implying a lock that does not bite."
```

---

## Task 4: The decision

**Files:**

- Create: `api/src/main/java/net/onelitefeather/apus/api/policy/TenantPolicy.java`
- Test: `api/src/test/java/net/onelitefeather/apus/api/policy/TenantPolicyTest.java`

**Interfaces:**

- Consumes: `PolicyType`, `PolicyKey`.
- Produces:

```java
public record PolicyEntryView(String key, String type, String value, boolean locked) {}

@Singleton
public class TenantPolicy {
    /** The message for the 400, or empty when the request is allowed. */
    public Optional<String> rejectSourceType(List<PolicyEntryView> policy, String requestedType);
    public Optional<String> rejectPoll(List<PolicyEntryView> policy, String requestedPoll);
    public Optional<String> rejectKeepVersions(List<PolicyEntryView> policy, Integer requested);
    public Optional<String> rejectForceRender(List<PolicyEntryView> policy, boolean force);
}
```

`PolicyEntryView` is the api module's own read model, so nothing here depends on the operator's mutable `PolicyEntry`.

- [ ] **Step 1: Write the failing test**

```java
package net.onelitefeather.apus.api.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TenantPolicyTest {

    private final TenantPolicy policy = new TenantPolicy();

    private static PolicyEntryView locked(String key, String type, String value) {
        return new PolicyEntryView(key, type, value, true);
    }

    private static PolicyEntryView unlocked(String key, String type, String value) {
        return new PolicyEntryView(key, type, value, false);
    }

    @Test
    void noPolicyAllowsEverything() {
        assertTrue(policy.rejectSourceType(List.of(), "s3").isEmpty());
        assertTrue(policy.rejectPoll(List.of(), "1s").isEmpty());
        assertTrue(policy.rejectKeepVersions(List.of(), 999).isEmpty());
        assertTrue(policy.rejectForceRender(List.of(), true).isEmpty());
    }

    @Test
    void anUnlockedEntryNeverRefuses() {
        // This is the whole difference between "override" and "lock": an unlocked entry is the
        // platform's recommendation, and the interfaces pre-fill with it. Enforcing it would
        // make the lock switch meaningless.
        List<PolicyEntryView> unlockedTypes = List.of(unlocked("source.types.allowed", "stringList", "s3"));
        assertTrue(policy.rejectSourceType(unlockedTypes, "push").isEmpty());
    }

    @Test
    void aLockedSourceTypeListRefusesAnythingOutsideIt() {
        List<PolicyEntryView> only = List.of(locked("source.types.allowed", "stringList", "s3,push"));

        assertTrue(policy.rejectSourceType(only, "s3").isEmpty());
        assertTrue(policy.rejectSourceType(only, "push").isEmpty());

        Optional<String> refusal = policy.rejectSourceType(only, "pterodactyl");
        assertTrue(refusal.isPresent());
        // The message has to name the option, or an administrator cannot tell the tenant which
        // rule to ask about.
        assertTrue(refusal.get().contains("source.types.allowed"), refusal.get());
    }

    @Test
    void anEmptyLockedListRefusesEveryType() {
        List<PolicyEntryView> none = List.of(locked("source.types.allowed", "stringList", ""));

        assertTrue(policy.rejectSourceType(none, "s3").isPresent());
    }

    @Test
    void aLockedPollMinimumRefusesAnythingShorter() {
        List<PolicyEntryView> floor = List.of(locked("source.poll.minimum", "duration", "5m"));

        assertTrue(policy.rejectPoll(floor, "5m").isEmpty());
        assertTrue(policy.rejectPoll(floor, "10m").isEmpty());
        assertTrue(policy.rejectPoll(floor, "1h").isEmpty());
        assertTrue(policy.rejectPoll(floor, "30s").isPresent());
    }

    @Test
    void aSourceWithNoPollIsNotComparedAgainstAMinimum() {
        // poll is optional: a source with none is only ever ingested on request, which is
        // slower than any floor rather than faster. Refusing it would be backwards.
        List<PolicyEntryView> floor = List.of(locked("source.poll.minimum", "duration", "5m"));

        assertTrue(policy.rejectPoll(floor, null).isEmpty());
        assertTrue(policy.rejectPoll(floor, "").isEmpty());
    }

    @Test
    void aLockedKeepVersionsMaximumRefusesAnythingLarger() {
        List<PolicyEntryView> cap = List.of(locked("source.keepVersions.maximum", "integer", "3"));

        assertTrue(policy.rejectKeepVersions(cap, 3).isEmpty());
        assertTrue(policy.rejectKeepVersions(cap, 1).isEmpty());
        assertTrue(policy.rejectKeepVersions(cap, null).isEmpty());
        assertTrue(policy.rejectKeepVersions(cap, 4).isPresent());
    }

    @Test
    void aLockedForceRenderBanRefusesOnlyForcedRenders() {
        List<PolicyEntryView> banned = List.of(locked("render.force.allowed", "boolean", "false"));

        assertTrue(policy.rejectForceRender(banned, false).isEmpty());
        assertTrue(policy.rejectForceRender(banned, true).isPresent());

        List<PolicyEntryView> permitted = List.of(locked("render.force.allowed", "boolean", "true"));
        assertTrue(policy.rejectForceRender(permitted, true).isEmpty());
    }

    @Test
    void anEntryWhoseValueDoesNotMatchItsTypeIsIgnoredRatherThanCrashing() {
        // The write path validates this, but a Tenant can also be edited with kubectl. A
        // malformed entry must not take the API down or refuse every request -- it is treated
        // as absent, which is the same as unregulated.
        List<PolicyEntryView> broken = List.of(locked("source.poll.minimum", "duration", "later"));

        assertTrue(policy.rejectPoll(broken, "1s").isEmpty());
    }

    @Test
    void anEntryWithTheWrongTypeForItsKeyIsIgnored() {
        List<PolicyEntryView> mistyped = List.of(locked("source.poll.minimum", "integer", "300"));

        assertTrue(policy.rejectPoll(mistyped, "1s").isEmpty());
    }

    @Test
    void anUnknownKeyIsNeverEnforcedEvenWhenLocked() {
        List<PolicyEntryView> unknown = List.of(locked("source.poll.maximum", "duration", "5m"));

        assertTrue(policy.rejectPoll(unknown, "1h").isEmpty());
        assertFalse(PolicyKey.isEnforced("source.poll.maximum"));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :api:test --tests "*TenantPolicyTest*"
```

Expected: FAIL — `TenantPolicy` does not exist.

- [ ] **Step 3: Write `PolicyEntryView` and `TenantPolicy`**

`api/src/main/java/net/onelitefeather/apus/api/policy/PolicyEntryView.java` — AGPL header, then:

```java
package net.onelitefeather.apus.api.policy;

/**
 * One policy entry as this module reads it: immutable, and independent of the operator's mutable
 * {@code PolicyEntry} so nothing in the decision path can accidentally write back into a custom
 * resource it was only handed to inspect.
 */
public record PolicyEntryView(String key, String type, String value, boolean locked) {}
```

`api/src/main/java/net/onelitefeather/apus/api/policy/TenantPolicy.java` — AGPL header, then:

```java
package net.onelitefeather.apus.api.policy;

import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether a tenant's requested value is refused by that tenant's policy.
 *
 * <p>Pure: no Kubernetes client, no context, nothing to mock. Every branch of the registry is
 * therefore covered by plain unit tests.
 *
 * <p><b>Three ways an entry is ignored,</b> all of them deliberate and all of them tested. An
 * unlocked entry is a recommendation, not a rule. An entry whose key this module does not know
 * cannot be enforced by definition. And an entry whose value does not parse as its declared type
 * -- which the write path rejects, but {@code kubectl edit} does not -- is treated as absent
 * rather than as a reason to refuse everything: a malformed policy must not become an outage.
 *
 * <p>Policy narrows choices inside access the caller already has. It never widens access, and it
 * is never consulted to decide whether a caller may act on a tenant at all -- that stays with
 * {@code TenantAccess} and the role checks.
 */
@Singleton
public class TenantPolicy {

    public Optional<String> rejectSourceType(List<PolicyEntryView> policy, String requestedType) {
        return value(policy, PolicyKey.SOURCE_TYPES_ALLOWED).flatMap(raw -> {
            List<String> allowed = PolicyType.STRING_LIST.parseStringList(raw);
            if (allowed.contains(requestedType)) {
                return Optional.empty();
            }
            return Optional.of("source type '" + requestedType + "' is not allowed for this tenant ("
                    + PolicyKey.SOURCE_TYPES_ALLOWED.key() + " allows: "
                    + (allowed.isEmpty() ? "none" : String.join(", ", allowed)) + ")");
        });
    }

    public Optional<String> rejectPoll(List<PolicyEntryView> policy, String requestedPoll) {
        // No poll at all means "only when asked", which is slower than any floor rather than
        // faster -- comparing it would refuse the most conservative choice available.
        if (requestedPoll == null || requestedPoll.isBlank()) {
            return Optional.empty();
        }
        return value(policy, PolicyKey.SOURCE_POLL_MINIMUM).flatMap(raw -> {
            if (!PolicyType.DURATION.accepts(requestedPoll)) {
                // Shape validation belongs to the controller; the policy has nothing to say
                // about a value it cannot interpret.
                return Optional.empty();
            }
            long minimum = PolicyType.DURATION.parseDurationSeconds(raw);
            long requested = PolicyType.DURATION.parseDurationSeconds(requestedPoll);
            if (requested >= minimum) {
                return Optional.empty();
            }
            return Optional.of("poll interval '" + requestedPoll + "' is shorter than this tenant's minimum of "
                    + raw + " (" + PolicyKey.SOURCE_POLL_MINIMUM.key() + ")");
        });
    }

    public Optional<String> rejectKeepVersions(List<PolicyEntryView> policy, Integer requested) {
        if (requested == null) {
            return Optional.empty();
        }
        return value(policy, PolicyKey.SOURCE_KEEP_VERSIONS_MAXIMUM).flatMap(raw -> {
            long maximum = PolicyType.INTEGER.parseInteger(raw);
            if (requested <= maximum) {
                return Optional.empty();
            }
            return Optional.of("keepVersions " + requested + " exceeds this tenant's maximum of " + maximum
                    + " (" + PolicyKey.SOURCE_KEEP_VERSIONS_MAXIMUM.key() + ")");
        });
    }

    public Optional<String> rejectForceRender(List<PolicyEntryView> policy, boolean force) {
        if (!force) {
            return Optional.empty();
        }
        return value(policy, PolicyKey.RENDER_FORCE_ALLOWED).flatMap(raw -> {
            if (PolicyType.BOOLEAN.parseBoolean(raw)) {
                return Optional.empty();
            }
            return Optional.of("forced renders are not allowed for this tenant ("
                    + PolicyKey.RENDER_FORCE_ALLOWED.key() + ")");
        });
    }

    /**
     * The value of a locked entry for {@code key}, if there is one, it declares the type the key
     * expects, and that value parses. Anything else is treated as absent -- see the class Javadoc.
     */
    private Optional<String> value(List<PolicyEntryView> policy, PolicyKey key) {
        if (policy == null) {
            return Optional.empty();
        }
        return policy.stream()
                .filter(entry -> key.key().equals(entry.key()))
                .filter(PolicyEntryView::locked)
                .filter(entry -> key.type().wireName().equals(entry.type()))
                .filter(entry -> key.type().accepts(entry.value()))
                .map(PolicyEntryView::value)
                .findFirst();
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :api:test --tests "*TenantPolicyTest*"
```

Expected: PASS, eleven tests.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/net/onelitefeather/apus/api/policy api/src/test/java/net/onelitefeather/apus/api/policy
git commit -m "feat(api): decide whether a tenant's requested value breaks its policy

Pure, so all eleven cases are plain unit tests. Three ways an entry is ignored,
each deliberate: unlocked is a recommendation, an unknown key cannot be enforced
by definition, and a value that does not parse as its declared type is treated as
absent -- kubectl edit can write one, and a malformed policy must not become an
outage that refuses every request."
```

---

## Task 5: Reading and writing policy over the API

**Files:**

- Create: `api/src/main/java/net/onelitefeather/apus/api/rest/tenant/PolicyEntryResponse.java`
- Create: `api/src/main/java/net/onelitefeather/apus/api/rest/tenant/PolicyKeyController.java`
- Create: `api/src/main/java/net/onelitefeather/apus/api/rest/tenant/TenantPolicyController.java`
- Modify: `api/src/main/java/net/onelitefeather/apus/api/rest/tenant/TenantResponse.java`
- Modify: `api/src/main/java/net/onelitefeather/apus/api/rest/tenant/UpdateTenantRequest.java`
- Modify: `api/src/main/java/net/onelitefeather/apus/api/rest/tenant/TenantController.java`
- Test: `api/src/test/java/net/onelitefeather/apus/api/rest/tenant/PolicyWriteValidationTest.java`

**Interfaces:**

- Consumes: `PolicyKey`, `PolicyType`, `PolicyEntryView`, the operator's `PolicyEntry`.
- Produces:
  - `PolicyEntryResponse(String key, String type, String value, boolean locked, boolean enforced)`
  - `UpdateTenantRequest(String storageQuota, Long maxObjects, List<String> allowedHostingDomains, List<PolicyEntryRequest> policy)`
  - `PolicyEntryRequest(String key, String type, String value, Boolean locked)`
  - `GET /api/policy-keys` → `List<PolicyKeyResponse(String key, String type, String description)>`
  - `GET /api/tenant/policy` → `List<PolicyEntryResponse>` for the caller's own tenant
  - `@Singleton TenantPolicyReader` with `List<PolicyEntryView> forPrincipal(ApusPrincipal principal)` — returns an empty list for a principal with no tenant. This is the exact signature Task 6 calls; do not rename it.

- [ ] **Step 1: Write the wire types**

`PolicyEntryResponse.java` — AGPL header, then:

```java
package net.onelitefeather.apus.api.rest.tenant;

import io.micronaut.serde.annotation.Serdeable;
import net.onelitefeather.apus.api.policy.PolicyKey;
import net.onelitefeather.apus.operator.api.PolicyEntry;

/**
 * One policy entry as the API returns it.
 *
 * <p>{@code enforced} is not stored anywhere -- it is computed from {@link PolicyKey}'s registry
 * on every read, because whether a key bites is a property of this module's code and changes
 * when the code does. Returning it is what lets the console mark an entry that will do nothing,
 * instead of drawing a lock switch that quietly locks nothing.
 */
@Serdeable
public record PolicyEntryResponse(String key, String type, String value, boolean locked, boolean enforced) {

    public static PolicyEntryResponse from(PolicyEntry entry) {
        return new PolicyEntryResponse(
                entry.getKey(),
                entry.getType(),
                entry.getValue(),
                entry.isLocked(),
                PolicyKey.isEnforced(entry.getKey()));
    }
}
```

`PolicyEntryRequest.java` in the same package:

```java
package net.onelitefeather.apus.api.rest.tenant;

import io.micronaut.serde.annotation.Serdeable;

/**
 * One policy entry as a caller sends it. {@code locked} is boxed so an omitted value can default
 * to {@code false} rather than being indistinguishable from an explicit "not locked" -- adding an
 * option must never start refusing a tenant's requests as a side effect.
 */
@Serdeable
public record PolicyEntryRequest(String key, String type, String value, Boolean locked) {}
```

`PolicyKeyResponse.java` in the same package:

```java
package net.onelitefeather.apus.api.rest.tenant;

import io.micronaut.serde.annotation.Serdeable;
import net.onelitefeather.apus.api.policy.PolicyKey;

/** One entry of the enforceable-key catalogue, so a form cannot drift from what actually bites. */
@Serdeable
public record PolicyKeyResponse(String key, String type, String description) {

    public static PolicyKeyResponse from(PolicyKey key) {
        return new PolicyKeyResponse(key.key(), key.type().wireName(), key.description());
    }
}
```

- [ ] **Step 2: Add `policy` to `TenantResponse`**

Add the component `List<PolicyEntryResponse> policy` to the record — after `allowedHostingDomains`, before `namespace` — and in `from`:

```java
                spec.getPolicy().stream().map(PolicyEntryResponse::from).toList(),
```

Update the record's Javadoc to mention that `policy` carries `enforced` per entry.

- [ ] **Step 3: Add `policy` to `UpdateTenantRequest`**

```java
public record UpdateTenantRequest(
        String storageQuota,
        Long maxObjects,
        List<String> allowedHostingDomains,
        List<PolicyEntryRequest> policy) {}
```

Extend the record's Javadoc:

```java
 * <p>{@code policy} follows the same partial-update rule at the field level -- omitted leaves the
 * current entries untouched -- but a present list <b>replaces all of them</b>. Entry-level
 * patching is deliberately not offered: with a free-form key space a merge would need a delete
 * sentinel, and "send the list you want to hold" is both easier to reason about and easier to
 * render a form for.
```

- [ ] **Step 4: Write the failing validation test**

`api/src/test/java/net/onelitefeather/apus/api/rest/tenant/PolicyWriteValidationTest.java`:

```java
package net.onelitefeather.apus.api.rest.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import net.onelitefeather.apus.operator.api.PolicyEntry;
import org.junit.jupiter.api.Test;

/**
 * The four ways a policy write is refused. An unknown *key* is deliberately not among them --
 * storing one is the point of the design.
 */
class PolicyWriteValidationTest {

    private static PolicyEntryRequest entry(String key, String type, String value) {
        return new PolicyEntryRequest(key, type, value, true);
    }

    @Test
    void aBlankKeyIsRefused() {
        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> PolicyWrite.toEntries(List.of(entry("", "string", "x"))));
        assertTrue(thrown.getMessage().contains("key"), thrown.getMessage());
    }

    @Test
    void anUnknownTypeIsRefused() {
        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> PolicyWrite.toEntries(List.of(entry("a.b", "timespan", "5m"))));
        assertTrue(thrown.getMessage().contains("timespan"), thrown.getMessage());
    }

    @Test
    void aValueThatDoesNotParseAsItsTypeIsRefused() {
        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> PolicyWrite.toEntries(List.of(entry("a.b", "duration", "soon"))));
        assertTrue(thrown.getMessage().contains("soon"), thrown.getMessage());
    }

    @Test
    void aDuplicateKeyIsRefusedRatherThanLastOneWinning() {
        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> PolicyWrite.toEntries(List.of(entry("a.b", "string", "1"), entry("a.b", "string", "2"))));
        assertTrue(thrown.getMessage().contains("a.b"), thrown.getMessage());
    }

    @Test
    void anUnknownKeyIsStored() {
        List<PolicyEntry> entries = PolicyWrite.toEntries(List.of(entry("render.concurrency.maximum", "integer", "2")));

        assertEquals(1, entries.size());
        assertEquals("render.concurrency.maximum", entries.get(0).getKey());
    }

    @Test
    void anOmittedLockDefaultsToUnlocked() {
        List<PolicyEntry> entries =
                PolicyWrite.toEntries(List.of(new PolicyEntryRequest("a.b", "string", "x", null)));

        assertEquals(false, entries.get(0).isLocked());
    }

    @Test
    void aKeyKnownToTheRegistryMustDeclareTheTypeTheRegistryExpects() {
        // Otherwise the entry is stored, displayed as enforced, and silently ignored by
        // TenantPolicy because the type does not match -- the worst of both worlds.
        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> PolicyWrite.toEntries(List.of(entry("source.poll.minimum", "integer", "300"))));
        assertTrue(thrown.getMessage().contains("source.poll.minimum"), thrown.getMessage());
    }
}
```

- [ ] **Step 5: Run it and watch it fail**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :api:test --tests "*PolicyWriteValidationTest*"
```

Expected: FAIL — `PolicyWrite` does not exist.

- [ ] **Step 6: Write `PolicyWrite`**

`api/src/main/java/net/onelitefeather/apus/api/rest/tenant/PolicyWrite.java` — AGPL header, then:

```java
package net.onelitefeather.apus.api.rest.tenant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.onelitefeather.apus.api.policy.PolicyKey;
import net.onelitefeather.apus.api.policy.PolicyType;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import net.onelitefeather.apus.operator.api.PolicyEntry;

/**
 * Turns a caller's policy list into custom-resource entries, refusing the four shapes that would
 * otherwise be stored as nonsense.
 *
 * <p>An unknown <i>key</i> is deliberately not one of them: recording an intended rule ahead of
 * the code that enforces it is the point of the generic design. An unknown <i>type</i> is,
 * because nothing could ever interpret it.
 *
 * <p>A known key declaring the wrong type is also refused, and that one is worth stating: such an
 * entry would be stored, reported as {@code enforced} by the registry, and then silently skipped
 * by {@code TenantPolicy} for the type mismatch -- an option that looks enforced and is not is
 * exactly what this design set out to avoid.
 */
final class PolicyWrite {

    private PolicyWrite() {}

    static List<PolicyEntry> toEntries(List<PolicyEntryRequest> requested) {
        List<PolicyEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (PolicyEntryRequest request : requested) {
            if (request.key() == null || request.key().isBlank()) {
                throw new BadRequestException("policy key must not be blank");
            }
            if (!seen.add(request.key())) {
                throw new BadRequestException("duplicate policy key '" + request.key() + "'");
            }
            PolicyType type = PolicyType.fromWireName(request.type())
                    .orElseThrow(() -> new BadRequestException("unknown policy type '" + request.type()
                            + "' for key '" + request.key() + "'"));
            if (!type.accepts(request.value())) {
                throw new BadRequestException("value '" + request.value() + "' is not a valid "
                        + type.wireName() + " for key '" + request.key() + "'");
            }
            PolicyKey.fromKey(request.key()).ifPresent(known -> {
                if (known.type() != type) {
                    throw new BadRequestException("key '" + known.key() + "' must be of type "
                            + known.type().wireName() + ", not " + type.wireName());
                }
            });

            PolicyEntry entry = new PolicyEntry();
            entry.setKey(request.key());
            entry.setType(type.wireName());
            entry.setValue(request.value());
            entry.setLocked(Boolean.TRUE.equals(request.locked()));
            entries.add(entry);
        }
        return entries;
    }
}
```

- [ ] **Step 7: Run the test and watch it pass**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :api:test --tests "*PolicyWriteValidationTest*"
```

Expected: PASS, seven tests.

- [ ] **Step 8: Wire the write into `TenantController.update`**

In the `update` method, after the existing `allowedHostingDomains` handling:

```java
        if (request.policy() != null) {
            // A present list replaces every entry -- see UpdateTenantRequest's Javadoc for why
            // entry-level patching is not offered.
            spec.setPolicy(PolicyWrite.toEntries(request.policy()));
        }
```

- [ ] **Step 9: Add the catalogue endpoint**

`PolicyKeyController.java` — AGPL header, then a controller mirroring the shape of the existing ones (`@Controller("/api/policy-keys")`, `@Secured(SecurityRule.IS_AUTHENTICATED)`), whose single `@Get` returns
`Arrays.stream(PolicyKey.values()).map(PolicyKeyResponse::from).toList()`. Javadoc: it carries no tenant data, is identical for every caller, and exists so a console form cannot drift from what the API enforces.

- [ ] **Step 10: Add the reader both the endpoint and Task 6 use**

`api/src/main/java/net/onelitefeather/apus/api/policy/TenantPolicyReader.java` — AGPL header, then:

```java
package net.onelitefeather.apus.api.policy;

import jakarta.inject.Singleton;
import java.util.List;
import net.onelitefeather.apus.api.security.ApusPrincipal;

/**
 * The one place that turns a caller into that caller's policy.
 *
 * <p>A principal with no tenant gets an empty list rather than an error: a platform admin
 * browsing the tenant application is an ordinary visitor, not a fault, and "no tenant" and "no
 * policy" mean the same thing to every reader downstream -- unregulated.
 */
@Singleton
public class TenantPolicyReader {

    private final TenantRepository repository;

    public TenantPolicyReader(TenantRepository repository) {
        this.repository = repository;
    }

    public List<PolicyEntryView> forPrincipal(ApusPrincipal principal) {
        if (principal == null || principal.tenant() == null) {
            return List.of();
        }
        return repository.findByName(principal.tenant())
                .map(tenant -> tenant.getSpec().getPolicy().stream()
                        .map(entry -> new PolicyEntryView(
                                entry.getKey(), entry.getType(), entry.getValue(), entry.isLocked()))
                        .toList())
                .orElse(List.of());
    }
}
```

Use whatever type `TenantController` injects for its repository — read that file's constructor and match it exactly rather than trusting the name `TenantRepository` here.

- [ ] **Step 11: Add the tenant-scoped read endpoint**

`TenantPolicyController.java` — AGPL header, then a controller in the shape of the module's existing ones:

```java
@Controller("/api/tenant/policy")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class TenantPolicyController {

    private final PrincipalResolver principalResolver;
    private final TenantPolicyReader reader;

    public TenantPolicyController(PrincipalResolver principalResolver, TenantPolicyReader reader) {
        this.principalResolver = principalResolver;
        this.reader = reader;
    }

    @Get
    public HttpResponse<List<PolicyEntryResponse>> policy(Authentication authentication) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        List<PolicyEntryResponse> entries = reader.forPrincipal(principal).stream()
                .map(view -> new PolicyEntryResponse(
                        view.key(), view.type(), view.value(), view.locked(),
                        PolicyKey.isEnforced(view.key())))
                .toList();
        return HttpResponse.ok(entries);
    }
}
```

Its Javadoc must say why it exists: without it a locked option first becomes visible to a user as a rejection *after* they submitted a form, which is the worst possible moment to learn a rule. Note also that it is scoped to the caller's own tenant by construction — the tenant is taken from the token and there is no path parameter to abuse.

- [ ] **Step 12: Run the api suite**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :api:test
```

Expected: PASS.

- [ ] **Step 13: Commit**

```bash
git add api/src/main/java/net/onelitefeather/apus/api/policy api/src/main/java/net/onelitefeather/apus/api/rest/tenant api/src/test/java/net/onelitefeather/apus/api/rest/tenant
git commit -m "feat(api): read and write tenant policy

Every entry the API returns carries enforced, computed from the registry on each
read, so the console can mark an option that will do nothing. A present policy
list replaces all entries: with a free-form key space a merge would need a delete
sentinel.

A known key declaring the wrong type is refused, because such an entry would be
stored, reported as enforced, and then skipped for the type mismatch -- an option
that looks enforced and is not is the failure this design exists to prevent."
```

---

## Task 6: Enforcement at the two write paths

**Files:**

- Modify: `api/src/main/java/net/onelitefeather/apus/api/rest/worldsource/WorldSourceController.java`
- Modify: `api/src/main/java/net/onelitefeather/apus/api/rest/map/BlueMapMapController.java`
- Test: `api/src/test/java/net/onelitefeather/apus/api/rest/worldsource/WorldSourcePolicyTest.java`

**Interfaces:**

- Consumes: `TenantPolicy`, `PolicyEntryView`, the tenant lookup from Task 5.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

A `@MicronautTest` in the style of the module's existing HTTP tests. **Read `BlueMapMapControllerHttpTest` first** and copy its setup verbatim — how it activates the environment, how it mints a token with the injected `TokenGenerator` and the `tenant-operator` role, how it seeds custom resources, and how it asserts on `HttpClientResponseException`. The cases below assume that harness; do not invent a second one.

```java
    /** Seeds the caller's tenant with one locked entry. Mirror the neighbouring tests' seeding. */
    private void givenPolicy(String key, String type, String value, boolean locked) {
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName(TENANT);
        PolicyEntry entry = new PolicyEntry();
        entry.setKey(key);
        entry.setType(type);
        entry.setValue(value);
        entry.setLocked(locked);
        tenant.getSpec().setPolicy(List.of(entry));
        seed(tenant);
    }

    private HttpResponse<?> postSource(String type, String poll, Integer keepVersions) {
        return client.toBlocking().exchange(HttpRequest
                .POST("/api/sources", new CreateWorldSourceRequest(
                        "world-src", type, null, null, poll, null, keepVersions))
                .bearerAuth(token()));
    }

    @Test
    void aSourceTypeOutsideALockedListIsRefusedWithFourHundred() {
        givenPolicy("source.types.allowed", "stringList", "s3", true);

        HttpClientResponseException thrown = assertThrows(HttpClientResponseException.class,
                () -> postSource("pterodactyl", null, null));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatus());
    }

    @Test
    void theRefusalNamesThePolicyKeySoTheTenantCanAskAboutIt() {
        // Without the key in the message a tenant can only report "it says no", and an
        // administrator has to guess which of their own rules did it.
        givenPolicy("source.types.allowed", "stringList", "s3", true);

        HttpClientResponseException thrown = assertThrows(HttpClientResponseException.class,
                () -> postSource("pterodactyl", null, null));

        assertTrue(thrown.getMessage().contains("source.types.allowed"), thrown.getMessage());
    }

    @Test
    void aSourceTypeInsideALockedListIsAccepted() {
        givenPolicy("source.types.allowed", "stringList", "s3,push", true);

        assertEquals(HttpStatus.CREATED, postSource("push", null, null).getStatus());
    }

    @Test
    void aPollShorterThanALockedMinimumIsRefused() {
        givenPolicy("source.poll.minimum", "duration", "5m", true);

        HttpClientResponseException thrown = assertThrows(HttpClientResponseException.class,
                () -> postSource("s3", "30s", null));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatus());
    }

    @Test
    void anUnlockedMinimumDoesNotRefuse() {
        // The difference between "override" and "lock", asserted where a user would meet it.
        givenPolicy("source.poll.minimum", "duration", "5m", false);

        assertEquals(HttpStatus.CREATED, postSource("s3", "30s", null).getStatus());
    }

    @Test
    void keepVersionsAboveALockedMaximumIsRefused() {
        givenPolicy("source.keepVersions.maximum", "integer", "3", true);

        HttpClientResponseException thrown = assertThrows(HttpClientResponseException.class,
                () -> postSource("s3", null, 4));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatus());
    }

    @Test
    void aTenantWithNoPolicyIsUnaffected() {
        assertEquals(HttpStatus.CREATED, postSource("s3", "1s", 99).getStatus());
    }

    @Test
    void policyIsCheckedAfterShapeValidation() {
        // Both wrong: an unknown type (shape) that is also outside the allowed list (policy).
        // The caller must hear about the shape, which they can fix themselves, rather than
        // about a rule they would have to ask an administrator to change.
        givenPolicy("source.types.allowed", "stringList", "s3", true);

        HttpClientResponseException thrown = assertThrows(HttpClientResponseException.class,
                () -> postSource("ftp", null, null));

        assertTrue(thrown.getMessage().contains("type must be one of"), thrown.getMessage());
    }
```

Adjust the `CreateWorldSourceRequest` constructor call to that record's actual component order — read it rather than trusting the order above.

- [ ] **Step 2: Run it and watch it fail**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :api:test --tests "*WorldSourcePolicyTest*"
```

Expected: FAIL — no policy is consulted yet, so every case is accepted.

- [ ] **Step 3: Enforce in `WorldSourceController.create`**

Inject `TenantPolicy` and the tenant lookup, then immediately after the existing `type` validation and before the `WorldSource` is built:

```java
        List<PolicyEntryView> policy = tenantPolicyReader.forPrincipal(principal);
        tenantPolicy.rejectSourceType(policy, request.type()).ifPresent(message -> {
            throw new BadRequestException(message);
        });
        tenantPolicy.rejectPoll(policy, request.poll()).ifPresent(message -> {
            throw new BadRequestException(message);
        });
        tenantPolicy.rejectKeepVersions(policy, request.keepVersions()).ifPresent(message -> {
            throw new BadRequestException(message);
        });
```

The position is deliberate and is asserted by `policyIsCheckedAfterShapeValidation`: shape first, policy second, write third.

- [ ] **Step 4: Enforce in `BlueMapMapController.triggerRender`**

Same pattern, before the render is created:

```java
        tenantPolicy.rejectForceRender(tenantPolicyReader.forPrincipal(principal), request.force())
                .ifPresent(message -> {
                    throw new BadRequestException(message);
                });
```

- [ ] **Step 5: Run the tests and watch them pass**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :api:test
```

Expected: PASS, including the existing suites — a tenant with no policy must behave exactly as before, which the untouched neighbouring tests already assert.

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java
git commit -m "feat(api): refuse tenant writes that break a locked policy

Shape validation first, policy second, write third: a request that is both
malformed and policy-violating reports the malformation, which is the one the
caller can fix without asking an administrator. A tenant with no policy behaves
exactly as before."
```

---

## Task 7: The interfaces

**Files:**

- Modify: `ui/layers/core/app/utils/apiTypes.ts`, `ui/layers/core/app/utils/apiClient.ts`
- Create: `ui/apps/console/app/components/platform/PolicyEditor.vue`
- Modify: `ui/apps/console/app/pages/tenants/[name].vue`
- Create: `ui/apps/app/app/composables/useTenantPolicy.ts`
- Modify: `ui/apps/app/app/pages/sources/new.vue`
- Test: `ui/layers/core/tests/unit/policy.spec.ts`, `ui/apps/console/tests/nuxt/policyEditor.nuxt.spec.ts`

**Interfaces:**

- Consumes: the endpoints from Task 5.
- Produces: `listPolicyKeys()`, `getTenantPolicy()` on the client; `useTenantPolicy()` returning `{ entries, isLocked(key), valueOf(key), allowedSourceTypes, minimumPoll, maximumKeepVersions, forceAllowed }`.

- [ ] **Step 1: Add the wire types**

In `apiTypes.ts`, mirroring the Java records with the file's existing one-comment-per-type convention:

```ts
/** api/src/main/java/net/onelitefeather/apus/api/rest/tenant/PolicyEntryResponse.java */
export interface PolicyEntryResponse {
  key: string
  type: 'string' | 'integer' | 'boolean' | 'duration' | 'stringList'
  value: string
  locked: boolean
  /** Computed by the API from its registry: false means this entry changes nothing. */
  enforced: boolean
}

/** api/src/main/java/net/onelitefeather/apus/api/rest/tenant/PolicyKeyResponse.java */
export interface PolicyKeyResponse {
  key: string
  type: PolicyEntryResponse['type']
  description: string
}
```

Add `policy: PolicyEntryResponse[]` to `TenantResponse` and `policy?: PolicyEntryRequest[] | null` to `UpdateTenantRequest`, with `PolicyEntryRequest` as `{ key, type, value, locked }`.

- [ ] **Step 2: Add the client methods**

```ts
    listPolicyKeys: () => request<PolicyKeyResponse[]>('/api/policy-keys'),
    getTenantPolicy: () => request<PolicyEntryResponse[]>('/api/tenant/policy'),
```

- [ ] **Step 3: Write the failing unit test for the policy readers**

`ui/layers/core/tests/unit/policy.spec.ts`, testing a small pure module `#core/utils/policy.ts` that turns entries into the four typed answers the app needs:

```ts
import { describe, expect, it } from 'vitest'
import { allowedSourceTypes, forceAllowed, maximumKeepVersions, minimumPollSeconds } from '~/utils/policy'
import type { PolicyEntryResponse } from '~/utils/apiTypes'

function locked(key: string, type: PolicyEntryResponse['type'], value: string): PolicyEntryResponse {
  return { key, type, value, locked: true, enforced: true }
}

describe('policy readers', () => {
  it('returns null when nothing is regulated, so callers can tell "no rule" from "rule of zero"', () => {
    expect(allowedSourceTypes([])).toBeNull()
    expect(minimumPollSeconds([])).toBeNull()
    expect(maximumKeepVersions([])).toBeNull()
    expect(forceAllowed([])).toBeNull()
  })

  it('reads a locked source type list', () => {
    expect(allowedSourceTypes([locked('source.types.allowed', 'stringList', 's3, push')]))
      .toEqual(['s3', 'push'])
  })

  it('ignores an unlocked entry, because the UI must not disable what the API would accept', () => {
    const advisory: PolicyEntryResponse[] = [
      { key: 'source.types.allowed', type: 'stringList', value: 's3', locked: false, enforced: true }
    ]
    expect(allowedSourceTypes(advisory)).toBeNull()
  })

  it('ignores an entry the API says it does not enforce', () => {
    const decorative: PolicyEntryResponse[] = [
      { key: 'source.types.allowed', type: 'stringList', value: 's3', locked: true, enforced: false }
    ]
    expect(allowedSourceTypes(decorative)).toBeNull()
  })

  it('parses durations the way the API does', () => {
    expect(minimumPollSeconds([locked('source.poll.minimum', 'duration', '5m')])).toBe(300)
    expect(minimumPollSeconds([locked('source.poll.minimum', 'duration', '1h30m')])).toBe(5400)
  })

  it('reads the force-render ban', () => {
    expect(forceAllowed([locked('render.force.allowed', 'boolean', 'false')])).toBe(false)
    expect(forceAllowed([locked('render.force.allowed', 'boolean', 'true')])).toBe(true)
  })
})
```

- [ ] **Step 4: Run it and watch it fail, then implement `#core/utils/policy.ts`**

```bash
cd ui && npx --yes pnpm@11.20.0 --filter @apus/ui-core test
```

Implement the four readers. Each filters on `locked && enforced` — the UI must never disable something the API would in fact accept, which is the mirror of the rule that the UI must never enable something the API refuses.

- [ ] **Step 5: Build the console editor**

`PolicyEditor.vue`: a `DataTable` of entries (key, type, value, locked, enforced), each row editable, an add control offering the catalogue's keys first and free text second, and a remove control. The enforced column renders a `StatusPill`-styled marker: entries with `enforced: false` say **"not enforced"** in words, never colour alone.

Saving emits the full list; the page sends it as `policy` on the existing `PATCH`.

- [ ] **Step 6: Write the console component test**

`policyEditor.nuxt.spec.ts` — the one assertion that protects the design's central promise:

```ts
  it('says in words when an entry will not be enforced', async () => {
    const wrapper = await mountSuspended(PolicyEditor, {
      props: {
        modelValue: [
          { key: 'render.concurrency.maximum', type: 'integer', value: '2', locked: true, enforced: false }
        ],
        knownKeys: []
      }
    })

    // A lock switch that locks nothing has to admit it, or an administrator will rely on it.
    expect(wrapper.text()).toContain('not enforced')
  })
```

- [ ] **Step 7: Honour the policy in the tenant app**

`useTenantPolicy.ts` calls `getTenantPolicy()` once and exposes the four readers. In `sources/new.vue`:

- step 1 offers only `allowedSourceTypes` when it is non-null, with one sentence saying the platform limits the choice
- the poll input's help text names the minimum, and step 3's validation refuses below it before submitting
- `maximumKeepVersions` caps the number input

In `worlds/[name].vue`, when `forceAllowed` is `false`, the force button is disabled with a sentence saying the platform does not permit it — not hidden, because a control that vanishes leaves the reader wondering whether they misremembered it.

- [ ] **Step 8: Run everything**

```bash
cd ui
npx --yes pnpm@11.20.0 lint
npx --yes pnpm@11.20.0 typecheck
npx --yes pnpm@11.20.0 test
```

- [ ] **Step 9: Commit**

```bash
git add ui
git commit -m "feat(ui): edit tenant policy in the console, honour it in the app

The console marks an entry the API does not enforce in words. The app disables
what a locked policy forbids rather than letting someone fill in a form and fail
at the end -- and disables rather than hides, so nobody wonders whether they
misremembered a control."
```

---

## Done when

- `./gradlew :api:test :operator:test` passes.
- `pnpm lint`, `pnpm typecheck`, `pnpm test` pass in `ui/`.
- The regenerated `Tenant` CRD carries `spec.policy` and the chart's copy matches `deploy/crds/`.
- A locked `source.types.allowed` refuses a disallowed type with a `400` naming the key, and the tenant app never offers that type in the first place.
- An entry whose key the registry does not know is stored, returned, shown, and marked "not enforced" in the console.
- A tenant with no policy behaves exactly as it did before this plan.
