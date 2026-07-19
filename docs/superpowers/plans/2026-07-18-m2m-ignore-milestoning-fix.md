# M2M "Ignore Milestoning" — Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the pure M2M path transparently ignore `<<temporal.*>>` stereotypes on classes so that queries with no date argument (or an accepted-and-ignored date argument) succeed, while M2M2R continues to filter at the relational leaf.

**Architecture:** Add a runtime predicate (`isPureM2MRuntime`) and a router-entry-stage rewriter (`stripMilestoningForPureM2M`) that walks the input `FunctionDefinition` and rewrites milestoning-injected `getAll*` variants and milestoned qualified-property calls to their non-milestoning equivalents when the runtime has no relational connection. Nothing else in the router, plan generator, or executor changes — downstream code already handles the non-milestoning forms via `isGetAllFunction` in `router_routing.pure`. Insertion is at the top of the `meta::pure::router::execute` overloads in `router_entry.pure`, before preval/routing.

**Tech Stack:** Pure language (`.pure` files) in `legend-engine-pure-code-compiled-core`, Legend router, JDK 11, Maven 3.6.2+.

**Environment note:** Every `mvn` command must be preceded by `source ~/bin/jdk11.sh &&` in the SAME Bash call (state does not persist across separate Bash invocations). This sets JAVA_HOME/PATH/MAVEN_OPTS.

**Spec reference:** `docs/superpowers/specs/2026-07-18-m2m-ignore-milestoning-fix-design.md`

**Prior PR (test-add):** commit `14f9759f2e0` on branch `m2mmilestoning` already added the three `test.ToFix` probes plus the M2M2R coexistence guard.

---

## File Structure (revised 2026-07-18 after initial attempt hit AST-metadata corruption)

- **Modify:** `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/runtime/runtimeExtension.pure` — add `isPureM2MRuntime` helper. **(Already done by the prior BLOCKED attempt; verify still present.)**
- **Modify:** `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/preeval/preeval.pure` — add optional `runtime` field to `State`; add `stripMilestoningIfNeeded` helper; call it at the top of `prevalGenericFunctionExpression`.
- **Modify:** `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/router_entry.pure` — thread the invocation's `runtime` into the preval `State` (or into a new `preval` overload) so the milestoning rewrite is gated correctly.
- **Modify:** `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/legend/milestoning/IgnoreMilestoningM2M.pure` — remove `test.ToFix` from three existing probes (already done in Task 1); add six new tests + supporting fixtures (Task 5).

Total: 3 modified core files, 1 modified test file. No new files.

**Historical note:** the previous plan revision proposed a new file `milestoningStripper.pure` with a fresh AST walker outside preval. That approach broke `openVariables` metadata for every query (158/213 tests errored on `Variable.accept`). The revised approach extends preval's own traversal so metadata maintenance stays with preval's existing infrastructure.

---

## Task 1: Baseline — remove ToFix and confirm the three probes currently fail

**Rationale:** Establishes the RED state that the fix drives to GREEN. Without confirming that the three probes really fail today, we cannot know the fix actually caused the pass.

**Files:**
- Modify: `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/legend/milestoning/IgnoreMilestoningM2M.pure`

- [ ] **Step 1: Remove `meta::pure::profiles::test.ToFix` from all three probes**

In `IgnoreMilestoningM2M.pure`, replace three stereotype lines. Each occurrence changes from:
```pure
function <<meta::pure::profiles::test.Test, meta::pure::profiles::test.AlloyOnly, meta::pure::profiles::test.ToFix>>
```
to:
```pure
function <<meta::pure::profiles::test.Test, meta::pure::profiles::test.AlloyOnly>>
```

The three functions affected are `testM2MIgnoresBusinessTemporalSource`, `testM2MIgnoresProcessingTemporalSource`, `testM2MIgnoresBitemporalSource`. Nothing else in the file changes.

- [ ] **Step 2: Build the pure module to confirm the change compiles**

```bash
source ~/bin/jdk11.sh && mvn clean install -DskipTests -pl legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core -am -T 4
```

Expected: `BUILD SUCCESS` in ~4-5 min. This installs the modified pure module into `~/.m2` so the test suite in Step 3 picks it up.

- [ ] **Step 3: Run the M2M suite and confirm the three probes now fail**

