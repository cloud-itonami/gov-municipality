#!/usr/bin/env bash
# gov-municipality 官 — run the permitting actor test suite with one command.
# Exits non-zero on any failure (deploy-gate friendly).
# NOTE: the actor dir is gov-municipality (hyphen); bb resolves the ns
#       gov-municipality.methods.* via the 20-actors/gov_municipality symlink
#       (underscore alias created alongside this actor's cljc port).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$ROOT/../.." && pwd)"
rc=0

BB_CP="20-actors"

run_cljc() {
  local ns="$1"
  echo "==> gov-municipality [cljc] $ns"
  ( cd "$REPO_ROOT" && bb --classpath "$BB_CP" -e \
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
