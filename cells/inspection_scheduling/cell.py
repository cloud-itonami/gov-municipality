from __future__ import annotations
from typing import Any
from langgraph.graph import StateGraph

class InspectionSchedulingCell:
    def __init__(self) -> None:
        self.graph: StateGraph[dict[str, Any]] | None = None

    def _initialize_state(self, state: dict[str, Any]) -> dict[str, Any]:
        return {"inspection_state": {"phase": "init", "projectId": state.get("projectId", "unknown"), "completionPct": 0}, "next_node": "fetch"}

    def _fetch_permit_status(self, state: dict[str, Any]) -> dict[str, Any]:
        return {"inspection_state": {**state.get("inspection_state", {}), "phase": "permit_verified", "completionPct": 25}, "next_node": "rules"}

    def _jurisdiction_rules(self, state: dict[str, Any]) -> dict[str, Any]:
        mock_schedule = {"foundation_inspection": "2026-06-20", "structural_inspection": "2026-07-15", "mep_inspection": "2026-08-10", "finishing_inspection": "2026-09-05", "final_inspection": "2026-09-20"}
        return {"inspection_state": {**state.get("inspection_state", {}), "phase": "schedule_ready", "schedule": mock_schedule, "completionPct": 75}, "next_node": "emit"}

    def _emit_schedule(self, state: dict[str, Any]) -> dict[str, Any]:
        return {"inspection_state": {**state.get("inspection_state", {}), "phase": "complete", "completionPct": 100}, "inspection_schedule_record": {"projectId": state.get("inspection_state", {}).get("projectId"), "schedule": state.get("inspection_state", {}).get("schedule", {})}, "next_node": "end"}

    def _build_graph(self) -> StateGraph[dict[str, Any]]:
        graph = StateGraph(dict)
        graph.add_node("init", self._initialize_state)
        graph.add_node("fetch", self._fetch_permit_status)
        graph.add_node("rules", self._jurisdiction_rules)
        graph.add_node("emit", self._emit_schedule)
        graph.add_edge("init", "fetch")
        graph.add_edge("fetch", "rules")
        graph.add_edge("rules", "emit")
        graph.add_edge("emit", "__end__")
        graph.set_entry_point("init")
        return graph.compile()

    def solve(self, state: dict[str, Any]) -> dict[str, Any]:
        if self.graph is None:
            self.graph = self._build_graph()
        return self.graph.invoke(state)