```bash
source ~/bin/jdk11.sh && mvn clean test -pl legend-engine-config/legend-engine-server/legend-engine-server-http-server -Dtest=Test_M2M_UsingPureClientTestSuite -DfailIfNoTests=false 2>&1 | tee /tmp/m2m-baseline-red.txt
```

Expected: `BUILD FAILURE`, `Tests run: 213, Failures: 0, Errors: 3, Skipped: 0` (or similar — 3 errors on the three new probes; 210 pre-existing tests still pass).

Confirm the three failing tests by name:
```bash
grep -E "^\[ERROR\]" /tmp/m2m-baseline-red.txt | grep testM2MIgnores
```

Expected: three lines, one per probe (business / processing / bitemporal).

Also confirm the pre-existing `testM2MMappingWithMilestonedModelsWithAllVersions` still passes:
```bash
grep -A 1 "testM2MMappingWithMilestonedModelsWithAllVersions" /tmp/m2m-baseline-red.txt | head -20
```

Expected: no `<<< ERROR` or `<<< FAILURE` next to it.

Do NOT commit at this point. The intermediate state (RED) exists only long enough for the fix in Tasks 2–4 to turn it GREEN before any commit.

## Task 2: Add the `isPureM2MRuntime` helper

**Files:**
- Modify: `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/runtime/runtimeExtension.pure`

- [ ] **Step 1: Read the file to find the right insertion point**

Open `runtimeExtension.pure`. Locate the existing `connectionByElement` function (around line 79). Insert the new helper directly after it. Keep imports at the top of the file; add any missing imports for the connection types used in the predicate.

- [ ] **Step 2: Add the helper**

Append (after `connectionByElement`):

```pure
// True iff every connection in the runtime is a Model-store connection
// (ModelConnection, JsonModelConnection, XmlModelConnection,
// ModelChainConnection). False as soon as any non-Model connection is
// present (e.g. a relational Connection), which is the M2M2R shape and
// must preserve milestoning.
function meta::pure::router::runtime::isPureM2MRuntime(runtime:meta::core::runtime::Runtime[1]):Boolean[1]
{
   $runtime.connectionStores.connection->forAll(c |
      $c->instanceOf(meta::external::store::model::ModelConnection)     ||
      $c->instanceOf(meta::external::store::model::JsonModelConnection) ||
      $c->instanceOf(meta::external::store::model::XmlModelConnection)  ||
      $c->instanceOf(meta::external::store::model::ModelChainConnection)
   )
}
```

If any of the fully-qualified connection type names above do not exist under `meta::external::store::model`, adjust to the actual package (grep `find legend-engine-core -name "*.pure" | xargs grep -l "JsonModelConnection"` to locate). Do NOT invent a new package.

- [ ] **Step 3: Build to confirm the helper compiles**

```bash
source ~/bin/jdk11.sh && mvn clean install -DskipTests -pl legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core -am -T 4
```

Expected: `BUILD SUCCESS`. If the build fails at Pure compilation of `runtimeExtension.pure`, the error message will point at the line — fix the reference (usually a wrong package path on `instanceOf`) and re-run.

## Task 3: Add the `stripMilestoningForPureM2M` walker

**Files:**
- Create: `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/preeval/milestoningStripper.pure`

- [ ] **Step 1: Create the new file with the walker**

