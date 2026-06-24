# 20-actors/gov-municipality — CLAUDE.md

## Identity
- **Name**: gov-municipality (政府・自治体 — government + municipal authority)
- **DID**: `did:web:etzhayyim.com:gov-municipality`
- **ADR**: ADR-2605250800 (R0 scaffold, 2026-05-26)
- **Status**: R0 scaffold — all cells import-time RuntimeError
- **Parent actor**: etzhayyim religious-corp (Phase 0 permitting, pre-tatekata)

## Architecture

3 Pregel cells in linear Phase sequence:

```
permit_submission → inspection_scheduling → final_sign_off
      (judah)            (benjamin)              (dan)
```

Each cell = 1 Pregel graph with super-step semantics (5–7 LangGraph nodes).
Cells communicate via lexicon records on MST (`com.etzhayyim.gov.*`).

## Jurisdictional Coverage

**R0**: Template stubs only (Japan, US, EU routing logic)
**R1**: Japan 建築確認申請 (Tokyo, Osaka, Kyoto sample), US IBC/NEC, EU EN 12354
**R2**: 10+ jurisdictions (5 Japanese prefectures, 5 US states, 3 EU countries)
**R3**: Live RPC integration (municipal database APIs)

## Constitutional Gates (G1–G9)

**IMMUTABLE in R0 and R1.** Stored in `manifest.jsonld` under `gov:constitutionalGates`.

- **G1**: All jurisdiction RPC calls open-source (no proprietary API wrappers)
- **G2**: Permit documents IPFS-pinned **before** construction equipment enters site
- **G3**: Municipal authority witness signature (≥1 jurisdiction)
- **G5**: Charter Rider compliance — no gatekeeping (permits granted within legal timeline)
- **G9**: 30-day public notice period before permit finalized (mesh transparency)

## Non-Goals (N1–N5)

**EXCLUDED from R0–R3 scope**:

- N1: Zoning variance negotiation (user task)
- N2: Environmental impact assessment (separate actor)
- N3: Historical preservation (separate actor)
- N4: Utility company approvals (→ infra-utility-connect, Phase 5+)
- N5: Financing/cost estimation (capital domain)

## Lexicon Namespace

**App lexicon root**: `com.etzhayyim.gov`

**Records** (3 types):

1. **`com.etzhayyim.gov.permitApplication`** — Submission data (project scope, location, drawings CID)
2. **`com.etzhayyim.gov.inspectionSchedule`** — Inspection slots + requirements per jurisdiction
3. **`com.etzhayyim.gov.permitsFinalizedRecord`** — Municipal sign-off + occupancy clearance

## Pregel Cells (Detailed)

### permit_submission
- **Input**: `projectScope` (siteId, buildingType, totalCost, GFA_m2)
- **Output**: `permitApplicationId` + `jurisdiction` metadata
- **LangGraph nodes** (placeholder in R0):
  1. `parse_scope` — extract jurisdiction from siteId
  2. `jurisdiction_lookup` — RPC → municipal authority
  3. `prepare_application` — template matching (Japan/US/EU)
  4. `submit_permit` → write `permitApplication` to MST

### inspection_scheduling
- **Input**: `permitApplicationId`, `constructionPhases`
- **Output**: `inspectionSchedule` (phase → inspection date + requirements)
- **LangGraph nodes** (placeholder):
  1. `fetch_permit_status` — RPC → check approval
  2. `jurisdiction_rules` — lookup inspection cadence per jurisdiction
  3. `schedule_inspections` — 5 inspection slots (foundation / structural / MEP / finishing / commissioning)
  4. `emit_schedule` → write `inspectionSchedule` to MST

### final_sign_off
- **Input**: `inspectionResults` (pass/conditional/fail per phase), defect list
- **Output**: `permitsFinalizedRecord` (authority signature, occupancy)
- **LangGraph nodes** (placeholder):
  1. `validate_inspections` — all phases ≥conditional
  2. `request_authority_signature` — RPC → municipal signatory
  3. `emit_occupancy_clearance` → write `permitsFinalizedRecord` to MST

## Testing (R0)

**Smoke test**: Verify all 3 cells import without exception:
```bash
cd 20-actors/gov-municipality
python -c "from cells.permit_submission import PermitSubmissionCell; assert PermitSubmissionCell"
python -c "from cells.inspection_scheduling import InspectionSchedulingCell; assert InspectionSchedulingCell"
python -c "from cells.final_sign_off import FinalSignOffCell; assert FinalSignOffCell"
```

All should pass import; `.solve()` calls should raise `RuntimeError("gov-municipality R0 scaffold...")`.

## Related Files

- `/20-actors/gov-municipality/manifest.jsonld` — DID + cell registry
- `/90-docs/adr/2605250800-gov-municipality-phase-0-permits-r0.md` — ADR (parent)
- `/CLAUDE.md` — Status table row TBD (gov-municipality)
