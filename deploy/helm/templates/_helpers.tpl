{{/*
Chart identity. Release-scoped naming keeps multiple installs in one
namespace independent (e.g. release "test" -> service "test-order").
*/}}
{{- define "order-inventory.fullname" -}}
{{- default .Release.Name .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Jaeger all-in-one service name, e.g. "test-jaeger". */}}
{{- define "order-inventory.jaegerFullname" -}}
{{- printf "%s-jaeger" (include "order-inventory.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Per-service full name. Usage: include "order-inventory.svcFullname" (list "order" .) */}}
{{- define "order-inventory.svcFullname" -}}
{{- $svc := index . 0 -}}
{{- $ctx := index . 1 -}}
{{- printf "%s-%s" (include "order-inventory.fullname" $ctx) $svc | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
OTLP/gRPC endpoint handed to every service. global.otelEndpoint overrides;
default targets the in-chart Jaeger service on 4317.
*/}}
{{- define "order-inventory.otelEndpoint" -}}
{{- if .Values.global.otelEndpoint -}}
{{- .Values.global.otelEndpoint -}}
{{- else -}}
{{- printf "http://%s:4317" (include "order-inventory.jaegerFullname" .) -}}
{{- end -}}
{{- end -}}

{{/* Common labels. */}}
{{- define "order-inventory.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end -}}
