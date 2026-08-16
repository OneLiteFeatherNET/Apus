/**
 * Wire types for the `api` module's REST/SSE surface (design spec §11.1). Each type here is a
 * direct field-for-field mirror of the Java response/request record it names in its comment --
 * read those files, do not guess at shape. Micronaut Serde serialises record components under
 * their declared name with no naming strategy configured, so JSON keys equal the Java field
 * names verbatim (camelCase both sides).
 *
 * These are *response* shapes as the API actually returns them -- several deliberately omit
 * fields a naive mirror of the underlying custom resource would include (Secret names, job
 * names, CR bookkeeping). See each Java file's own Javadoc for why; do not "complete" these
 * types with fields the API does not send.
 */

/** api/src/main/java/net/onelitefeather/apus/api/rest/support/ConditionResponse.java */
export interface ConditionResponse {
  type: string
  status: string
  reason: string
  message: string
}

// ---------------------------------------------------------------------------------------------
// Tenants -- GET/POST /api/tenants, platform-admin only (design spec §10.3, §11.1)
// ---------------------------------------------------------------------------------------------

/**
 * api/src/main/java/net/onelitefeather/apus/api/rest/tenant/PolicyEntryResponse.java
 *
 * `enforced` is computed by the API from its own registry on every read, not stored: whether a
 * key bites is a property of the api module's code. An entry with `enforced: false` is recorded
 * and displayed and changes nothing, and the console has to say so -- see PolicyEditor.vue.
 */
export interface PolicyEntryResponse {
  key: string
  type: PolicyValueType
  value: string
  locked: boolean
  enforced: boolean
}

/** The five types a policy value may declare -- api/.../policy/PolicyType.java. */
export type PolicyValueType = 'string' | 'integer' | 'boolean' | 'duration' | 'stringList'

/** api/src/main/java/net/onelitefeather/apus/api/rest/tenant/PolicyEntryRequest.java */
export interface PolicyEntryRequest {
  key: string
  type: PolicyValueType
  value: string
  locked: boolean
}

/** api/src/main/java/net/onelitefeather/apus/api/rest/tenant/PolicyKeyResponse.java */
export interface PolicyKeyResponse {
  key: string
  type: PolicyValueType
  description: string
}

/** api/src/main/java/net/onelitefeather/apus/api/rest/tenant/TenantResponse.java */
export interface TenantResponse {
  name: string
  displayName: string
  storage: TenantStorageResponse
  allowedHostingDomains: string[]
  policy: PolicyEntryResponse[]
  namespace: string
  objectStoreUser: string
  storageUsedBytes: number | null
  conditions: ConditionResponse[]
}

/** `TenantResponse.StorageResponse` -- never carries Ceph credentials, quota only. */
export interface TenantStorageResponse {
  quota: string | null
  maxObjects: number | null
}

/** api/src/main/java/net/onelitefeather/apus/api/rest/tenant/CreateTenantRequest.java */
export interface CreateTenantRequest {
  name: string
  displayName?: string | null
  storageQuota?: string | null
  maxObjects?: number | null
  allowedHostingDomains?: string[] | null
}

/**
 * api/src/main/java/net/onelitefeather/apus/api/rest/tenant/UpdateTenantRequest.java --
 * `PATCH /api/tenants/{name}`. Partial-update semantics: an omitted/`null` field leaves the
 * current value untouched. Unlike `CreateTenantRequest`, there is no `displayName` here -- the
 * endpoint only ever changes quota/domains (see that record's own Javadoc).
 */
export interface UpdateTenantRequest {
  storageQuota?: string | null
  maxObjects?: number | null
  allowedHostingDomains?: string[] | null
  /** A present list replaces every entry; omitting it leaves them untouched. */
  policy?: PolicyEntryRequest[] | null
}

// ---------------------------------------------------------------------------------------------
// World sources -- GET/POST /api/sources, caller's own tenant (design spec §10.3, §11.1)
// ---------------------------------------------------------------------------------------------

/** api/src/main/java/net/onelitefeather/apus/api/rest/worldsource/WorldSourceResponse.java */
export interface WorldSourceResponse {
  name: string
  type: string
  poll: string | null
  worlds: WorldSelectorResponse[]
  keepVersions: number
  lastSeenVersion: string | null
  latestBundle: WorldSourceBundleResponse | null
  lastPollTime: string | null
  conditions: ConditionResponse[]
}

export interface WorldSelectorResponse {
  name: string
  layout: string | null
  minecraftVersion: string | null
}

/** Which bundle version this source last produced -- path and version only. */
export interface WorldSourceBundleResponse {
  path: string
  version: string
}