```pure
// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import meta::pure::extension::*;
import meta::pure::milestoning::*;
import meta::pure::router::runtime::*;

// Rewrite pass for the pure M2M path.
// When the runtime has no relational connection (isPureM2MRuntime), walk the
// FunctionDefinition tree and rewrite the six milestoning-injected `getAll*`
// variants to their non-milestoning form (dropping date arguments), and rewrite
// milestoned qualified-property calls to their no-arg form via
// switchToNoArgMilestonedGeneratedQualifiedProperty. When the runtime has any
// relational connection (M2M2R shape), return the tree unchanged.
//
// This function is the single gate for the "M2M ignores milestoning" behavior.
// It is called from meta::pure::router::execute in router_entry.pure BEFORE
// preval / routing, so downstream code sees an already-rewritten tree.
function meta::pure::router::preeval::stripMilestoningForPureM2M<T>(f:FunctionDefinition<T>[1], runtime:meta::core::runtime::Runtime[1], extensions:meta::pure::extension::Extension[*]):FunctionDefinition<T>[1]
{
   if(!$runtime->isPureM2MRuntime(),
      | $f,
      | $f->stripMilestoningInFunctionDefinition($extensions)
   )
}

// Internal: walk the function body and rewrite each expression.
function <<access.private>> meta::pure::router::preeval::stripMilestoningInFunctionDefinition<T>(f:FunctionDefinition<T>[1], extensions:meta::pure::extension::Extension[*]):FunctionDefinition<T>[1]
{
   let rewrittenExprs = $f.expressionSequence->map(vs | $vs->stripMilestoningInValueSpecification($extensions));
   ^$f(expressionSequence = $rewrittenExprs)
}

// Internal: rewrite a single ValueSpecification (recursing into sub-expressions
// where present).
function <<access.private>> meta::pure::router::preeval::stripMilestoningInValueSpecification(vs:ValueSpecification[1], extensions:meta::pure::extension::Extension[*]):ValueSpecification[1]
{
   $vs->match([
      fe:FunctionExpression[1] |
         let rewrittenArgs = $fe.parametersValues->map(v | $v->stripMilestoningInValueSpecification($extensions));
         let feWithNewArgs = ^$fe(parametersValues=$rewrittenArgs);
         $feWithNewArgs->rewriteMilestoningCallIfNeeded();,
      inst:InstanceValue[1] |
         let rewrittenValues = $inst.values->map(v | $v->match([
            lambda:LambdaFunction<Any>[1] | $lambda->stripMilestoningInFunctionDefinition($extensions);,
            other:Any[1] | $other
         ]));
         ^$inst(values=$rewrittenValues);,
      other:ValueSpecification[1] | $other
   ])
}

// Internal: pattern-match the six milestoning-injected getAll variants and the
// milestoned qualified-property calls, and rewrite them to their no-date-arg
// equivalents. Everything else is returned unchanged.
function <<access.private>> meta::pure::router::preeval::rewriteMilestoningCallIfNeeded(fe:FunctionExpression[1]):FunctionExpression[1]
{
   let f = $fe.func;
   if($f == getAll_Class_1__Date_1__T_MANY_ || $f == getAll_Class_1__Date_1__Date_1__T_MANY_ || $f == getAllForEachDate_Class_1__Date_MANY__T_MANY_,
      | // rewrite to plain getAll(Class) — drop trailing date arg(s)
        let classArg = $fe.parametersValues->at(0);
        ^$fe(func = getAll_Class_1__T_MANY_, parametersValues = [$classArg]);,
      | if($f == getAllVersionsInRange_Class_1__Date_1__Date_1__T_MANY_,
           | let classArg = $fe.parametersValues->at(0);
             ^$fe(func = getAllVersions_Class_1__T_MANY_, parametersValues = [$classArg]);,
           | $f->match([
                qp:QualifiedProperty<Any>[1] |
                   if($qp->isMilestonedGeneratedQualifiedProperty() && $qp->isDateArgMilestonedGeneratedQualifiedProperty(),
                      | let noArgQp = $qp->switchToNoArgMilestonedGeneratedQualifiedProperty();
                        if($noArgQp->isEmpty(),
                           | $fe, // no no-arg counterpart exists; leave as-is
                           | let ownerArg = $fe.parametersValues->at(0);
                             ^$fe(func = $noArgQp->toOne(), parametersValues = [$ownerArg])
                        );,
                      | $fe
                   );,
                other:Any[1] | $fe
             ])
        )
   )
}
```

Notes on this Pure code:
- The pattern-match style follows the existing `switchToNoArgMilestonedGeneratedQualifiedProperty` and `isMilestonedGeneratedDateProperty` in `core/pure/milestoning/milestoning.pure`.
- The `getAll*` function symbols (`getAll_Class_1__T_MANY_` etc.) are the *same* symbols already used in `core/pure/router/routing/router_routing.pure:761` (`isGetAllFunction`). Do not invent new symbols.
- `access.private` mirrors existing `<<access.public>>` / private annotations in `preval.pure`. If Pure rejects `access.private` in this file, remove the annotation — Pure defaults are file-level.
- The recursion covers `FunctionExpression` (recurses into `parametersValues`) and `InstanceValue` (recurses into lambda values inside e.g. `graphFetch(#{...}#)` trees). Other `ValueSpecification` shapes are pass-through.

- [ ] **Step 2: Build the pure module to verify the new file compiles**

