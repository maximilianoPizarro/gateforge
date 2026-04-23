{{- define "gateforge.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "gateforge.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- printf "%s" $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "gateforge.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: gateforge
{{- end }}

{{- define "gateforge.datagrid" -}}
{{ include "gateforge.fullname" . }}-datagrid
{{- end }}

{{- define "gateforge.datagridHeadless" -}}
{{ include "gateforge.fullname" . }}-datagrid-headless
{{- end }}

{{- define "gateforge.datagridDnsQuery" -}}
{{ include "gateforge.datagridHeadless" . }}.{{ .Release.Namespace }}.svc.cluster.local
{{- end }}

{{- define "gateforge.datagridLabels" -}}
{{ include "gateforge.labels" . }}
app.kubernetes.io/name: {{ include "gateforge.datagrid" . }}
app.kubernetes.io/component: datagrid
{{- end }}

{{- define "gateforge.datagridSelectorLabels" -}}
app.kubernetes.io/name: {{ include "gateforge.datagrid" . }}
app.kubernetes.io/component: datagrid
{{- end }}
