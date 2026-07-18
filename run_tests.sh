#!/usr/bin/env bash
# gov-municipality 官 — run the permitting actor test suite with one command.
# Exits non-zero on any failure (deploy-gate friendly).
# Namespace paths use Clojure's underscore mapping. Build an ephemeral classpath
# alias so this standalone repository does not depend on the retired root alias.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
rc=0
CP_ROOT="$(mktemp -d)"
mkdir -p "$CP_ROOT/gov_municipality"
for entry in cells methods kotoba; do ln -s "$ROOT/$entry" "$CP_ROOT/gov_municipality/$entry"; done
trap 'rm -rf "$CP_ROOT"' EXIT

run_cljc() {
  local ns="$1"
  echo "==> gov-municipality [cljc] $ns"
  ( cd "$ROOT" && bb --classpath "$CP_ROOT" -e \
    "(require (quote clojure.test) (quote ${ns}))(let [r (clojure.test/run-tests (quote ${ns}))](System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))" ) || rc=1
}

run_cljc "gov-municipality.methods.test-agent"
run_cljc "gov-municipality.cells.permit-submission.test-state-machine"
run_cljc "gov-municipality.cells.final-sign-off.test-state-machine"
run_cljc "gov-municipality.cells.inspection-scheduling.test-state-machine"

if [[ $rc -eq 0 ]]; then
  echo "==> gov-municipality: ALL GREEN"
else
  echo "==> gov-municipality: FAILURES (rc=$rc)" >&2
fi
exit $rc