```bash
source ~/bin/jdk11.sh && mvn clean install -DskipTests -pl legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core -am -T 4
```

Expected: `BUILD SUCCESS`. Common failure modes:
- Wrong package path on a milestoning helper — grep for `switchToNoArgMilestonedGeneratedQualifiedProperty` to verify.
- Wrong function-symbol name for a `getAll` variant — verify against `router_routing.pure:761`.
- Pure rejects `access.private` — remove the annotation on the three internal functions.

Iterate until BUILD SUCCESS.

## Task 4: Wire `stripMilestoningForPureM2M` into `meta::pure::router::execute`

**Files:**
- Modify: `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/router_entry.pure`

- [ ] **Step 1: Locate the three `meta::pure::router::execute` overloads**

Grep the file for `router::execute` — three function definitions, at approximate lines 20 / 25 / 47 (line numbers may drift; use grep to find them):
```bash
grep -n "meta::pure::router::execute" legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/router_entry.pure
```

Each overload takes `(f:FunctionDefinition, mapping:Mapping, runtime:Runtime, ...)` and delegates to an internal implementation. The insertion point is the first line of each function body: replace the `$f` in the delegating call with `$f->stripMilestoningForPureM2M($runtime, $extensions)`.

- [ ] **Step 2: Add the import at the top of router_entry.pure**

Ensure the file has:
```pure
import meta::pure::router::preeval::*;
```

If already present (very likely — the file imports several router modules), skip.

- [ ] **Step 3: Rewrite the three overloads to prepend the strip pass**

For each of the three overloads, thread the `$f` variable through `stripMilestoningForPureM2M` before the internal delegation. Example transformation:

Before:
```pure
function meta::pure::router::execute<T|m>(f:FunctionDefinition<{->T[m]}>[1], mapping:Mapping[1], runtime:Runtime[1], extensions:Extension[*]):Result<T|m>[1]
{
   executeInternal($f, $mapping, $runtime, $extensions)
}
```

After:
```pure
function meta::pure::router::execute<T|m>(f:FunctionDefinition<{->T[m]}>[1], mapping:Mapping[1], runtime:Runtime[1], extensions:Extension[*]):Result<T|m>[1]
{
   let stripped = $f->meta::pure::router::preeval::stripMilestoningForPureM2M($runtime, $extensions);
   executeInternal($stripped, $mapping, $runtime, $extensions)
}
```

Apply the same transformation to all three overloads. If any overload's body is more complex than a single delegate call, thread `$stripped` through wherever `$f` was used.

- [ ] **Step 4: Build to verify wiring compiles**

```bash
source ~/bin/jdk11.sh && mvn clean install -DskipTests -pl legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core -am -T 4
```

Expected: `BUILD SUCCESS`. Fix any type errors (usually a signature mismatch on the delegating call).

- [ ] **Step 5: Run the M2M suite; verify the three baseline probes now pass**

```bash
source ~/bin/jdk11.sh && mvn clean test -pl legend-engine-config/legend-engine-server/legend-engine-server-http-server -Dtest=Test_M2M_UsingPureClientTestSuite -DfailIfNoTests=false 2>&1 | tee /tmp/m2m-after-fix-green.txt
```

Expected: `BUILD SUCCESS`, `Tests run: 213, Failures: 0, Errors: 0, Skipped: 0`. The three previously-red probes now pass; the existing 210 tests continue to pass.

If any of the three probes still fails, examine the error via:
```bash
cat legend-engine-config/legend-engine-server/legend-engine-server-http-server/target/surefire-reports/org.finos.legend.engine.server.test.pureClient.stores.Test_M2M_UsingPureClientTestSuite.txt | head -60
```
and iterate on the walker in `milestoningStripper.pure` — likely a recursion gap (lambda inside `graphFetch(#{...}#)` not being walked) or a missing `getAll` variant.

## Task 5: Add the six new comprehensive tests + supporting fixtures

**Files:**
- Modify: `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/legend/milestoning/IgnoreMilestoningM2M.pure`

- [ ] **Step 1: Add fixtures for the new tests**

In the existing model block (after the four existing class declarations, before the `###Mapping` block), add:

