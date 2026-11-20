#!/bin/sh
# =====================================================================================================================
# trivy-gate.sh - the Trivy gates of the grocery pipelines (contract Phase 14 §4), run inside aquasec/trivy.
#   trivy-gate.sh sources <label> <dependency-target> [<config-target>...]
#       dependencies (Maven pom.xml resolved from the local repository / npm package-lock.json) + secrets of
#       <dependency-target>, IaC misconfigurations (Dockerfiles ...) of every <config-target>
#   trivy-gate.sh image <label> <image-ref> [<sbom-file>]
#       OS packages and application dependencies of a built image (+ CycloneDX SBOM)
#   trivy-gate.sh rootfs <label> <dir> [<sbom-file>]
#       jars of an unpacked artifact (the Debezium plugin)
# Gate: exit 1 when a HIGH or CRITICAL vulnerability WITH A FIX, a HIGH/CRITICAL secret or a HIGH/CRITICAL
# misconfiguration remains after .ci/trivyignore (accepted findings with a reason and an expiry date).
# Reports in .ci/reports: trivy-<kind>-<label>.json (full) / .txt (table), *-gate.txt (what failed),
# trivy-summary.txt (counts per severity, appended). Exit 2 = the scan itself failed.
# =====================================================================================================================
set -u
R=.ci/reports
IGN=.ci/trivyignore
mkdir -p "$R"
[ -f "$IGN" ] || : > "$IGN"
mode="$1"; label="$2"; shift 2
status=0

counts() { # summary of one JSON report: vuln:<SEV>:<fixable|unfixed>=n secret:<SEV>=n config:<SEV>=n
  trivy convert --format template --template \
    '{{range .}}{{range .Vulnerabilities}}vuln:{{.Severity}}:{{if .FixedVersion}}fixable{{else}}unfixed{{end}}{{"\n"}}{{end}}{{range .Secrets}}secret:{{.Severity}}{{"\n"}}{{end}}{{range .Misconfigurations}}{{if eq .Status "FAIL"}}config:{{.Severity}}{{"\n"}}{{end}}{{end}}{{end}}' \
    "$1" 2>/dev/null | grep . | sort | uniq -c | awk '{printf "%s=%s ", $2, $1}'
}

# What fails the gate: vulnerabilities HIGH/CRITICAL WITH a fixed version, secrets HIGH/CRITICAL, misconfigurations
# HIGH/CRITICAL (FAIL). Accepted findings are already filtered out by --ignorefile at scan time. (trivy convert has
# no --ignore-unfixed, so the selection is a template over the JSON report.)
GATE_TEMPLATE='{{range .}}{{$t := .Target}}{{range .Vulnerabilities}}{{if and .FixedVersion (or (eq .Severity "HIGH") (eq .Severity "CRITICAL"))}}{{.Severity}} {{.VulnerabilityID}} {{.PkgName}} {{.InstalledVersion}} -> {{.FixedVersion}} ({{$t}}){{"\n"}}{{end}}{{end}}{{range .Secrets}}{{if or (eq .Severity "HIGH") (eq .Severity "CRITICAL")}}{{.Severity}} secret {{.RuleID}} ({{$t}}:{{.StartLine}}){{"\n"}}{{end}}{{end}}{{range .Misconfigurations}}{{if and (eq .Status "FAIL") (or (eq .Severity "HIGH") (eq .Severity "CRITICAL"))}}{{.Severity}} config {{.ID}} {{.Title}} ({{$t}}){{"\n"}}{{end}}{{end}}{{end}}'

gate() { # json name [display]
  trivy convert --format template --template "$GATE_TEMPLATE" "$1" 2>/dev/null | grep . | sort -u > "$R/$2-gate.txt"
  if [ -s "$R/$2-gate.txt" ]; then
    result=FAIL; status=1
    echo "---- ${3:-$2}: unaccepted HIGH/CRITICAL findings (fixable vulnerabilities, secrets, misconfigurations)"
    sed 's/^/  /' "$R/$2-gate.txt"
  else
    result=PASS
  fi
  line="${3:-$2}: $result  $(counts "$1")"
  echo "$line" | tee -a "$R/trivy-summary.txt"
}

table() { trivy convert --format table --output "${1%.json}.txt" "$1"; }

case "$mode" in
  sources)
    target="$1"; shift
    out="$R/trivy-fs-$label.json"
    # --offline-scan: no Maven Central lookups (it answers bursts with 429); poms resolve from the local repository
    trivy fs --quiet --offline-scan --scanners vuln,secret --skip-dirs '**/node_modules' --skip-dirs '**/target' \
      --skip-dirs '**/dist' --skip-dirs '.ci' --ignorefile "$IGN" --format json --output "$out" "$target" || exit 2
    table "$out"; gate "$out" "trivy-fs-$label"
    n=0
    for c in "$@"; do
      n=$((n + 1)); out="$R/trivy-config-$label-$n.json"
      trivy config --quiet --ignorefile "$IGN" --format json --output "$out" "$c" || exit 2
      table "$out"; gate "$out" "trivy-config-$label-$n" "trivy-config-$label-$n ($c)"
    done
    ;;
  image|rootfs)
    target="$1"; sbom="${2:-}"
    out="$R/trivy-$mode-$label.json"
    trivy "$mode" --quiet --offline-scan --scanners vuln,secret --ignorefile "$IGN" --format json --output "$out" "$target" || exit 2
    table "$out"; gate "$out" "trivy-$mode-$label"
    if [ -n "$sbom" ]; then
      trivy "$mode" --quiet --offline-scan --format cyclonedx --output "$sbom" "$target" || exit 2
      echo "SBOM (CycloneDX): $sbom ($(grep -o '"bom-ref"' "$sbom" | wc -l) components)"
    fi
    ;;
  *) echo "usage: trivy-gate.sh sources|image|rootfs <label> <target> ..." >&2; exit 2 ;;
esac
exit $status
