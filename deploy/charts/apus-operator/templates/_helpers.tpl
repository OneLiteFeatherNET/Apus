{{- define "apus-operator.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "apus-operator.fullname" -}}
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

{{- define "apus-operator.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "apus-operator.labels" -}}
helm.sh/chart: {{ include "apus-operator.chart" . }}
{{ include "apus-operator.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: apus
{{- end }}

{{- define "apus-operator.selectorLabels" -}}
app.kubernetes.io/name: {{ include "apus-operator.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "apus-operator.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "apus-operator.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Resolves an image reference, defaulting the tag to the chart's appVersion.
Usage: {{ include "apus-operator.image" (dict "image" .Values.image "ctx" .) }}
*/}}
{{- define "apus-operator.image" -}}
{{- $tag := .image.tag | default .ctx.Chart.AppVersion -}}
{{- printf "%s:%s" .image.repository $tag -}}
{{- end }}
