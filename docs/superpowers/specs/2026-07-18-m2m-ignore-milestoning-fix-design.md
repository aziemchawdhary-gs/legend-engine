# M2M "Ignore Milestoning" — Fix Design

- **Date:** 2026-07-18
- **Author:** Aziem Chawdhary
- **Status:** Draft — pending user review
- **Prior PR:** `14f9759f2e0` (test-add PR) — introduced three `test.ToFix` probes and one M2M2R coexistence guard test.

## Goal

Make the pure M2M path transparently ignore `<<temporal.*>>` stereotypes on classes it operates over. In a runtime whose connections are all `ModelConnection` / `JsonModelConnection` / `XmlModelConnection` / `ModelChainConnection` (no relational connection), queries like `T.all()`, `T.all(%date)`, `T.all($bdate)`, `$src.prop`, and `$src.prop(%date)` on milestoned classes must all execute as if the milestoning stereotype were absent. Date arguments — hardcoded or variable — must be accepted and ignored.

M2M2R (M2M chained into a Relational store within the same Runtime) must continue to respect milestoning through to the relational leaf, exactly as today.

## Non-goals

- Changing how milestoning behaves in any store other than the pure M2M path.
- Removing the ability to declare `<<temporal.*>>` stereotypes on classes.
- Any change to the wire protocol (`vX_X_X` metamodel classes) beyond what a Pure-side rewrite naturally produces.
- Documentation updates in `docs/engineering/` (deferred to a follow-up doc PR once the fix has landed).

## Architecture

Single intervention point: **extend preval to rewrite milestoning-injected function expressions when the runtime is pure-M2M.**

```
Query --> [preval (extended with milestoning rewrite)] --> [route to store contracts] --> [plan gen] --> [execute]
                ^
                Add a rewrite check at the top of prevalGenericFunctionExpression:
                when State.runtime is pure-M2M, rewrite the leaf milestoning-
                injected getAll variants and milestoned qualified property calls
                to their no-date-arg forms. Preval's existing infrastructure
                handles the AST rebuild (openVariables maintenance, type
                propagation) around the rewrite.
```

**Design revision (2026-07-18):** an initial attempt used a fresh outside-preval walker that manually rebuilt `FunctionExpression` / `InstanceValue` / `LambdaFunction` nodes via `^$fe(parametersValues=...)`. This dropped `openVariables` metadata that Pure's Java-side protocol compiler needs; every query broke on `Variable.accept`. The corrected architecture reuses **preval's** existing traversal (`prevalGenericFunctionExpression` in `preeval.pure`), which already does the openVariables maintenance correctly. The rewrite is a **leaf-node substitution only** — we rebuild the specific milestoning-injected `FunctionExpression` (e.g. `getAll(Class,Date)` → `getAll(Class)`) but let preval handle the enclosing `graphFetch` / `serialize` / lambda tree normally.

The rewriter runs on each `FunctionExpression` as preval walks the tree, gated by a new `runtime` field in preval's `State` (populated at the router entry). Nothing else changes — no `StoreContract` type change, no changes to the M2M `execution` function, no changes to `InMemoryGraphFetchExecutionNode`. The router and downstream code see a tree that already has milestoning args stripped when they should be stripped.

## Components

### C1. New Pure helper: `isPureM2MRuntime`

**File:** `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/runtime/runtimeExtension.pure`

Add:
```pure
function meta::pure::router::runtime::isPureM2MRuntime(runtime:meta::core::runtime::Runtime[1]):Boolean[1]
{
   // True iff every connection in the runtime is a Model-store connection
   // (ModelConnection, JsonModelConnection, XmlModelConnection, ModelChainConnection).
   // False as soon as any non-Model connection is present (e.g. a relational
   // Connection), which is the M2M2R shape and must preserve milestoning.
   $runtime.connectionStores.connection->forAll(c |
      $c->instanceOf(meta::external::store::model::ModelConnection)          ||
      $c->instanceOf(meta::external::store::model::JsonModelConnection)      ||
      $c->instanceOf(meta::external::store::model::XmlModelConnection)       ||
      $c->instanceOf(meta::external::store::model::ModelChainConnection)
   )
}
```