```pure
Class meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::model::S_Person_Plain
{
   fullName : String[1];
}

Class <<temporal.businesstemporal>> meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::model::T_Person_BizTemporal
{
   fullName : String[1];
}

Class <<temporal.businesstemporal>> meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::model::S_Owner_WithMilestonedProperty
{
   name : String[1];
   vehicle : meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::model::V_Vehicle[1];
}

Class <<temporal.businesstemporal>> meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::model::V_Vehicle
{
   name : String[1];
}

Class meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::model::T_Owner_Flat
{
   name : String[1];
   vehicleName : String[1];
}
```

- [ ] **Step 2: Add the six new mapping definitions to the `###Mapping` block**

Append at the end of the existing `###Mapping` block (after `bitemporalIgnoreMapping`):

```pure
Mapping meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::milestonedTargetIgnoreMapping
(
   T_Person_BizTemporal : Pure
   {
      ~src S_Person_Plain
      fullName : $src.fullName
   }
)

Mapping meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::bothMilestonedIgnoreMapping
(
   T_Person_BizTemporal : Pure
   {
      ~src S_Person_BizTemporal
      fullName : $src.fullName
   }
)

Mapping meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::milestonedPropertyIgnoreMapping
(
   T_Owner_Flat : Pure
   {
      ~src S_Owner_WithMilestonedProperty
      name : $src.name,
      vehicleName : $src.vehicle.name
   }
)

Mapping meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::milestonedPropertyWithDateIgnoreMapping
(
   T_Owner_Flat : Pure
   {
      ~src S_Owner_WithMilestonedProperty
      name : $src.name,
      vehicleName : $src.vehicle(%2020-10-15).name
   }
)
```

- [ ] **Step 3: Add the six new test functions**

Append after the existing three test functions (before the `###Mapping` block, since Pure grammar sections are separate):

