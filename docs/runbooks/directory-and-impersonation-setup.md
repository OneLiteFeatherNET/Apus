# Enabling teams, users and impersonation

What has to happen in Microsoft Entra and in the cluster before the console's teams, invitations,
password resets and impersonation do anything. Everything here is a one-time setup per platform,
except step 4 which is once per tenant.

None of it can be automated from inside Apus: granting an application permission is a directory
administrator's decision, and Apus deliberately holds no permission that would let it grant
itself another.

## 0. Before you start

You need a directory administrator who can grant admin consent, and the Azure CLI signed in to
the right tenant:

```bash
az login --tenant 1a14dfb5-0eac-41bf-94cb-195c2e387520
az account show --query tenantId -o tsv
```

## 1. Emit the `groups` claim

**This step alone fixes the "No tenant to show" bug**, and it is worth doing even if you decide
against everything below.

The tenant application has shown *"No tenant (platform-level account)"* for every user since
single sign-on was set up. The cause is not a mis-mapped claim: the `Apus` app registration has
`groupMembershipClaims: null`, so the `organization` claim the API reads was never emitted at all.

```bash
APP_OBJECT_ID=$(az ad app show --id 59a9ea74-a98c-4b6b-b60b-3a309128a1cb --query id -o tsv)

az rest --method PATCH \
  --uri "https://graph.microsoft.com/v1.0/applications/$APP_OBJECT_ID" \
  --headers "Content-Type=application/json" \
  --body '{"groupMembershipClaims":"SecurityGroup"}'
```

Verify:

```bash
az ad app show --id 59a9ea74-a98c-4b6b-b60b-3a309128a1cb --query groupMembershipClaims -o tsv
# SecurityGroup
```

Existing sessions keep their old tokens. Sign out and back in to get a token carrying `groups`.

**Above roughly 200 group memberships Entra stops sending the list** and sends a `_claim_names`
pointer instead. `PrincipalResolver` treats that as "no tenant" rather than guessing — the same
outcome as today, so nothing regresses, but such a user will not resolve to a tenant.

## 2. A second app registration for directory access

**Do not add Graph permissions to the `Apus` registration.** It is a SPA — a public client. It
cannot hold a secret and cannot use the client-credentials flow at all, so application
permissions there would be unusable, and having them listed invites the belief that the browser
holds them.

```bash
az ad app create --display-name "Apus Directory" --sign-in-audience AzureADMyOrg
DIR_APP_ID=$(az ad app list --display-name "Apus Directory" --query "[0].appId" -o tsv)
az ad sp create --id "$DIR_APP_ID"
```

Add the permissions. These are **application** permissions, not delegated:

```bash
GRAPH=00000003-0000-0000-c000-000000000000

# Group.ReadWrite.All                62a82d76-70ea-41e2-9197-370581804d09  teams
# User.Invite.All                    09850681-111b-4a89-9bed-3f2cae46d706  invitations
# User.Read.All                      df021288-bdef-4463-88db-98f22de89214  people, assignments
# User-PasswordProfile.ReadWrite.All cc117bb9-00cf-4eb8-b580-ea2a878fe8f7  password reset
for ID in 62a82d76-70ea-41e2-9197-370581804d09 \
          09850681-111b-4a89-9bed-3f2cae46d706 \
          df021288-bdef-4463-88db-98f22de89214 \
          cc117bb9-00cf-4eb8-b580-ea2a878fe8f7; do
  az ad app permission add --id "$DIR_APP_ID" --api "$GRAPH" --api-permissions "$ID=Role"
done

az ad app permission admin-consent --id "$DIR_APP_ID"
```

**`User.ReadWrite.All` is deliberately not in that list, and `User-PasswordProfile.ReadWrite.All`
is.** Microsoft split password resets out of `User.ReadWrite.All` into a permission of their own,
so the broad one would not actually let Apus reset a password — and asking for it anyway would
grant the ability to rewrite every attribute of every account for nothing in return. All four ids
above were read back from the Graph service principal in this tenant rather than copied from
memory:

```bash
az ad sp show --id 00000003-0000-0000-c000-000000000000 \
  --query "appRoles[?value=='Group.ReadWrite.All' || value=='User.Invite.All' \
            || value=='User.Read.All' || value=='User-PasswordProfile.ReadWrite.All'].{v:value,id:id}" \
  -o table
```

**What you are granting, plainly.** These are directory-wide, and Entra offers no narrower
variant: the holder can rename any group in the organisation and reset the password of any
account in it, including accounts that have nothing to do with Apus.

What narrows them is Apus's own `DirectoryGuard`, and it is worth knowing exactly what it
promises, because nothing else does:

- any group no `Tenant` claims via `spec.identity.groupId` is refused
- any user who is not a member of such a group is refused
- any password reset aimed at an account holding a privileged directory role
  (Global Administrator, User Administrator, and a deliberately generous list of others) is
  refused
- resetting your own password here is refused — that belongs at the identity provider
- every mutation is logged with the acting principal, before the call, so failed attempts are
  recorded too

If that is not a trade you want to make, stop here. Step 1 stands on its own, and the console
will simply show those panels as unavailable.

## 3. The client secret

Workload identity federation would be better — nothing to rotate, nothing to leak — but it is not
available on this cluster: the API server's OIDC issuer is `https://api.k8s.onelite.feather:6443`,
an internal name on a private address that Entra cannot reach to fetch signing keys.

```bash
az ad app credential reset --id "$DIR_APP_ID" --display-name apus-directory --years 1
# note the `password` field -- it is shown once
```

Put it in the cluster, in the namespace the API runs in:

```bash
kubectl create secret generic apus-directory-credentials \
  --namespace apus-system \
  --from-literal=client-secret='<the password>'
```

Then turn the feature on in the `apus-platform` HelmRelease:

```yaml
directory:
  enabled: true
  tenantId: 1a14dfb5-0eac-41bf-94cb-195c2e387520
  clientId: <DIR_APP_ID>
  clientSecret:
    secretName: apus-directory-credentials
    key: client-secret
```

**Record the expiry.** A year from now this stops working, and the failure looks like the
directory being down rather than like a credential having lapsed.

## 4. Per tenant: point the tenant at its group

Once per tenant, and nothing works for that tenant until it is done — neither membership
resolution nor any directory operation, because a tenant with no group is refused rather than
treated as "any group".

```bash
kubectl patch tenant onelitefeather-dev --type merge \
  -p '{"spec":{"identity":{"groupId":"<the Entra group object id>"}}}'
```

Find the group id:

```bash
az ad group list --display-name "Apus Tenant onelitefeather-dev" --query "[0].id" -o tsv
```

If no such group exists yet, create one and put the tenant's people in it:

```bash
az ad group create \
  --display-name "Apus Tenant onelitefeather-dev" \
  --mail-nickname apus-onelitefeather-dev
```

## 5. Check it

```bash
# The API recognises the group
kubectl logs -n apus-system deploy/apus-platform-api | grep -i "tenant group index"

# A signed-in user now resolves to a tenant: the tenant app's /account page should show
# the tenant name instead of "No tenant (platform-level account)".
```

In the console, a tenant's page gains a "Teams and people" section. If it says the directory
could not be reached, the message names which of the three settings in step 3 is missing.

## What impersonation needs

Nothing beyond the above. It is two request headers applied by `ImpersonationFilter`, and it
grants nothing: the effective principal never holds `platform-admin` and never holds a role its
caller does not, so anyone using it can only ever do less than they could as themselves — in a
different tenant. Every impersonated request is logged under the real subject.