**Rationale for including `ModelChainConnection`:** M2M chained to M2M (chain resolves to another M2M mapping without any relational leaf) is still pure-M2M. The chain resolution happens downstream and does not by itself imply a relational leg. The M2M2R shape is disqualified by the presence of a *separate* relational connection in the same runtime.

### C2. Extend preval with a milestoning rewrite (revised 2026-07-18)

**Design revision:** an initial attempt used a fresh outside-preval walker that manually rebuilt `FunctionExpression` / `InstanceValue` / `LambdaFunction` nodes via `^$fe(parametersValues=...)`. This dropped `openVariables` metadata that Pure's Java-side protocol compiler needs; every query broke on `Variable.accept`. The corrected approach reuses **preval's** own traversal (which already maintains `openVariables` and evaluates children correctly) and only rebuilds the LEAF milestoning-injected node.

**File:** `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/preeval/preeval.pure`

Three sub-changes:

**C2a. Add a `runtime` field to `State`.**

```pure
Class meta::pure::router::preeval::State
{
  ...existing fields...
  runtime            : meta::core::runtime::Runtime[0..1];   // NEW
  ...
}
```

Optional multiplicity — existing callers that construct `State` without runtime continue to work; the milestoning gate returns "not pure M2M" when `runtime` is empty, so no rewrite happens for those callers.

**C2b. Add the leaf rewriter and call it at the top of `prevalGenericFunctionExpression`.**

Add:

```pure
function <<access.private>> meta::pure::router::preeval::stripMilestoningIfNeeded(sfe:FunctionExpression[1], state:meta::pure::router::preeval::State[1]):FunctionExpression[1]
{
   if($state.runtime->isEmpty() || !$state.runtime->toOne()->meta::pure::router::runtime::isPureM2MRuntime(),
      | $sfe,
      | let f = $sfe.func;
        if($f == getAll_Class_1__Date_1__T_MANY_ ||
           $f == getAll_Class_1__Date_1__Date_1__T_MANY_ ||
           $f == getAllForEachDate_Class_1__Date_MANY__T_MANY_,
           | ^$sfe(func = getAll_Class_1__T_MANY_, parametersValues = [$sfe.parametersValues->at(0)]),
           | if($f == getAllVersionsInRange_Class_1__Date_1__Date_1__T_MANY_,
                | ^$sfe(func = getAllVersions_Class_1__T_MANY_, parametersValues = [$sfe.parametersValues->at(0)]),
                | $f->match([
                     qp:QualifiedProperty<Any>[1] |
                        if($qp->meta::pure::milestoning::isMilestonedGeneratedQualifiedProperty() &&
                           $qp->meta::pure::milestoning::isDateArgMilestonedGeneratedQualifiedProperty(),
                           | let noArgQp = $qp->meta::pure::milestoning::switchToNoArgMilestonedGeneratedQualifiedProperty();
                             if($noArgQp->isEmpty(),
                                | $sfe,
                                | ^$sfe(func = $noArgQp->toOne(), parametersValues = [$sfe.parametersValues->at(0)])
                             );,
                           | $sfe
                        );,
                     other:Any[1] | $sfe
                  ])
             )
        )
   )
}
```

Then modify `prevalGenericFunctionExpression`:

```pure
function <<access.private>> meta::pure::router::preeval::prevalGenericFunctionExpression(sfeIn : FunctionExpression[1], state : meta::pure::router::preeval::State[1], extensions:Extension[*]):PrevalWrapper<Any>[1]
{
  let sfe = $sfeIn->stripMilestoningIfNeeded($state);
  // ...existing body unchanged (all references to $sfe now see the rewritten value)...
}
```

The parameter is renamed to `sfeIn`; `sfe` is rebound to the rewritten value at the top. All existing code in the function body (which references `$sfe`) sees the rewritten expression.

**Why this avoids the metadata-corruption issue:**
- We rebuild only the LEAF `FunctionExpression`. The rebuild `^$sfe(func=..., parametersValues=[classArg])` copies `genericType`, `multiplicity`, and other fields from the original.
- Enclosing `graphFetch` / `serialize` / `LambdaFunction` nodes are NOT rebuilt. Preval's normal traversal continues over them, maintaining `openVariables` and type context correctly (see `preeval.pure:415-427`).
- The classArg parameter — a `ValueSpecification` for the class literal — is passed through unchanged.

**C2c. Populate `State.runtime` at the router entry.**