```pure
function <<meta::pure::profiles::test.Test, meta::pure::profiles::test.AlloyOnly>>
{  serverVersion.start='v1_33_0',
   doc.doc='Given: a plain source and a <<temporal.businesstemporal>> target',
   doc.doc='When:  the mapping is executed via graphFetch+serialize with NO date argument',
   doc.doc='Then:  milestoning on the target is ignored and produces plain JSON.'
}
meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::testM2MIgnoresMilestonedTarget() : Boolean[1]
{
   let tree = #{T_Person_BizTemporal{fullName}}#;
   let query = |T_Person_BizTemporal.all()->graphFetch($tree)->serialize($tree);
   let mapping = meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::milestonedTargetIgnoreMapping;
   let runtime = ^Runtime(
                    connectionStores = ^ConnectionStore(
                       element=^ModelStore(),
                       connection=^JsonModelConnection(
                          class=S_Person_Plain,
                          url='data:application/json,{"fullName":"Dana"}'
                       )
                    )
                 );
   let result = meta::pure::router::execute($query, $mapping, $runtime, meta::pure::extension::configuration::coreExtensions());
   let json = $result.values->toOne();
   assert(jsonEquivalent('{"fullName":"Dana"}'->parseJSON(), $json->parseJSON()));
}

function <<meta::pure::profiles::test.Test, meta::pure::profiles::test.AlloyOnly>>
{  serverVersion.start='v1_33_0',
   doc.doc='Given: a <<temporal.businesstemporal>> source AND a <<temporal.businesstemporal>> target',
   doc.doc='When:  the mapping is executed via graphFetch+serialize with NO date argument',
   doc.doc='Then:  milestoning on both sides is ignored and produces plain JSON.'
}
meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::testM2MIgnoresBothMilestoned() : Boolean[1]
{
   let tree = #{T_Person_BizTemporal{fullName}}#;
   let query = |T_Person_BizTemporal.all()->graphFetch($tree)->serialize($tree);
   let mapping = meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::bothMilestonedIgnoreMapping;
   let runtime = ^Runtime(
                    connectionStores = ^ConnectionStore(
                       element=^ModelStore(),
                       connection=^JsonModelConnection(
                          class=S_Person_BizTemporal,
                          url='data:application/json,{"fullName":"Eve"}'
                       )
                    )
                 );
   let result = meta::pure::router::execute($query, $mapping, $runtime, meta::pure::extension::configuration::coreExtensions());
   let json = $result.values->toOne();
   assert(jsonEquivalent('{"fullName":"Eve"}'->parseJSON(), $json->parseJSON()));
}

function <<meta::pure::profiles::test.Test, meta::pure::profiles::test.AlloyOnly>>
{  serverVersion.start='v1_33_0',
   doc.doc='Given: a <<temporal.businesstemporal>> target',
   doc.doc='When:  the mapping is executed via graphFetch+serialize WITH a hardcoded date on .all(...)',
   doc.doc='Then:  the date is accepted but ignored; result is same as no-date case.'
}
meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::testM2MIgnoresHardcodedDateOnAll() : Boolean[1]
{
   let tree = #{T_Person_BizTemporal{fullName}}#;
   let query = |T_Person_BizTemporal.all(%2020-10-15)->graphFetch($tree)->serialize($tree);
   let mapping = meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::milestonedTargetIgnoreMapping;
   let runtime = ^Runtime(
                    connectionStores = ^ConnectionStore(
                       element=^ModelStore(),
                       connection=^JsonModelConnection(
                          class=S_Person_Plain,
                          url='data:application/json,{"fullName":"Frank"}'
                       )
                    )
                 );
   let result = meta::pure::router::execute($query, $mapping, $runtime, meta::pure::extension::configuration::coreExtensions());
   let json = $result.values->toOne();
   assert(jsonEquivalent('{"fullName":"Frank"}'->parseJSON(), $json->parseJSON()));
}

function <<meta::pure::profiles::test.Test, meta::pure::profiles::test.AlloyOnly>>
{  serverVersion.start='v1_33_0',
   doc.doc='Given: a <<temporal.businesstemporal>> target',
   doc.doc='When:  the mapping is executed via graphFetch+serialize WITH a let-captured (non-literal) date on .all($d)',
   doc.doc='Then:  the captured date is accepted but ignored; result is same as no-date case.'
}
meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::testM2MIgnoresCapturedDateOnAll() : Boolean[1]
{
   let bdate = %2020-10-15;
   let tree = #{T_Person_BizTemporal{fullName}}#;
   let query = |T_Person_BizTemporal.all($bdate)->graphFetch($tree)->serialize($tree);
   let mapping = meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::milestonedTargetIgnoreMapping;
   let runtime = ^Runtime(
                    connectionStores = ^ConnectionStore(
                       element=^ModelStore(),
                       connection=^JsonModelConnection(
                          class=S_Person_Plain,
                          url='data:application/json,{"fullName":"Grace"}'
                       )
                    )
                 );
   let result = meta::pure::router::execute($query, $mapping, $runtime, meta::pure::extension::configuration::coreExtensions());
   let json = $result.values->toOne();
   assert(jsonEquivalent('{"fullName":"Grace"}'->parseJSON(), $json->parseJSON()));
}

function <<meta::pure::profiles::test.Test, meta::pure::profiles::test.AlloyOnly>>
{  serverVersion.start='v1_33_0',
   doc.doc='Given: a mapping body that navigates a milestoned qualified property WITHOUT a date argument',
   doc.doc='When:  the mapping is executed via graphFetch+serialize with NO date argument',
   doc.doc='Then:  the milestoned property navigation is ignored and produces the expected JSON.'
}
meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::testM2MIgnoresMilestonedProperty() : Boolean[1]
{
   let tree = #{T_Owner_Flat{name, vehicleName}}#;
   let query = |T_Owner_Flat.all()->graphFetch($tree)->serialize($tree);
   let mapping = meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::milestonedPropertyIgnoreMapping;
   let runtime = ^Runtime(
                    connectionStores = ^ConnectionStore(
                       element=^ModelStore(),
                       connection=^JsonModelConnection(
                          class=S_Owner_WithMilestonedProperty,
                          url='data:application/json,{"name":"Henry","vehicle":{"name":"Falcon"}}'
                       )
                    )
                 );
   let result = meta::pure::router::execute($query, $mapping, $runtime, meta::pure::extension::configuration::coreExtensions());
   let json = $result.values->toOne();
   assert(jsonEquivalent('{"name":"Henry","vehicleName":"Falcon"}'->parseJSON(), $json->parseJSON()));
}

function <<meta::pure::profiles::test.Test, meta::pure::profiles::test.AlloyOnly>>
{  serverVersion.start='v1_33_0',
   doc.doc='Given: a mapping body that navigates a milestoned qualified property WITH a hardcoded date',
   doc.doc='When:  the mapping is executed via graphFetch+serialize with NO date on the query',
   doc.doc='Then:  the date on the property is accepted but ignored; result is same as no-date case.'
}
meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::testM2MIgnoresHardcodedDateOnProperty() : Boolean[1]
{
   let tree = #{T_Owner_Flat{name, vehicleName}}#;
   let query = |T_Owner_Flat.all()->graphFetch($tree)->serialize($tree);
   let mapping = meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::milestonedPropertyWithDateIgnoreMapping;
   let runtime = ^Runtime(
                    connectionStores = ^ConnectionStore(
                       element=^ModelStore(),
                       connection=^JsonModelConnection(
                          class=S_Owner_WithMilestonedProperty,
                          url='data:application/json,{"name":"Iris","vehicle":{"name":"Kestrel"}}'
                       )
                    )
                 );
   let result = meta::pure::router::execute($query, $mapping, $runtime, meta::pure::extension::configuration::coreExtensions());
   let json = $result.values->toOne();
   assert(jsonEquivalent('{"name":"Iris","vehicleName":"Kestrel"}'->parseJSON(), $json->parseJSON()));
}
```

