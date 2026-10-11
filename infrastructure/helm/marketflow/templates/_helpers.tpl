{{/* Common labels for every object (workloads add marketflow.selector, which carries the instance label). */}}
{{- define "marketflow.labels" -}}
app.kubernetes.io/part-of: marketflow
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{/* Selector labels for one workload; call with (dict "name" $name "root" $). */}}
{{- define "marketflow.selector" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{- define "marketflow.image" -}}
{{- $tag := .root.Values.image.tag | default .root.Chart.AppVersion -}}
{{ printf "%s/%s:%s" .root.Values.image.registry .name $tag }}
{{- end -}}

{{- define "marketflow.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{ .Values.serviceAccount.name | default "marketflow" }}
{{- else -}}
{{ .Values.serviceAccount.name | default "default" }}
{{- end -}}
{{- end -}}
