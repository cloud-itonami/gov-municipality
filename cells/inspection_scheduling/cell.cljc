(ns gov-municipality.cells.inspection-scheduling.cell
  "LangGraph Pregel wrapper for gov-municipality inspection_scheduling cell.
  1:1 port of cells/inspection_scheduling/cell.py (ADR-2605250800).
  .solve() runs the init→fetch→rules→emit Pregel chain (the deterministic
  super-step sequence ported in state-machine/run-chain) and returns the merged
  state, exactly like InspectionSchedulingCell.solve compiling + invoking the graph."
  (:require [gov-municipality.cells.inspection-scheduling.state-machine :as sm]))

(defn solve
  "Run the inspection-scheduling Pregel graph over input-state → permit verify →
  jurisdiction rules → emit inspection schedule. String keys mirror the Python dict."
  [input-state]
  (sm/run-chain input-state))
