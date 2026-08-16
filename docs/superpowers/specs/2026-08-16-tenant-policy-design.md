# Apus — Tenant Options: Override and Lock: Design

**As of:** 2026-08-16
**Status:** Draft for approval

A platform administrator can set options on a tenant, and lock the ones the tenant must not
deviate from. This is the first of four subsystems the larger goal decomposes into; the other
three (a per-tenant application instance, teams and users through Entra, and impersonation) each
get their own spec and are out of scope here.

## 0. Decisions taken before this document

Four, made deliberately in the brainstorming session that produced it:

1. **A generic key/value bag**, not a fixed set of typed fields. New options must be addable
   without changing the CRD schema. This was chosen over a fixed schema with the trade-off
   understood and stated below (§6).
2. **The API enforces the keys it knows about**, stores the ones it does not, and says which is
   which. A lock switch that locks nothing must admit it.
3. **Per tenant only.** No platform-wide layer to inherit from; an unset option means unregulated,
   and the system behaves exactly as it does today.
4. **Values are typed.** The one concession that keeps a generic bag from becoming a bag of
   strings: a type lets the API validate and the console render the right input.

---

## 1. Starting point

A `Tenant` today has three settings, all of them platform-owned and none of them lockable,
because a tenant cannot change them in the first place:

```java
// operator/src/main/java/net/onelitefeather/apus/operator/api/TenantSpec.java
private String displayName;
private StorageQuota storage;   // quota, maxObjects
private Hosting hosting;        // allowedDomains
```

What a tenant *can* choose is on its own resources, and nothing constrains it:

| Where | What the tenant picks today | Constrained by |
| --- | --- | --- |
| `POST /api/sources` | `type` (s3 / pterodactyl / upload / push) | a hardcoded set of four |
| `POST /api/sources` | `poll` (how often Apus checks) | nothing |
| `POST /api/sources` | `keepVersions` (snapshots retained) | nothing |
| `POST /api/maps/{id}/render` | `force` (rebuild from scratch) | nothing |

That table is the whole surface where "locked" can mean something today, and it is what the
initial key registry (§4) covers.

---

## 2. The data model

Policy lives on the `Tenant` custom resource, because that is where a tenant's settings live and
because it makes the whole thing visible to `kubectl` and reviewable in Git like every other part
of the platform.

```yaml
spec:
  displayName: ACME Community
  storage:
    quota: 50Gi
  policy:
    - key: source.types.allowed
      type: stringList
      value: "s3,push"
      locked: true
    - key: source.poll.minimum
      type: duration
      value: "5m"
      locked: false
```

One entry:

| Field | Meaning |
| --- | --- |
| `key` | Dotted identifier. Free-form; the registry (§4) gives some of them meaning |
| `type` | `string`, `integer`, `boolean`, `duration` or `stringList` |
| `value` | Always a string in the schema, parsed according to `type` |
| `locked` | Whether the API rejects a tenant write that deviates |

**Why `value` is a string.** A heterogeneously-typed field forces either
`x-kubernetes-preserve-unknown-fields` — which turns off schema validation for the whole subtree
and lets a typo through silently — or a union of typed fields, one of which is populated. A string
plus a declared type keeps the CRD schema closed and stable no matter which keys arrive later,
and puts parsing in one place that can report a useful error.

**Keys are unique per tenant.** A list rather than a map because Kubernetes list-with-merge-key
semantics are well understood by every tool in this cluster, and because the console needs a
stable order to render. Uniqueness is validated by the API on write; a duplicate key is a
`400`, not a last-one-wins.

---

## 3. What `locked` means

Exactly two behaviours, and the distinction is the whole feature:

| `locked` | The API | The interfaces |
| --- | --- | --- |
| `true` | **Rejects** a tenant's write that deviates, with `400` and a message naming the option | Hide or disable the control |
| `false` | Accepts anything | Pre-fill with the value and label it as the platform's recommendation |

An unlocked entry is therefore a default, not a constraint. That is the honest reading of "set it,
and optionally lock it": the platform expresses a value either way, and `locked` decides whether
deviating from it is refused or merely discouraged.

**A locked entry is enforced only if its key is known.** See §4 — this is the one place where the
generic model shows its seam, and the interfaces must show it rather than paper over it.

---

## 4. The registry, and what it costs

The `api` module ships a registry of keys it understands. Each entry names the type it expects,
the request field it constrains, and the check it performs.

| Key | Type | Enforced at | Rejected when locked and |
| --- | --- | --- | --- |
| `source.types.allowed` | `stringList` | `POST /api/sources` | `type` is not in the list |
| `source.poll.minimum` | `duration` | `POST /api/sources` | `poll` is shorter than the value |
| `source.keepVersions.maximum` | `integer` | `POST /api/sources` | `keepVersions` exceeds the value |
| `render.force.allowed` | `boolean` | `POST /api/maps/{id}/render` | `force` is true and the value is false |

Four keys, chosen because they are exactly the four choices a tenant can make today (§1). They
need no change to any CRD but `Tenant`, and each maps to a validation the controller already has
a place for — `WorldSourceController.create` validates `name` and `type` inline and throws
`BadRequestException`, which is where these joins.