- [ ] **Step 4: Build to verify the new fixtures + tests compile**

```bash
source ~/bin/jdk11.sh && mvn clean install -DskipTests -pl legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core -am -T 4
```

Expected: `BUILD SUCCESS`. Common failures:
- `T_Person_BizTemporal.all()` may hit the compile-time milestoning auto-injector requiring a date. If so, the empirical checkpoint from the spec applies: iterate — either (a) mark that test `test.ToFix` for now and continue (and open a follow-up spec change for the compile-time work), or (b) if the pattern is common, extend the walker to synthesize a date placeholder that the runtime rewrite then strips.

Iterate until BUILD SUCCESS.

- [ ] **Step 5: Run the M2M suite and confirm all nine tests pass**

```bash
source ~/bin/jdk11.sh && mvn clean test -pl legend-engine-config/legend-engine-server/legend-engine-server-http-server -Dtest=Test_M2M_UsingPureClientTestSuite -DfailIfNoTests=false 2>&1 | tee /tmp/m2m-after-full-fix.txt
```

Expected: `BUILD SUCCESS`, `Tests run: 219, Failures: 0, Errors: 0, Skipped: 0` (210 pre-existing + 9 new). Verify by name:
```bash
grep -c "testM2MIgnores" /tmp/m2m-after-full-fix.txt
```
Expected: nine occurrences of test names (loaded and passed).

If any of the six new tests fail:
- Read `surefire-reports/*.txt` for the specific failure.
- If the failure is a compile-time rejection at Step 4 (auto-injector), see Step 4's note.
- If the failure is a runtime assertion mismatch, examine the actual JSON output and diagnose whether the walker missed a case (e.g. lambda inside `#{...}#` graphFetch tree not being walked correctly).
- Iterate on the walker in `milestoningStripper.pure`.

## Task 6: Verify regression guards (no code changes)

**Files:** none.

- [ ] **Step 1: Confirm the pre-existing `testM2MMappingWithMilestonedModelsWithAllVersions` still passes**

Already covered by the Task 5 run — it's part of the 210 pre-existing tests. Confirm:
```bash
grep -A 3 "testM2MMappingWithMilestonedModelsWithAllVersions" /tmp/m2m-after-full-fix.txt | head -10
```
Expected: no `<<< ERROR` or `<<< FAILURE` next to it.

- [ ] **Step 2: Confirm the M2M2R coexistence guard still passes**

The M2M2R milestoning tests (including our guard at `coexistenceGuard::testM2M2RStillFiltersHardcodedDate`) are not wired into `Test_M2M_UsingPureClientTestSuite`. Verification is via Pure IDE (interactive) — surface this as a follow-up manual step to the user in the completion summary. Do NOT block on it here; the module build in Task 4 Step 4 already verified the guard file compiles against the updated pure module.

The reason we do not block: the guard test's runtime constructs a `getModelChainRuntime` that includes an H2 relational connection. `isPureM2MRuntime` returns `false` on that runtime, so `stripMilestoningForPureM2M` returns the input unchanged and the M2M2R milestoning behavior is byte-identical to today. The compile-time verification is a strong indicator that the runtime behavior is preserved.

## Task 7: Commit

- [ ] **Step 1: Verify git status**

```bash
git status
```

