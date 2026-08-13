# Apus — Helm Charts: Design

**Stand:** 2026-08-13
**Status:** Entwurf zur Freigabe

Apus wird über zwei Helm Charts ausgerollt, die im Apus-Repository leben, gemeinsam mit dem
Code versioniert und als OCI-Artefakte nach Harbor veröffentlicht werden. Sie ersetzen die
Kustomize-Basis, die der Phase-8-Plan bisher vorsah.

---

## 1. Ausgangslage

Apus hat heute **keine** Deployment-Beschreibung. Der Phase-8-Plan sieht eine Kustomize-Basis
unter `deploy/base` vor; davon ist nichts gebaut. Es gibt also nichts zu migrieren.

Im Cluster-Repository (`Kubernetes-FLUX`) existieren zwei etablierte Muster nebeneinander:

- **Eigene Charts** liegen unter `helm/<name>` (`leantime`, `micronaut`, `outline`, `shlink`)
  und werden per `HelmRelease` mit `sourceRef: GitRepository helmcharts` referenziert.
- **Fremde Charts** kommen als OCI-Artefakt über `OCIRepository`, etwa der
  kube-prometheus-stack von `ghcr.io` mit
  `layerSelector.mediaType: application/vnd.cncf.helm.chart.content.v1.tar+gzip`.

Das zweite Muster ist der Weg, den Apus geht: Apus ist aus Sicht des Cluster-Repositories
kein hauseigenes Manifest, sondern ein versioniertes Produkt mit eigenem Release-Zyklus.

Das vorhandene `helm/micronaut`-Chart (v0.5.2) enthält Deployment, Service, Ingress,
HTTPRoute, ConfigMap, Secret, ServiceAccount, RBAC, HPA, PDB und ServiceMonitor. Es dient
als **Vorlage** für Struktur, Label-Konventionen und `values.yaml`-Gliederung — als
Dependency ist es nicht nutzbar, weil es unpubliziert im Cluster-Repository liegt.

---

## 2. Entscheidungen