/** api/src/main/java/net/onelitefeather/apus/api/rest/worldsource/CreateWorldSourceRequest.java */
export interface CreateWorldSourceRequest {
  name: string
  type: 's3' | 'pterodactyl' | 'upload' | 'push'
  s3?: CreateWorldSourceS3Request | null
  pterodactyl?: CreateWorldSourcePterodactylRequest | null
  poll?: string | null
  worlds?: WorldSelectorRequest[] | null
  keepVersions?: number | null
}

export interface CreateWorldSourceS3Request {
  endpoint: string
  bucket: string
  prefix?: string | null
  credentialsSecretName?: string | null
}

export interface CreateWorldSourcePterodactylRequest {
  panelUrl: string
  serverId: string
  credentialsSecretName?: string | null
  select?: string | null
}

export interface WorldSelectorRequest {
  name: string
  layout?: string | null
  minecraftVersion?: string | null
}

// ---------------------------------------------------------------------------------------------
// Maps -- GET /api/maps, GET /api/maps/{id}, POST /api/maps/{id}/render (design spec §10.3, §11.1)
// ---------------------------------------------------------------------------------------------

/** api/src/main/java/net/onelitefeather/apus/api/rest/map/BlueMapMapResponse.java */
export interface BlueMapMapResponse {
  name: string
  source: BlueMapMapSourceResponse
  trigger: BlueMapMapTriggerResponse
  bluemap: BlueMapMapSettingsResponse
  shards: number
  historyLimit: number
  purgeOnDelete: boolean
  bucket: BlueMapMapBucketResponse
  latestRender: BlueMapMapLatestRenderResponse
  conditions: ConditionResponse[]
}

export interface BlueMapMapSourceResponse {
  sourceRef: string | null
  world: string | null
  dimension: string | null
}

export interface BlueMapMapTriggerResponse {
  onNewBundle: boolean
  schedule: string | null
  concurrencyPolicy: string | null
}

export interface BlueMapMapSettingsResponse {
  version: string | null
  minecraftVersion: string | null
}

/** Bucket name and endpoint only -- never the Secret name holding its credentials. */
export interface BlueMapMapBucketResponse {
  name: string | null
  endpoint: string | null
}

export interface BlueMapMapLatestRenderResponse {
  name: string | null
  phase: string | null
}

/** api/src/main/java/net/onelitefeather/apus/api/rest/map/TriggerRenderRequest.java */
export interface TriggerRenderRequest {
  force: boolean
}

// ---------------------------------------------------------------------------------------------
// Renders -- GET /api/renders, GET /api/renders/{id}, plus SSE /events and /logs
// ---------------------------------------------------------------------------------------------

/** api/src/main/java/net/onelitefeather/apus/api/rest/render/BlueMapRenderResponse.java */
export interface BlueMapRenderResponse {
  name: string
  mapRef: string | null
  force: boolean
  phase: string | null
  progress: BlueMapRenderProgressResponse
  startTime: string | null
  completionTime: string | null
  conditions: ConditionResponse[]
}

export interface BlueMapRenderProgressResponse {
  percent: number
  currentMap: string | null
  etaSeconds: number
  degraded: boolean
}

/**
 * SSE payload for `GET /api/renders/{id}/events` --
 * api/src/main/java/net/onelitefeather/apus/api/events/RenderProgress.java. A distinct, smaller
 * type from {@link BlueMapRenderResponse}: the live stream is phase + progress only, not the
 * full render resource.
 */
export interface RenderProgressEvent {
  phase: string | null
  percent: number
  currentMap: string | null
  etaSeconds: number
  degraded: boolean
}

/**
 * api/src/main/java/net/onelitefeather/apus/api/rest/render/ClusterRenderResponse.java --
 * `GET /api/renders/cluster`, `platform-admin` only. Wraps the ordinary render response with
 * which tenant it belongs to, since the platform dashboard's cluster-wide view has no tenant of
 * its own to scope by.
 */
export interface ClusterRenderResponse {
  tenant: string
  render: BlueMapRenderResponse
}

// ---------------------------------------------------------------------------------------------
// Hostings -- GET /api/hostings, read-only, caller's own tenant
// ---------------------------------------------------------------------------------------------

/** api/src/main/java/net/onelitefeather/apus/api/rest/hosting/BlueMapHostingResponse.java */
export interface BlueMapHostingResponse {
  name: string
  maps: (string | null)[]
  hostname: string | null
  url: string | null
  ready: boolean
  replicas: number
  conditions: ConditionResponse[]
}
