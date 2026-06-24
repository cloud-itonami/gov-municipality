# permit_submission Cell

**Murakumo node**: judah (regulatory specialist)

**Input**: `projectScope` (siteId, buildingType, construction cost)  
**Output**: `permitApplicationId` + jurisdiction metadata

**5-node LangGraph**:
1. jurisdiction_identified — lookup jurisdiction from siteId
2. template_selected — match permit template (Japan/US/EU)
3. application_prepared — fill application data
4. submitted — RPC submit to municipal authority

**Gate**: G2 (permit documents IPFS-pinned)

**Status**: R0 scaffold (mock RPC)