**File:** `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/router_entry.pure`

The router entry constructs preval `State` either directly or by calling a `preval(f, ...)` overload that constructs `State`. Populate `State.runtime` with the invocation's `runtime`. Two implementation options — implementer chooses based on existing structure:

(a) add a new `preval` overload accepting `runtime:Runtime[1]` and populating `State` — call it from router entry; or
(b) construct `State` directly at the router entry with `runtime = $runtime` and call `preval(f, state, extensions)`.

The constraint is that when preval is called from `router::execute`, `State.runtime` equals the invocation's `runtime` argument.

**Rewrite mapping (unchanged from prior design):**

| Before (auto-injected form) | After (rewrite target) |
|---|---|
| `getAll_Class_1__Date_1__T_MANY_(C, d)` | `getAll_Class_1__T_MANY_(C)` |
| `getAll_Class_1__Date_1__Date_1__T_MANY_(C, d1, d2)` | `getAll_Class_1__T_MANY_(C)` |
| `getAllVersionsInRange_Class_1__Date_1__Date_1__T_MANY_(C, d1, d2)` | `getAllVersions_Class_1__T_MANY_(C)` |
| `getAllForEachDate_Class_1__Date_MANY__T_MANY_(C, [d1,d2,...])` | `getAll_Class_1__T_MANY_(C)` |
| milestoned qualified property call `prop(C, d)` | no-arg form via `switchToNoArgMilestonedGeneratedQualifiedProperty` |
| milestoned qualified property call `prop(C, d1, d2)` | no-arg form via `switchToNoArgMilestonedGeneratedQualifiedProperty` |

Date args are dropped verbatim regardless of whether they are literal `%date`, `VariableExpression`, or computed. The rewrite does not evaluate them. Downstream router code correctly handles the non-milestoned `getAll_Class_1__T_MANY_` form (see `isGetAllFunction` in `router_routing.pure:761`).

### C3. Test expansion

**File:** `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/legend/milestoning/IgnoreMilestoningM2M.pure`

Changes to existing tests:
- Remove `meta::pure::profiles::test.ToFix` from all three `testM2MIgnores{Business,Processing,Bitemporal}TemporalSource` functions. They must now pass under the same assertion.

New tests (add fixtures alongside — additional target classes as needed):
1. `testM2MIgnoresMilestonedTarget` — plain source class, `<<temporal.businesstemporal>>` target, mapping `T_Target : Pure { ~src S_Source; fullName : $src.fullName }`, query `T_Target.all() -> graphFetch -> serialize`, plain JSON payload with no date.
2. `testM2MIgnoresBothMilestoned` — milestoned source AND milestoned target (both businesstemporal), no date args.
3. `testM2MIgnoresHardcodedDateOnAll` — milestoned target, query `T_Target.all(%2020-10-15) -> graphFetch(...)`, plain JSON. Date is present but must be ignored — assertion is same as case 1 (result unaffected by date).
4. `testM2MIgnoresVariableDateOnAll` — milestoned target, query is a `{bdate:DateTime[1]|T_Target.all($bdate) -> graphFetch(...)}` lambda with `pair('bdate', '2020-10-15T00:00:00')`, plain JSON. Same assertion as case 1.
5. `testM2MIgnoresMilestonedProperty` — milestoned source, plain target, mapping body uses `$src.vehicle.name` where `vehicle` is a milestoned qualified property. No date args.
6. `testM2MIgnoresHardcodedDateOnProperty` — same as 5 but mapping body has `$src.vehicle(%2020-10-15).name`. Date accepted, must be ignored (assertion identical to case 5).

Each new test uses `<<meta::pure::profiles::test.Test, meta::pure::profiles::test.AlloyOnly>>` — no `test.ToFix`, no `serverVersion.start` guard (they can run from `v1_33_0` onward as with the existing three).

Preval-level unit tests (optional but recommended for the walker) can live in `core/pure/router/preeval/tests.pure` — a handful of assertions that the rewriter produces the expected `FunctionExpression` shape without going through full execute. Add only if the walker's internal branches are non-trivial; if the walker is a simple pattern match, the six end-to-end tests give sufficient coverage.

### C4. Regression guards (must stay green — no code changes here, only re-run and confirm)

