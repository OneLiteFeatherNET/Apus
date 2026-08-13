#!/usr/bin/env bash
# Copies the generated CRDs into the chart and annotates them so that `helm uninstall`
# keeps them. Run after ./gradlew :operator:generateCrds whenever a CRD changes.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
src="$root/deploy/crds"
dst="$(dirname "$0")/files/crds"

mkdir -p "$dst"
rm -f "$dst"/*.yaml

for f in "$src"/*.yaml; do
  name="$(basename "$f")"
  # yq is not a dependency of this repo; the annotation is inserted with awk so the
  # script needs nothing beyond coreutils.
  awk '
    /^metadata:/ && !done {
      print
      print "  annotations:"
      print "    helm.sh/resource-policy: keep"
      done = 1
      next
    }
    { print }
  ' "$f" > "$dst/$name"
done

echo "copied $(ls -1 "$dst"/*.yaml | wc -l) CRDs into the chart"
