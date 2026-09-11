(ns gov-municipality.cells.final-sign-off.cell
  "LangGraph Pregel wrapper for gov-municipality final_sign_off cell.
  1:1 port of cells/final_sign_off/cell.py (ADR-2605250800).
  .solve() runs the init→validate→request→emit Pregel chain (the deterministic
  super-step sequence ported in state-machine/run-chain) and returns the merged
  state, exactly like FinalSignOffCell.solve compiling + invoking the graph."
  (:require [gov-municipality.cells.final-sign-off.state-machine :as sm]))

(defn solve
  "Run the final-sign-off Pregel graph over input-state → validate inspections →
  request authority signature → emit occupancy clearance. String keys mirror the
  Python dict."
  [input-state]
  (sm/run-chain input-state))