- `MilestonedM2M.pure::testM2MMappingWithMilestonedModelsWithAllVersions` — the pre-existing positive test with hardcoded date. After the fix, the `.all(%2020-10-15)` and `$src.vehicle(%2020-10-15).name` will be rewritten to their no-date forms. The assertion should still hold because the JSON payload contains a single VehicleOwner and a single Vehicle — there is nothing to filter out even with milestoning; the rewrite is behaviourally invariant on this input.
- `m2mIgnoresMilestoningCoexistenceGuard::testM2M2RStillFiltersHardcodedDate` — must still filter at the relational leaf because `isPureM2MRuntime` returns false for that runtime (it includes an H2 connection).
- `milestonedSourceToMilestonedTargetProperty::testWithHardcodedDate` (existing M2M2R reference) — same reasoning.

## Empirical checkpoint (compile-time risk)

The comprehensive scope introduces test shapes we have not yet observed compiling — specifically target-milestoned (`T_Target.all()` where T_Target is milestoned) and milestoned property navigation without a date. If the Pure compiler's milestoning auto-injector rejects `T_Target.all()` at compile time with an error like "must be either called in a milestoning context or supplied with [businessDate] parameters", the fix will need a second change: either relax the auto-injector when the class is in a pure-M2M usage context, or emit a synthetic date placeholder that the runtime rewrite then strips.

**Empirical outcome (2026-07-19):** the compile-time auto-injector DOES reject these cases. Two shapes are compile-time-blocked:
- `T_Milestoned.all()` (no date arg on milestoned target)
- `$src.milestonedProperty.subProperty` inside a mapping body (no date arg on milestoned qualified property call in the mapping expression)

A third shape compiles but hits a runtime protocol-serializer issue:
- `$src.milestonedProperty(%date).subProperty` (hardcoded date on milestoned property navigation inside a mapping body) — `Unexpected character (*) at position 38` in the PMCD JSON round-trip. Distinct from the Layer-1/2 issues we fixed.

**Decision:** compile-time work is deferred to a follow-up PR. The current PR ships Layer 1 + Layer 2 which cover the shapes that compile and route through our fix. Two tests are marked `test.ToFix` for the compile-and-runtime-issue variants; two test shapes (`testM2MIgnoresMilestonedTarget`, `testM2MIgnoresBothMilestoned`) are dropped from this PR entirely because they cannot even be authored today — the follow-up PR will re-introduce them once the compile-time relaxation lands.

## Success criteria

- All nine tests in `IgnoreMilestoningM2M.pure` (three existing + six new) pass in the M2M test suite.
- `MilestonedM2M.pure::testM2MMappingWithMilestonedModelsWithAllVersions` still passes.
- `m2mIgnoresMilestoningCoexistenceGuard::testM2M2RStillFiltersHardcodedDate` still passes.
- `milestonedSourceToMilestonedTargetProperty::testWithHardcodedDate` still passes.
- No unrelated M2M or M2M2R test regresses. Full M2M suite total goes from 210 to 210 + 9 = 219 (or however many new tests we add), all passing.
- Checkstyle: 0 violations.
- `mvn clean install -DskipTests -pl <core-pure-compiled-core> -am -T 4`: BUILD SUCCESS.
- `mvn clean test -pl <server-http-server> -Dtest=Test_M2M_UsingPureClientTestSuite -DfailIfNoTests=false`: BUILD SUCCESS.

## Deferred to follow-up PRs

- Documentation update in `docs/engineering/architecture/` describing the new pure-M2M milestoning-ignore behavior and its M2M2R counterpart.
- The existing `test.ToFix` cases in the M2M2R milestoning suite (variable-date, NoArg mapping via includes) — these are M2M2R bugs, not pure-M2M, and unrelated to this PR.
- Any store-contract-level generalization of "which stores ignore milestoning" — if we ever grow the set of milestoning-ignoring stores, we can promote the predicate to a StoreContract field then.

## Open questions carried forward (not blocking this PR)

- Should the rewrite be visible in the plan output (e.g. logged) for debuggability, or is silent rewrite better? Default: silent. Revisit if plan-output confusion arises.
- Does the preval-stage rewrite interact correctly with `ExecutionContext` overrides (e.g. `AuthContext`, `VectorBatchQueryContext`) that route around the standard execute path? Verify empirically during implementation; the six new tests do not exercise these contexts.
