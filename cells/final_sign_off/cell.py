from __future__ import annotations
from typing import Any
from langgraph.graph import StateGraph

class FinalSignOffCell:
    def __init__(self) -> None:
        self.graph: StateGraph[dict[str, Any]] | None = None

    def _initialize_state(self, state: dict[str, Any]) -> dict[str, Any]:
        return {"signoff_state": {"phase": "init", "projectId": state.get("projectId", "unknown"), "completionPct": 0}, "next_node": "validate"}

    def _validate_inspections(self, state: dict[str, Any]) -> dict[str, Any]:
        return {"signoff_state": {**state.get("signoff_state", {}), "phase": "inspections_validated", "completionPct": 40}, "next_node": "request"}

    def _request_authority_signature(self, state: dict[str, Any]) -> dict[str, Any]:
        mock_sig = {"authority_did": "did:web:tokyo.lg.jp:building", "signature": "aB3cD6eF9gH...", "occupancy_clearance": True}
        return {"signoff_state": {**state.get("signoff_state", {}), "phase": "signed", "signature": mock_sig, "completionPct": 100}, "next_node": "emit"}

    def _emit_occupancy_clearance(self, state: dict[str, Any]) -> dict[str, Any]:
        return {"signoff_state": state.get("signoff_state", {}), "permits_finalized_record": {"projectId": state.get("signoff_state", {}).get("projectId"), "occupancy_clearance": True, "authority_signature": state.get("signoff_state", {}).get("signature", {})}, "next_node": "end"}

    def _build_graph(self) -> StateGraph[dict[str, Any]]:
        graph = StateGraph(dict)
        graph.add_node("init", self._initialize_state)
        graph.add_node("validate", self._validate_inspections)
        graph.add_node("request", self._request_authority_signature)
        graph.add_node("emit", self._emit_occupancy_clearance)
        graph.add_edge("init", "validate")
        graph.add_edge("validate", "request")
        graph.add_edge("request", "emit")
        graph.add_edge("emit", "__end__")
        graph.set_entry_point("init")
        return graph.compile()

    def solve(self, state: dict[str, Any]) -> dict[str, Any]:
        if self.graph is None:
            self.graph = self._build_graph()
        return self.graph.invoke(state)