| Frage | Entscheidung | Begründung |
| --- | --- | --- |
| Helm oder Kustomize | **Helm ersetzt Kustomize** | Zwei parallele Deployment-Beschreibungen für dieselben Komponenten driften auseinander; das Cluster-Repository arbeitet ohnehin mit Helm |
| Schnitt | **Zwei Charts**: `apus-operator`, `apus-platform` | Die Trennlinie liegt dort, wo sie im Design ohnehin liegt: Der Operator ist der Kern und funktioniert allein (Spec §14, Phase 2 „für interne Nutzung bereits vollständig brauchbar"), API und UI sind die Oberfläche darüber |
| Ort | **Apus-Repository**, `deploy/charts/`, OCI nach Harbor | Chart und Code versionieren gemeinsam; die Kombination Chart↔Image kann nicht auseinanderlaufen |
| CRDs | **Als Templates** mit `helm.sh/resource-policy: keep` | Helms `crds/`-Verzeichnis wird bei `helm upgrade` nie aktualisiert; Apus' CRDs werden generiert und ändern sich mit jeder Phase |
| Mandanten | **Nicht im Chart** | Mandanten sind Betriebsdaten, keine Installationsdaten (Spec §14). Ein `helm uninstall` dürfte sie nicht mitreißen |

---

## 3. Was die Charts ausrollen — und was nicht

Von den sechs Komponenten installiert Helm nur drei. Das ist keine Lücke, sondern folgt der
Architektur:

| Komponente | Weg in den Cluster |
| --- | --- |
| `operator` | `apus-operator` — Deployment, cluster-weite RBAC |
| die sechs CRDs | `apus-operator` — Templates mit `resource-policy: keep` |
| `api` | `apus-platform` — Deployment, Service, Ingress, ServiceMonitor |
| `ui` | `apus-platform` — Deployment, Service, Ingress |
| `runner` | **vom Operator erzeugt** aus `BlueMapRender` (Job) |
| `ingest` | **vom Operator erzeugt** aus `WorldIngest` (Job) |
| `hosting` | **vom Operator erzeugt** aus `BlueMapHosting` (Deployment + Service + Ingress) |

Für die letzten drei reicht Helm nur die Image-Referenz durch — sie erscheinen in
`apus-operator`s `values.yaml` als `images.runner`, `images.ingest`, `images.hosting` und
landen als `APUS_RUNNER_IMAGE`/`APUS_INGEST_IMAGE`/`APUS_HOSTING_IMAGE` im Operator-Deployment.
Ein eigenes Chart für `hosting` wäre fachlich falsch: Es würde einen Webserver anlegen, den
der Operator gleich noch einmal erzeugt.

---

## 4. Chart `apus-operator`

Das Minimum, mit dem Apus arbeitet. Wer ausschließlich über `kubectl` und Git fährt,
installiert nur dieses Chart.

```text
deploy/charts/apus-operator/
  Chart.yaml
  values.yaml
  values.schema.json
  .helmignore
  README.md
  templates/
    _helpers.tpl
    crds.yaml            # die sechs CRDs, resource-policy: keep
    deployment.yaml
    serviceaccount.yaml
    rbac.yaml            # ClusterRole + ClusterRoleBinding
    service.yaml         # nur der Metrics-Port
    servicemonitor.yaml  # optional, .Values.metrics.serviceMonitor.enabled
    NOTES.txt
```

`values.yaml`-Oberfläche, gegliedert nach dem, was ein Betreiber tatsächlich entscheiden muss:

```yaml
image:
  repository: harbor.onelitefeather.dev/apus/operator
  tag: ""            # leer => .Chart.AppVersion
  pullPolicy: IfNotPresent

# Die Images, die der Operator für die von ihm erzeugten Workloads einsetzt.
# Default ist jeweils dieselbe Version wie der Operator selbst.
images:
  runner:
    repository: harbor.onelitefeather.dev/apus/runner
    tag: ""
  ingest:
    repository: harbor.onelitefeather.dev/apus/ingest
    tag: ""
  hosting:
    repository: harbor.onelitefeather.dev/apus/hosting
    tag: ""

# Rook/Ceph, aus dem der Operator Buckets und Mandanten-Nutzer bezieht (Spec §9.1).
rook:
  namespace: rook-ceph
  cephObjectStore: ceph-objectstore
  bucketStorageClass: ceph-bucket

# Der plattformweite Bundle-Bucket (Spec §5) -- Installationsvoraussetzung, kein Inhalt.
bundles:
  bucket: apus-bundles
  s3Endpoint: ""
  s3Region: us-east-1
  credentialsSecret: apus-bundle-credentials

metrics:
  enabled: true
  port: 8080
  serviceMonitor:
    enabled: false

resources: {}
nodeSelector: {}
tolerations: []
affinity: []
podSecurityContext: {}
securityContext: {}
```

**CRDs.** `templates/crds.yaml` entsteht beim Chart-Bau aus `deploy/crds/` (den in Phase 8
eingecheckten Generator-Ausgaben), jede Ressource mit

```yaml
metadata:
  annotations:
    helm.sh/resource-policy: keep
```

Damit werden sie bei `helm upgrade` mit aktualisiert, bei `helm uninstall` aber behalten —
sonst würde das Deinstallieren des Charts sämtliche `Tenant`-, `BlueMapMap`- und
`BlueMapHosting`-Ressourcen im Cluster mitlöschen.

Ein Schalter `crds.install: true` erlaubt es, sie abzuschalten, wenn eine Organisation CRDs
getrennt verwaltet. Der Default ist `true`.

**RBAC.** Die ClusterRole ist die aus dem Phase-8-Plan (Task 2), unverändert in ihrem Umfang:
eigene Custom Resources samt Status und Finalizern, Namespaces/ResourceQuotas/LimitRanges und
NetworkPolicies für Mandanten, Jobs/Deployments/Services/ConfigMaps/Ingresses für die
erzeugten Workloads, `pods` und `pods/log` lesend für die Fortschrittsermittlung,
`objectbucketclaims` und `cephobjectstoreusers` für Rook, `secrets` **nur lesend**, `events`
schreibend.

---

## 5. Chart `apus-platform`

REST-API und Dashboard. Setzt ein installiertes `apus-operator` voraus — die CRDs müssen
existieren, bevor die API sie liest.

```text
deploy/charts/apus-platform/
  Chart.yaml
  values.yaml
  values.schema.json
  .helmignore
  README.md
  templates/
    _helpers.tpl
    api-deployment.yaml
    api-service.yaml
    api-rbac.yaml
    api-servicemonitor.yaml
    ui-deployment.yaml
    ui-service.yaml
    ingress.yaml
    NOTES.txt
```

Die `api-rbac.yaml` trägt die in Phase 9 verengte Berechtigung: `secrets` nur mit
`resourceNames: ["apus-push-token"]` und `verbs: ["get"]`. Wird Phase 9 noch nicht umgesetzt
sein, wenn dieses Chart entsteht, trägt es die heutige, breitere Regel — mit einem Kommentar,
der auf §15 Punkt 9 verweist, damit die Verengung nicht vergessen wird.

`values.yaml` deckt zusätzlich zu den üblichen Bild-/Ressourcen-/Ingress-Blöcken den
Identity-Broker ab:

```yaml
auth:
  issuer: ""          # Pflichtwert, ohne den die API nicht startet
  jwksUri: ""
  audience: apus
```

`issuer` hat bewusst **keinen** Default: Ein halb konfiguriertes Deployment muss beim Start
scheitern, nicht Token ungeprüft akzeptieren. `values.schema.json` erzwingt das, sodass
`helm install` ohne Issuer mit einer verständlichen Meldung abbricht statt mit einem
CrashLoop.

---

## 6. Versionierung und Veröffentlichung

Beide Charts werden von Release Please mitversioniert, im Root-Track — dieselbe Version, in
der auch die Images entstehen. `Chart.yaml` bekommt je einen Marker:

```yaml
version: 0.2.1     # x-release-please-version
appVersion: "0.2.1" # x-release-please-version
```

und `release-please-config.json` je einen `extra-files`-Eintrag im Root-Paket. Damit gilt:
`apus-operator-0.3.0` referenziert `apus/operator:0.3.0`, weil `image.tag` leer bleibt und auf
`.Chart.AppVersion` zurückfällt. Die Kombination kann nicht auseinanderlaufen.

Veröffentlicht wird nach dem Muster der Images, im selben `release-please.yml`, gegated auf
`root-released`:

```bash
helm package deploy/charts/apus-operator
helm push apus-operator-<version>.tgz oci://<harbor>/apus/charts
```

Ein zentraler wiederverwendbarer Workflow dafür existiert im OLF-Katalog **nicht** — dort gibt
es nur `docker-publish`, `gradle-*`, `markdown-lint`, `pr-lint`, `close-invalid-prs` und
`release-please`. Apus bekommt deshalb zunächst einen repo-eigenen Job. Sobald ein zweites
OLF-Projekt Charts veröffentlicht, gehört er als `helm-publish.yml` in das
`workflows`-Repository; der repo-eigene Job wird dann dagegen ersetzt.

**Offen und vor dem ersten Chart-Push zu klären:** Der Image-Push nach Harbor scheitert
derzeit mit `empty challenge header` (Registry-Authentifizierung). Solange das ungelöst ist,
wird auch ein Chart-Push scheitern — beide gehen an dieselbe Registry.

---

## 7. Einbindung ins Cluster-Repository

Nach dem Muster, das dort für den kube-prometheus-stack bereits läuft:

```yaml
apiVersion: source.toolkit.fluxcd.io/v1
kind: OCIRepository
metadata:
  name: apus-operator
  namespace: flux-system
spec:
  interval: 5m
  layerSelector:
    mediaType: application/vnd.cncf.helm.chart.content.v1.tar+gzip
    operation: copy
  url: oci://harbor.onelitefeather.dev/apus/charts/apus-operator
  ref:
    semver: "=0.3.0"
```

plus ein `HelmRelease` je Chart unter `apps/base/apus/`. Die cluster-spezifischen Werte
(Registry-Host, Rook-Namen, Hostnamen, Issuer) stehen dort in `values:` — nicht im Chart.
Renovate hält die `semver`-Pins aktuell, wie bei den anderen OCI-Quellen.

---

## 8. Prüfung

| Ebene | Vorgehen |
| --- | --- |
| Statisch | `helm lint` und `helm template` für beide Charts im PR-Build; das gerenderte Ergebnis durch `kubectl apply --dry-run=client` |
| Schema | `helm template` ohne `auth.issuer` muss **fehlschlagen** — sonst greift `values.schema.json` nicht |
| Werte-Matrix | `helm template` mit Default-Werten, mit allen Schaltern an (`metrics.serviceMonitor`, `ingress`), und mit `crds.install: false` |
| Installation | Der k3s-Integrationstest aus Phase 8 Task 8 installiert künftig das Chart, statt Manifeste einzeln anzuwenden — damit ist der Ausrollweg selbst getestet, nicht nur sein Ergebnis |
| Upgrade | `helm upgrade` von der vorigen Chart-Version auf die aktuelle im selben k3s-Test, um zu belegen, dass die CRDs tatsächlich mit aktualisiert werden |

Der Upgrade-Test ist der wichtigste Punkt der Tabelle: Er prüft genau die Eigenschaft, wegen
der CRDs als Templates statt im `crds/`-Verzeichnis liegen.

---

## 9. Auswirkung auf den Phase-8-Plan

`docs/superpowers/plans/2026-08-12-phase-8-deployment-und-observability.md` wird angepasst:

- **Task 1 (CRDs einchecken)** bleibt unverändert — die Charts konsumieren `deploy/crds/`.
- **Task 2 und 3** (Kustomize-Basis für Operator, API und UI) werden durch die beiden Charts
  ersetzt.
- **Task 6 (Scrape-Konfiguration)** verschiebt sich teilweise in die Charts: `ServiceMonitor`
  für Operator und API werden Templates. Der `PodMonitor` für die vom Operator erzeugten
  Render-Pods bleibt eigenständig, weil er Pods in Mandanten-Namespaces selektiert, die kein
  Chart kennt.
- **Task 7 (Dashboards)** bleibt, wandert aber als optionale ConfigMap ins
  `apus-platform`-Chart (`dashboards.enabled`).
- **Task 8 (k3s-E2E)** installiert künftig das Chart.
- Die Tasks 4 und 5 (Metriken in Operator und API) sind unberührt.

---

## 10. Nicht-Ziele

- **Kein Chart für `runner`, `ingest` oder `hosting`.** Sie werden vom Operator erzeugt.
- **Keine Mandanten, Quellen oder Karten im Chart.** Betriebsdaten, nicht Installationsdaten.
- **Kein Umbrella-Chart** über beide. Wer beides will, installiert zwei Releases; ein Umbrella
  brächte eine dritte Version, die mit den anderen beiden synchron gehalten werden müsste.
- **Keine Migration.** Es gibt keine bestehende Kustomize-Installation.

---

## 11. Offene Punkte

1. **Harbor-Authentifizierung.** Der Image-Push scheiterte mit `empty challenge header`, bis
   `docker-publish.yml` auf `regctl registry login --skip-check` umgestellt hat — der
   anonyme Connectivity-Ping vor dem eigentlichen Push ist die Ursache. Der Chart-Push geht an
   dieselbe Registry und vermeidet den Ping jetzt auf demselben Weg: kein
   `helm registry login`, stattdessen wird die Credential-Datei direkt geschrieben und per
   `--registry-config` an `helm push` übergeben (`release-please.yml`). **Nicht gegen die
   echte Registry getestet** — lokal ist nur nachgewiesen, dass `helm push` mit einer
   handgeschriebenen Credential-Datei und ohne vorherigen Login gegen eine Registry mit
   Basic-Auth durchläuft. Ob Harbor sich beim Push selbst zufriedengibt, zeigt erst der erste
   Release-Lauf.
2. **Harbor-Projekt für Charts.** Ob `apus/charts` als Repository-Pfad im bestehenden
   Projekt `apus` liegt oder ein eigenes Harbor-Projekt bekommt, ist eine Betriebsentscheidung.
3. **Chart-Publishing im zentralen Katalog.** Zunächst repo-eigener Job; die Aufnahme in
   `OneLiteFeatherNET/workflows` steht an, sobald ein zweites Projekt Charts veröffentlicht.
4. **`values.schema.json`-Umfang.** Issuer und JWKS-URI sind als Pflichtfelder gesetzt — beide
   kommen in `application.yml` ohne Default aus der Umgebung, ein leerer Wert lässt die API
   also entweder ungeprüfte Token akzeptieren oder mangels Signaturschlüsseln jedes Token
   ablehnen. Ob weitere Werte (Rook-Namen, Bundle-Bucket) ebenfalls erzwungen werden sollen,
   entscheidet sich an der Frage, ob ein sinnvoller Default existiert.
5. **Das Dashboard ist gar nicht konfigurierbar.** `ui/nuxt.config.ts` setzt `oidcIssuer` und
   `oidcClientId` auf `''`, `ui/Dockerfile` ruft `pnpm generate` ohne Build-Argumente auf, und
   ins nginx-Image wandert nur `.output/public`. Damit sind die leeren OIDC-Werte im
   veröffentlichten Image eingefroren: `NUXT_PUBLIC_*` wirkt zur Laufzeit nur mit einem
   Nitro-Server, den dieses Image nicht enthält. Konsequenz: **keine Installation kann sich
   anmelden**, unabhängig davon, was im Chart steht — `apus-platform` reicht die Werte heute
   bewusst nur an die API weiter, das UI-Deployment bekommt sie nicht, weil es sie nicht lesen
   könnte. Das ist kein Chart-, sondern ein UI-/Build-Problem: die Reparatur ändert, wie das UI
   gebaut wird (Build-Args plus `pnpm generate` je Installation, oder ein zur Laufzeit
   geladenes `config.json` neben `index.html`, oder doch ein Nitro-Server im Image). Erst
   danach ist im Chart überhaupt etwas zu verdrahten. Blockiert damit jede echte
   Inbetriebnahme des Dashboards.
6. **Kein Image-Pull-Secret für die vom Operator erzeugten Workloads.** Die Render- und
   Ingest-Jobs sowie die Hosting-Deployments, die der Operator baut, tragen weder ein
   `imagePullSecrets` noch einen ServiceAccount — die Charts setzen die zugehörigen Images
   aber per Default auf ein privates Harbor-Projekt. Auf einem Cluster ohne node-weite
   Registry-Credentials bleibt damit jeder Render-Job in `ImagePullBackOff` hängen, während
   Operator und API selbst laufen (deren Pull-Secret setzt das Chart). Der Fix gehört in den
   Operator-Code (die Ressourcen-Builder in `render`, `ingest`, `hosting`), nicht in die
   Charts; die Charts können ihn nur begleiten, indem sie den Namen des Secrets bzw. des
   ServiceAccounts als Wert an die Operator-Konfiguration durchreichen. `imagePullSecrets` in
   `values.yaml` deckt heute ausschließlich die Pods, die die Charts selbst erzeugen.
