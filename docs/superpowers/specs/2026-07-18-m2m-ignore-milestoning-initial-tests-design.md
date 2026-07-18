# M2M "Ignore Milestoning" — Initial Test Additions

- **Date:** 2026-07-18
- **Author:** Aziem Chawdhary
- **Status:** Draft — pending user review

## Goal

Add exploratory Pure tests that assert the intended end-state: **the pure M2M path transparently ignores milestoning stereotypes on the classes it operates over.** Milestoned classes should behave in an M2M mapping/runtime as if the `<<temporal.*>>` stereotype were absent — no date argument required on `.all()`, no date argument required on milestoned properties. Also add one guard test asserting that **M2M2R** (M2M chained into a relational store) continues to respect milestoning through to the relational leaf.

This PR is **test-add only.** No production code changes. The tests are expected to fail on the pure-M2M side today; observing their failure modes will inform a follow-up PR that implements the actual "ignore" behavior.

## Non-goals (deferred to follow-up PRs)

- Implementing the router / planner / executor changes that make the M2M tests pass.
- Variable-date (bind-var) support in the pure M2M path.
- Fixes for the existing M2M2R `test.ToFix` cases (`testWithVariableDate`, `testWithHardcodedDate_ViaNoArgMapping`).
- Documentation updates in `docs/engineering/` describing the new behavior — done once the fix lands.

## Current state (as of 2026-07-18)

**Pure M2M:**

- One existing test in `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/legend/milestoning/MilestonedM2M.pure` — `testM2MMappingWithMilestonedModelsWithAllVersions()`. It uses `<<temporal.businesstemporal>>` on both source (`S_VehicleOwner`) and target (`T_VehicleOwner`), and passes a hardcoded `%2020-10-15` businessDate on both `.all(...)` and property access (`$src.vehicle(%2020-10-15).name`). No coverage of processing- or bitemporal. No coverage of the "no date arg supplied" case.

**M2M2R:**

- `legend-engine-xts-relationalStore/.../modelToModelToRelational/milestoned/milestonedSourceToMilestonedTargetProperty.pure` — three tests: `testWithHardcodedDate` (passes), `testWithVariableDate` (marked `test.ToFix`), `testWithHardcodedDate_ViaNoArgMapping` (marked `test.ToFix`).
- Sibling files cover `nonMilestonedSourceToMilestonedTargetProperty` and `milestonedSourceToNonMilestonedTargetProperty`.

**Gap:** no test exists that asserts "M2M ignores milestoning" — i.e. a query against a milestoned class in a pure-M2M runtime that supplies **no** date parameter anywhere.

## Test additions

### 1. Pure M2M — three "ignore" tests (all expected to fail today)

**New file:** `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/legend/milestoning/IgnoreMilestoningM2M.pure`

Each test uses this common shape:

- A tiny source class marked with one temporal stereotype (business / processing / bitemporal).
- A tiny target class with **no** temporal stereotype.
- A PureInstance M2M mapping: `Target : Pure { ~src Source; <field> : $src.<field> }`.
- Query: `Target.all() -> graphFetch(#{Target{...}}#) -> serialize(...)` — no date argument anywhere.
- Runtime: `JsonModelConnection` with plain JSON — no `businessDate` / `processingDate` fields in the JSON either.
- Assertion: serialized output equals a plain JSON object.

Test functions:

- `meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::testM2MIgnoresBusinessTemporalSource()`
- `meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::testM2MIgnoresProcessingTemporalSource()`
- `meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::testM2MIgnoresBitemporalSource()`

Each test carries stereotypes `<<meta::pure::profiles::test.Test, meta::pure::profiles::test.AlloyOnly>>` so they are picked up by the existing M2M test-collection harness (`Test_M2M_UsingPureClientTestSuite`).

**Explicit non-decision:** we are *not* deciding here whether the target class also being milestoned matters — the initial three tests all pair a milestoned source with a plain target. That is the simplest probe of the intent "M2M ignores milestoning on classes it sees." Additional shapes (target milestoned, both milestoned) can be added once the initial failure modes are understood.

### 2. M2M2R — one coexistence guard test

**New file:** `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/modelToModelToRelational/milestoned/m2mIgnoresMilestoningCoexistenceGuard.pure`

- Reuses the existing milestoned-model fixtures from `shared.pure` in the same folder (no new classes).
- One test function: `meta::pure::graphFetch::tests::m2m2r::milestoning::coexistenceGuard::testM2M2RStillFiltersHardcodedDate()`.
- Query mirrors the shape of the currently-passing `testWithHardcodedDate` — hardcoded `%date` on both `.all(...)` and the milestoned property inside the graphFetch tree.
- Assertion: the relational leaf still applies the milestoning WHERE-clause and returns the expected filtered rows.
- Marked `<<test.Test, test.AlloyOnly>>` so it runs alongside the existing M2M2R milestoning suite.

Purpose: this test is *not* redundant with the existing suite. It provides an explicit in-PR assertion that the M2M2R milestoning path is intact using the same fixtures the "ignore" tests would touch. When the follow-up PR implements the M2M-side ignore behavior, this test guards against accidentally breaking M2M2R.

## Success criteria (for this PR)

- The three new pure-M2M test functions are picked up by the M2M test harness.
- The one new M2M2R test function is picked up by the M2M2R test harness.
- The three pure-M2M tests fail (or, if they unexpectedly pass, we investigate why — the current state suggests they should fail).
- The M2M2R guard test passes.
- Failure output from the pure-M2M tests is captured in the PR description, showing exactly how milestoning currently rejects the query so the follow-up fix has a clear target.

## Deferred to follow-up PRs

- **Fix PR:** implement the "M2M ignores milestoning" behavior. Likely candidates for where the change lives (to be validated in a separate design):
  - Router-level pre-processing that strips milestoning wrappers on subexpressions routed to the M2M store contract, provided the routing has no relational downstream.
  - Store-contract-level declaration that PureInstance mappings do not participate in milestoning post-processing.
  - The M2M in-memory execution node (`InMemoryGraphFetchExecutionNode`) treating milestoned classes as non-milestoned for `getAll`/property navigation.
- **Coverage expansion PR:** add target-milestoned and both-milestoned variants once the fix is in.

## Open questions carried forward (not blocking this PR)

- Are there any grammar-side rejections of `Target.all()` for milestoned target that happen before we even reach the router? If so, the fix may need to touch parser/compiler as well as router.
- How does the fix interact with the M2M2R chain when the top query is a pure-M2M expression whose value is later joined into the relational tree? The coexistence guard test only covers the "direct M2M2R query" shape.
