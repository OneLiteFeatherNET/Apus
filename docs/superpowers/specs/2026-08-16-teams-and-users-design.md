# Apus — Teams and Users through Entra: Design

**As of:** 2026-08-16
**Status:** Draft for approval

The console shows how many teams and users a tenant has, creates teams, invites users, resets
passwords, and shows assignments. This is the third of four subsystems; impersonation is the
fourth and depends on this one.

## 0. Two problems that look like one

They are not one problem, and conflating them would put a directory-wide credential in a browser.

**Reading who someone is** — which tenant does this signed-in user belong to? That is a token
question. It needs no Microsoft Graph, no secret and no new app registration.

**Changing the directory** — create a group, invite a person, reset a password. That needs
Microsoft Graph application permissions, which can only live on a *confidential* client.

The first also fixes a bug that has been open since SSO was set up.

## 1. Tenant membership, and the "No tenant to show" bug

The tenant application has shown *"No tenant (platform-level account)"* for every user since
Entra was wired up. The cause is now confirmed rather than suspected: `PrincipalResolver` reads
the claim `organization`, and on the `Apus` app registration both `groupMembershipClaims` and
`optionalClaims` are `null`. Entra was never going to emit that claim. It is not a mapping that
broke — it is a claim that was never configured to exist.

**Fix: map an Entra group to a tenant.**

```yaml
spec:
  identity:
    # The Entra group whose members belong to this tenant.
    groupId: 8f14e45f-ceea-467a-9a3f-3a1f9c0e2b77
```

`groupMembershipClaims: "SecurityGroup"` is set on the app registration so tokens carry a
`groups` claim, and `PrincipalResolver` resolves the tenant by looking each group id up against
the `Tenant` resources. A user in no mapped group has no tenant, exactly as today — the failure
mode does not change, only the success case starts working.

**The `groups` claim overflows at ~200 group memberships**, at which point Entra emits a
`_claim_names`/`_claim_sources` pointer instead of the list and the token carries no groups at
all. Handled explicitly: the resolver treats an overflowed token as "tenant unknown" and says so
in the log, rather than silently behaving like a user with no groups. Resolving an overflowed
token properly needs a Graph call, which belongs to §2 and is not required for this to work at
the sizes this platform has.

**`Tenant.spec.identity.groupId` is optional.** A platform that does not use group-based
membership keeps exactly today's behaviour.

## 2. Changing the directory: a second app registration

**The Graph permissions must not go on the `Apus` app registration**, and this is not a
preference. `Apus` is a SPA — a public client. A public client cannot hold a secret and cannot
use the client-credentials flow at all, so application permissions granted there would never be
usable. Worse, it invites the assumption that the browser holds them: anything the console can
do, the person operating the console's browser can do by hand.

So a second registration, `Apus Directory`, confidential, used only by the `api` module,
server-side. The console never speaks to Graph; it calls the Apus API, which is already
role-gated.

| Permission | For | Type |
| --- | --- | --- |
| `Group.ReadWrite.All` | create a team, list teams, read membership | Application |
| `User.Invite.All` | invite a user | Application |
| `User.ReadWrite.All` | reset a password (`passwordProfile`) | Application |
| `User.Read.All` | show users and assignments | Application |

All require admin consent. Granted deliberately, and the cost is stated in §3 rather than
buried.

**Credential: a client secret, not workload identity federation.** Federation would be the better
answer — nothing to rotate, nothing to leak — but it is unavailable here, and this was checked
rather than assumed: the cluster's OIDC issuer is `https://api.k8s.onelite.feather:6443`, an
internal name on a private address that Entra cannot reach to fetch keys. So a client secret,
held in a Kubernetes `Secret`, referenced by the `api` Deployment through `secretKeyRef` and
never inlined into a manifest, with an expiry date recorded in the runbook.

## 3. What these permissions actually allow, and what stops it

`Group.ReadWrite.All` and `User.ReadWrite.All` are directory-wide. They do not stop at Apus. With
them the API could rename any group in the OneLiteFeather tenant and reset the password of any
account in it, including accounts that have nothing to do with this platform. There is no Graph
scoping that narrows them — Entra has no "these groups only" variant of `Group.ReadWrite.All`.

The narrowing therefore has to be in Apus's own code, and being the only thing standing between
an API bug and the whole directory, it is written as a guard the operations call rather than a
check each one remembers:

- **Every group operation is refused unless the group id appears in some `Tenant`'s
  `spec.identity.groupId`.** A group nobody claims is not Apus's to touch.
- **Every user operation is refused unless that user is a member of such a group.**
- **Password reset additionally refuses any user holding a privileged directory role**
  (Global Administrator, Privileged Role Administrator, User Administrator, and the rest of the
  documented set). An Apus tenant-owner must not be able to take over a directory admin.
- **Password reset is refused on the acting user's own account**, which is what a self-service
  password change is for and is not what this permission is granted for.
- **Every mutation is audit-logged** with the acting principal, the target, and the outcome —
  before the call, so an attempt that fails is recorded too.

Each of those is a test, and the tests are written from the attacker's side: "a tenant-owner
cannot reset a Global Administrator's password" rather than "reset works".

## 4. What the console gets

| Where | What |
| --- | --- |
| Tenant list | team and user counts per tenant |
| Tenant detail | the teams in this tenant, and each team's members |
| Tenant detail | create a team, invite a user by e-mail |
| User detail | reset password, showing the temporary password once and never again |

Counts come from Graph and are cached briefly — a tenant list that makes two Graph calls per row
would be both slow and a good way to meet Graph's throttling.

## 5. Endpoints

All under the existing role model: `platform-admin` for anything cross-tenant, `tenant-owner`
for a tenant's own directory.

All under `/api/tenants/{name}/directory`, so one prefix carries the whole capability and it is
obvious from a path which requests reach the identity provider at all.

| Method | Path | Who |
| --- | --- | --- |
| `GET` | `…/directory/counts` | read: `platform-admin`, or a member of that tenant |
| `GET` | `…/directory` | read |
| `GET` | `…/directory/teams/{teamId}/members` | read — the assignment itself |
| `POST` | `…/directory/teams` | write: `platform-admin` or `tenant-owner` |
| `POST` | `…/directory/invitations` | write |
| `POST` | `…/directory/users/{userId}/password-reset` | write, plus §3's checks on the target |

**A team id in a path is checked against this tenant's teams, not trusted.** The group guard
would pass on its own — the tenant's own group is managed — while the id pointed at any group in
the directory, which would turn the members endpoint into a way to read every group in the
organisation.

A tenant-owner naming a tenant that is not theirs gets `404`, not `403` — the same rule the rest
of the API already follows, so a probe cannot map which tenants exist.

## 6. Graph is somebody else's service, and it will be down

Every Graph call is wrapped so that a failure is reported as a failure of *that panel*, not of the
tenant page. A tenant whose storage and renders are fine must not become unreadable because
Microsoft is throttling. Specifically: counts render as "unavailable" rather than zero — a zero
that means "we could not ask" is a lie an administrator would act on.

Throttling (`429`) is retried once with the `Retry-After` delay and then surfaced.

## 7. Non-goals

- **No sync of Entra groups into Apus.** Entra stays the system of record; Apus reads it.
- **No user deletion.** Inviting and resetting is what was asked for; deleting an account is a
  directory operation with no way back, and no button for it belongs in a tenant console.
- **No nested group resolution.** A group's direct members are its members. Transitive
  membership would make every count a different, slower question.
- **Impersonation is subsystem D**, and depends on this.