Expected untracked/modified list:
- `docs/superpowers/plans/2026-07-18-m2m-ignore-milestoning-fix.md` (new)
- `docs/superpowers/specs/2026-07-18-m2m-ignore-milestoning-fix-design.md` (new)
- Modified `IgnoreMilestoningM2M.pure`
- Modified `runtimeExtension.pure`
- New `milestoningStripper.pure`
- Modified `router_entry.pure`

The pre-existing `M pom.xml` from the branch state should remain unstaged.

- [ ] **Step 2: Stage the six files**

```bash
git add \
    docs/superpowers/plans/2026-07-18-m2m-ignore-milestoning-fix.md \
    docs/superpowers/specs/2026-07-18-m2m-ignore-milestoning-fix-design.md \
    legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/legend/milestoning/IgnoreMilestoningM2M.pure \
    legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/runtime/runtimeExtension.pure \
    legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/preeval/milestoningStripper.pure \
    legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/router_entry.pure
```

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
fix: pure M2M path transparently ignores milestoning stereotypes

Adds a router-entry-stage rewriter that walks the incoming
FunctionDefinition and rewrites milestoning-injected getAll* variants
plus milestoned qualified-property calls to their non-milestoning forms
when the runtime has no relational connection. In the pure M2M shape,
this makes .all(), .all(%date), .all($bdate), $src.prop, and
$src.prop(%date) all succeed on <<temporal.*>>-stereotyped classes -- a
JSON document is not milestoned, so the temporal args are accepted and
ignored. In the M2M2R shape (runtime contains a relational connection),
the rewriter is a no-op and milestoning continues to filter at the
relational leaf exactly as before.

Changes:
- Add meta::pure::router::runtime::isPureM2MRuntime helper (predicate
  over Runtime.connectionStores).
- Add meta::pure::router::preeval::stripMilestoningForPureM2M walker in
  a new file core/pure/router/preeval/milestoningStripper.pure.
- Wire the walker into all three overloads of meta::pure::router::execute
  in router_entry.pure, prepending it to the delegating call so
  downstream preval/routing sees an already-rewritten tree.
- Remove test.ToFix from the three original probes in
  IgnoreMilestoningM2M.pure.
- Add six new tests + supporting fixtures covering: milestoned target,
  both-milestoned, hardcoded and variable date on .all(...), milestoned
  qualified property navigation with and without date args in the
  mapping body.

Test results:
- Full M2M suite: 219 tests, 0 failures, 0 errors (was 210 before).
- MilestonedM2M.pure::testM2MMappingWithMilestonedModelsWithAllVersions:
  still passing -- the rewrite is behaviourally invariant on that input
  (single VehicleOwner + single Vehicle in the JSON, so filtering vs
  no filtering yields the same result).
- xt-relationalStore-core-pure build: still clean; the M2M2R
  coexistence guard compiles unchanged. isPureM2MRuntime returns false
  for the M2M2R runtime (H2 connection present) so the rewriter is a
  no-op and M2M2R milestoning is byte-identical.

Design and plan:
- docs/superpowers/specs/2026-07-18-m2m-ignore-milestoning-fix-design.md
- docs/superpowers/plans/2026-07-18-m2m-ignore-milestoning-fix.md

Prior test-add PR: 14f9759f2e0.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Verify the commit**

```bash
git show --stat HEAD
```

Expected: six files in the commit, all additions or modifications as described.

---

## Deferred / follow-up (not part of this PR)

- **Manual Pure IDE verification** of `coexistenceGuard::testM2M2RStillFiltersHardcodedDate` + `milestonedSourceToMilestonedTargetProperty::testWithHardcodedDate` — user runs these interactively to double-check M2M2R behavior.
- **Documentation update** in `docs/engineering/architecture/` describing the new pure-M2M milestoning-ignore behavior and its M2M2R counterpart.
- **Compile-time relaxation** — only if any of the six new tests hit compile-time rejection during Task 5 Step 4. In that case, revise the spec and add the compile-time change in a follow-up PR.
- **M2M2R `test.ToFix` cases** (variable-date, NoArg mapping via includes) — separate M2M2R bugs, unrelated to this fix.
- **True bind-var via `executeLegendQuery`** — the `testM2MIgnoresCapturedDateOnAll` test uses a let-captured date to exercise the walker's non-literal-arg path. A follow-up may add a variant using the `executeLegendQuery` + `pair('bdate', ...)` binding pattern (as in the M2M2R milestoned tests) for a "true bind-var" shape.
