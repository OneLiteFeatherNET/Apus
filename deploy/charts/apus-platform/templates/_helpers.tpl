{{- define "apus-platform.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "apus-platform.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "apus-platform.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
This chart deploys three workloads (api, ui, console) from one release, so a plain fullname
would collide between them. Every per-component template goes through this helper instead --
usage: {{ include "apus-platform.componentFullname" (dict "ctx" . "component" "api") }}
*/}}
{{- define "apus-platform.componentFullname" -}}
{{- $ctx := .ctx -}}
{{- printf "%s-%s" (include "apus-platform.fullname" $ctx) .component | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "apus-platform.api.fullname" -}}
{{- include "apus-platform.componentFullname" (dict "ctx" . "component" "api") }}
{{- end }}

{{- define "apus-platform.ui.fullname" -}}
{{- include "apus-platform.componentFullname" (dict "ctx" . "component" "ui") }}
{{- end }}

{{- define "apus-platform.console.fullname" -}}
{{- include "apus-platform.componentFullname" (dict "ctx" . "component" "console") }}
{{- end }}

{{/*
Chart-wide labels, without a component. For resources that span both workloads (for
example a shared Ingress) rather than belonging to just the API or the UI.
*/}}
{{- define "apus-platform.labels" -}}
helm.sh/chart: {{ include "apus-platform.chart" . }}
app.kubernetes.io/name: {{ include "apus-platform.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: apus
{{- end }}

{{/*
Labels for a single component's resources. Usage:
{{ include "apus-platform.componentLabels" (dict "ctx" . "component" "api") }}
*/}}
{{- define "apus-platform.componentLabels" -}}
{{- $ctx := .ctx -}}
helm.sh/chart: {{ include "apus-platform.chart" $ctx }}
app.kubernetes.io/name: {{ include "apus-platform.name" $ctx }}
app.kubernetes.io/instance: {{ $ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
app.kubernetes.io/version: {{ $ctx.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ $ctx.Release.Service }}
app.kubernetes.io/part-of: apus
{{- end }}

{{/*
Selector labels for a single component. Deliberately narrower than componentLabels --
selectors must never change across an upgrade, so this only carries the fields a
Deployment's selector actually needs. Without app.kubernetes.io/component here, the API
and UI Deployments would have identical selectors and steal each other's pods. Usage:
{{ include "apus-platform.componentSelectorLabels" (dict "ctx" . "component" "api") }}
*/}}
{{- define "apus-platform.componentSelectorLabels" -}}
app.kubernetes.io/name: {{ include "apus-platform.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{- define "apus-platform.api.serviceAccountName" -}}
{{- if .Values.api.serviceAccount.create }}
{{- default (include "apus-platform.api.fullname" .) .Values.api.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.api.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Resolves an image reference, defaulting the tag to the chart's appVersion.
Usage: {{ include "apus-platform.image" (dict "image" .Values.api.image "ctx" .) }}
*/}}
{{- define "apus-platform.image" -}}
{{- $tag := .image.tag | default .ctx.Chart.AppVersion -}}
{{- printf "%s:%s" .image.repository $tag -}}
{{- end }}