**The cost, stated plainly.** An option outside this table is stored, returned by the API and
displayed by the console, and *does nothing*. Locking it changes nothing. The registry grows only
with code, so "add an option" is a code change no matter how generic the storage is — the
generality buys the ability to record intent ahead of enforcement, not enforcement itself.

Two consequences the design takes on deliberately:

- Every policy entry the API returns carries an `enforced` boolean, computed from the registry.
- The console renders unenforced entries with a visible marker, and refuses to imply otherwise.
  An administrator who locks `render.concurrency.maximum` must be able to see, at the moment they
  do it, that nothing will enforce it.

---

## 5. API surface

**Read, platform (`platform-admin`).** `GET /api/tenants` and its `TenantResponse` gain:

```java
record PolicyEntryResponse(String key, String type, String value, boolean locked, boolean enforced)
```

**Write, platform (`platform-admin`).** `PATCH /api/tenants/{name}` gains `policy`. Consistent
with the endpoint's existing partial-update semantics, an omitted `policy` leaves the current
entries untouched; a present `policy` **replaces the list wholesale**. Entry-level patching is
not offered: with a free-form key space, a merge would need a delete sentinel, and "send the list
you want" is both simpler to reason about and simpler to render a form for.

Validation on write, all `400`:

- unknown `type`
- `value` unparseable for its `type`
- duplicate `key`
- blank `key`

Note what is *not* rejected: an unknown key. That is the point of the generic bag.

**Read, tenant (any tenant role).** New: `GET /api/tenant/policy`, returning the caller's own
tenant's entries. Without it the tenant application cannot pre-fill or disable anything, and a
locked option would first become visible to a user as a rejection after they submitted a form —
which is the worst moment to learn about a rule. The endpoint exposes only the caller's own
tenant, resolved from the token exactly as every other tenant-scoped endpoint does.

**Read, catalogue (any authenticated caller).** New: `GET /api/policy-keys`, returning the
registry: key, type, what it constrains, in one sentence each. The console renders inputs and
validation from it, so the form a person fills in cannot drift from what the API actually
enforces. It contains no tenant data and is the same for everyone.

---

## 6. Enforcement

A single `TenantPolicy` component in the `api` module, consulted by the two controllers that
accept tenant choices. It is a pure function of (policy entries, the value being requested):

```java
Optional<String> reject(List<PolicyEntry> policy, PolicyKey key, Object requested)
```

It returns the message to put in the `400`, or empty. Pure — no Kubernetes client, no application
context — so the whole table in §4 is covered by plain unit tests without a cluster.

**The order matters.** Policy is checked *after* the existing shape validation (`name` not blank,
`type` one of four) and *before* anything is written. A request that is both malformed and
policy-violating reports the malformation, because that is the one the caller can act on without
asking an administrator.

**Enforcement is not authorization.** Policy answers "may this tenant use this value", never "may
this caller act on this tenant" — that stays with `TenantAccess` and the role checks, unchanged.
A policy entry can never widen access, only narrow choices within access the caller already has.

---

## 7. The interfaces

**Console, tenant detail page.** A new section under the existing quota and domain editors: the
entries as a dense table (key, type, value, locked, enforced), each row editable, plus an add
control that offers the registry's known keys first and a free-text key second. Removing a row
and saving removes the entry. The unenforced marker is a visible badge on the row, not a footnote.

**Tenant application.** Reads `GET /api/tenant/policy` once per session and uses it in the source
flow: a locked `source.types.allowed` removes the disallowed types from step 1 of "Connect a
source" rather than letting someone pick one and fail at step 4; a locked `source.poll.minimum`
sets the input's minimum; a locked `render.force.allowed: false` disables the force button and
says why in a sentence rather than leaving a dead control.

Unlocked entries pre-fill the same fields and are not enforced anywhere in the UI.

---

## 8. Tests

| What | How |
| --- | --- |
| Parsing and typing | Unit tests per type, including the malformed cases each `400` promises |
| `TenantPolicy.reject` | Unit tests for all four registry keys: locked-and-violating, locked-and-compliant, unlocked-and-violating (must accept), key absent (must accept), key present but unknown (must accept) |
| Write validation | Controller tests for each `400`: unknown type, unparseable value, duplicate key, blank key |
| Tenant-scoped read | A tenant sees only its own policy; another tenant's name resolves to its own, never to the other's |
| Console | Component test that an unenforced entry renders its marker — the one assertion protecting against silently implying enforcement |

---

## 9. Non-goals

- **No platform-wide defaults.** Decided in §0. The data model does not preclude adding a layer
  later; nothing here would have to be migrated.
- **No enforcement of unknown keys.** By construction.
- **No new constrainable fields.** Map and render defaults (`shards`, `historyLimit`,
  `concurrencyPolicy`, BlueMap version) are not covered: enforcing them means the operator has to
  honour policy at reconcile time, which is a larger change to the `BlueMapMap` path and belongs
  in its own spec.
- **Nothing about users, teams or impersonation.** Separate subsystems, separate specs.
