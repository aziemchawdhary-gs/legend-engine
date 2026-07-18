# M2M "Ignore Milestoning" — Initial Test Additions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add four Pure test functions (three pure-M2M "ignore milestoning" probes marked `test.ToFix` + one M2M2R coexistence guard) that document the intended end-state and expose how M2M currently rejects milestoned classes with no date arguments.

**Architecture:** Two new Pure test files. No Java or production Pure code changes.

- `IgnoreMilestoningM2M.pure` — three source classes (one per temporal stereotype), one plain target class, three PureInstance mappings, three tests calling `Target.all() -> graphFetch -> serialize` with no date args. Runtime uses `JsonModelConnection` with plain JSON.
- `m2mIgnoresMilestoningCoexistenceGuard.pure` — one test that mirrors `milestonedSourceToMilestonedTargetProperty::testWithHardcodedDate` under a distinct function name in a distinct sub-package, using the existing milestoned-model fixtures.

**Tech Stack:** Pure language `.pure` files, Legend M2M store contract, `JsonModelConnection`, `graphFetch`/`serialize`, JUnit 4 test runner `Test_M2M_UsingPureClientTestSuite` (test discovery via `TestCollection.collectTests("meta::pure::mapping::modelToModel::test::alloy", ...)`), Maven 3.6.2+, JDK 11.

**Key facts assumed** (verified during design):
- Current production protocol version: `v1_33_0` (from `PureClientVersions.java`). Use this for `serverVersion.start`.
- The M2M test collection root discovered by the Java runner is `meta::pure::mapping::modelToModel::test::alloy`. Any function marked `<<meta::pure::profiles::test.Test>>` under that namespace is collected.
- `<<meta::pure::profiles::test.ToFix>>` marks a test as expected-failing so it does not fail the build; the pattern is used throughout the M2M tests (see `qualifiedProperties.pure`).
- The pure M2M path uses `meta::pure::router::execute(query, mapping, runtime, coreExtensions())` for end-to-end execution (see `MilestonedM2M.pure`).
- The M2M2R milestoning tests (`meta::pure::graphFetch::tests::m2m2r::milestoning::*`) are **not** currently listed in `Test_Relational_UsingPureClientTestSuite.java` — they run only via Pure IDE or in `xt-relationalStore-core-pure` module compile-time test discovery. The guard test we add sits alongside them and inherits the same discoverability.

---

## File Structure

- **Create:** `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/legend/milestoning/IgnoreMilestoningM2M.pure`
- **Create:** `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/modelToModelToRelational/milestoned/m2mIgnoresMilestoningCoexistenceGuard.pure`

No other files touched.

---

## Task 1: Add the pure-M2M "ignore" test file

**Files:**
- Create: `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/legend/milestoning/IgnoreMilestoningM2M.pure`

Full file content is provided below in Step 1. The file is self-contained — no changes to any other Pure file are required. Classes and mappings live under a fresh package `meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore` so they cannot collide with the existing `MilestonedM2M.pure` fixtures.

Each test is marked `<<meta::pure::profiles::test.Test, meta::pure::profiles::test.AlloyOnly, meta::pure::profiles::test.ToFix>>`. Rationale for `test.ToFix`: the tests express the *intended* behavior after the follow-up fix lands. They are expected to fail today. `test.ToFix` keeps the build green while documenting the intent. When the fix PR lands it will remove `meta::pure::profiles::test.ToFix` from the stereotype list.

- [ ] **Step 1: Create `IgnoreMilestoningM2M.pure` with the exact content below**

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

import meta::pure::executionPlan::profiles::*;
import meta::pure::mapping::*;
import meta::json::*;
import meta::external::store::model::*;
import meta::pure::graphFetch::execution::*;
import meta::core::runtime::*;
import meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::model::*;

Class <<temporal.businesstemporal>> meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::model::S_Person_BizTemporal
{
   fullName : String[1];
}

Class <<temporal.processingtemporal>> meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::model::S_Person_ProcTemporal
{
   fullName : String[1];
}

Class <<temporal.bitemporal>> meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::model::S_Person_Bitemporal
{
   fullName : String[1];
}

Class meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::model::T_Person_Plain
{
   fullName : String[1];
}

function <<meta::pure::profiles::test.Test, meta::pure::profiles::test.AlloyOnly, meta::pure::profiles::test.ToFix>>
{  serverVersion.start='v1_33_0',
   doc.doc='Given: an M2M mapping whose source class carries <<temporal.businesstemporal>>',
   doc.doc='When:  the mapping is executed via graphFetch+serialize with NO date argument anywhere',
   doc.doc='Then:  milestoning is ignored and the mapping produces the plain target JSON.'
}
meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::testM2MIgnoresBusinessTemporalSource() : Boolean[1]
{
   let tree = #{T_Person_Plain{fullName}}#;
   let query = |T_Person_Plain.all()->graphFetch($tree)->serialize($tree);
   let mapping = meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::businessTemporalIgnoreMapping;
   let runtime = ^Runtime(
                    connectionStores = ^ConnectionStore(
                       element=^ModelStore(),
                       connection=^JsonModelConnection(
                          class=S_Person_BizTemporal,
                          url='data:application/json,{"fullName":"Alice"}'
                       )
                    )
                 );
   let result = meta::pure::router::execute($query, $mapping, $runtime, meta::pure::extension::configuration::coreExtensions());
   let json = $result.values->toOne();
   assert(jsonEquivalent('{"fullName":"Alice"}'->parseJSON(), $json->parseJSON()));
}

function <<meta::pure::profiles::test.Test, meta::pure::profiles::test.AlloyOnly, meta::pure::profiles::test.ToFix>>
{  serverVersion.start='v1_33_0',
   doc.doc='Given: an M2M mapping whose source class carries <<temporal.processingtemporal>>',
   doc.doc='When:  the mapping is executed via graphFetch+serialize with NO date argument anywhere',
   doc.doc='Then:  milestoning is ignored and the mapping produces the plain target JSON.'
}
meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::testM2MIgnoresProcessingTemporalSource() : Boolean[1]
{
   let tree = #{T_Person_Plain{fullName}}#;
   let query = |T_Person_Plain.all()->graphFetch($tree)->serialize($tree);
   let mapping = meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::processingTemporalIgnoreMapping;
   let runtime = ^Runtime(
                    connectionStores = ^ConnectionStore(
                       element=^ModelStore(),
                       connection=^JsonModelConnection(
                          class=S_Person_ProcTemporal,
                          url='data:application/json,{"fullName":"Bob"}'
                       )
                    )
                 );
   let result = meta::pure::router::execute($query, $mapping, $runtime, meta::pure::extension::configuration::coreExtensions());
   let json = $result.values->toOne();
   assert(jsonEquivalent('{"fullName":"Bob"}'->parseJSON(), $json->parseJSON()));
}

function <<meta::pure::profiles::test.Test, meta::pure::profiles::test.AlloyOnly, meta::pure::profiles::test.ToFix>>
{  serverVersion.start='v1_33_0',
   doc.doc='Given: an M2M mapping whose source class carries <<temporal.bitemporal>>',
   doc.doc='When:  the mapping is executed via graphFetch+serialize with NO date argument anywhere',
   doc.doc='Then:  milestoning is ignored and the mapping produces the plain target JSON.'
}
meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::testM2MIgnoresBitemporalSource() : Boolean[1]
{
   let tree = #{T_Person_Plain{fullName}}#;
   let query = |T_Person_Plain.all()->graphFetch($tree)->serialize($tree);
   let mapping = meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::bitemporalIgnoreMapping;
   let runtime = ^Runtime(
                    connectionStores = ^ConnectionStore(
                       element=^ModelStore(),
                       connection=^JsonModelConnection(
                          class=S_Person_Bitemporal,
                          url='data:application/json,{"fullName":"Carol"}'
                       )
                    )
                 );
   let result = meta::pure::router::execute($query, $mapping, $runtime, meta::pure::extension::configuration::coreExtensions());
   let json = $result.values->toOne();
   assert(jsonEquivalent('{"fullName":"Carol"}'->parseJSON(), $json->parseJSON()));
}

###Mapping
import meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::model::*;

Mapping meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::businessTemporalIgnoreMapping
(
   T_Person_Plain : Pure
   {
      ~src S_Person_BizTemporal
      fullName : $src.fullName
   }
)

Mapping meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::processingTemporalIgnoreMapping
(
   T_Person_Plain : Pure
   {
      ~src S_Person_ProcTemporal
      fullName : $src.fullName
   }
)

Mapping meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::bitemporalIgnoreMapping
(
   T_Person_Plain : Pure
   {
      ~src S_Person_Bitemporal
      fullName : $src.fullName
   }
)
```

- [ ] **Step 2: Build the M2M-core-pure module to verify the new file compiles**

Run:
```bash
mvn clean install -DskipTests -pl legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core -am -T 4
```

Expected: `BUILD SUCCESS`. The Pure compilation of this module also runs Pure IDE-style compilation of `.pure` sources. If our file has syntax errors or unresolved references, the module build will fail with a Pure compilation error pointing to `IgnoreMilestoningM2M.pure`.

If it fails: read the compile error, fix the offending line in `IgnoreMilestoningM2M.pure`, and re-run this step. Common failure modes to watch for:
- Wrong import path for `T_Person_Plain` / source classes (should be `meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore::model::*`).
- The `###Mapping` block needs its own `import` for the model classes because grammar-block scope resets.
- Missing `newLine()` / trailing content after last mapping definition.

Do **not** proceed to Task 2 until this step succeeds.

## Task 2: Run the pure-M2M tests and capture the failure output

The tests are marked `test.ToFix` so the suite will not fail even if the assertions fail. The point of this task is to capture *how* they currently fail (compile-time reject? execution-time exception? silent empty result?) so the follow-up fix PR has a concrete target.

- [ ] **Step 1: Run the full M2M test suite**

Run:
```bash
mvn test -pl legend-engine-config/legend-engine-server/legend-engine-server-http-server \
    -Dtest=Test_M2M_UsingPureClientTestSuite \
    -am 2>&1 | tee /tmp/m2m-ignore-tests-output.txt
```

The `-am` flag ensures all upstream modules build first (needed because our new `.pure` file is in an upstream module and must be compiled before this test runs).

Expected: the suite runs; total test count includes our three new tests. Look near the end of the output for lines mentioning `testM2MIgnores`.

- [ ] **Step 2: Extract the outcome for our three tests**

Run:
```bash
grep -A 15 "testM2MIgnores" /tmp/m2m-ignore-tests-output.txt > /tmp/m2m-ignore-tests-summary.txt
cat /tmp/m2m-ignore-tests-summary.txt
```

Expected content: for each of `testM2MIgnoresBusinessTemporalSource`, `testM2MIgnoresProcessingTemporalSource`, `testM2MIgnoresBitemporalSource`, either:
- The test PASSED (unexpected — see note below).
- The test was skipped/expected-fail because of `test.ToFix` and a printed error message. Legend's ToFix runner typically prints the underlying failure message even for skipped tests.

Save the extracted summary — it goes into the commit message and PR description so the failure signature is preserved.

**If any test unexpectedly passes:** stop and investigate. It may mean M2M already handles this case for `source-milestoned, plain target` (only), which would narrow the follow-up fix scope significantly. Note the passing test in the commit message and continue.

- [ ] **Step 3: Verify the existing `testM2MMappingWithMilestonedModelsWithAllVersions` in `MilestonedM2M.pure` still passes**

Run:
```bash
grep -A 5 "testM2MMappingWithMilestonedModelsWithAllVersions" /tmp/m2m-ignore-tests-output.txt
```

Expected: the existing test is reported as `OK` / `PASSED`. This confirms our additions did not accidentally break the existing milestoning-with-date positive path.

## Task 3: Add the M2M2R coexistence guard test file

**Files:**
- Create: `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/modelToModelToRelational/milestoned/m2mIgnoresMilestoningCoexistenceGuard.pure`

This test lives beside the existing M2M2R milestoning tests, uses the same fixtures (`TargetProductMilestoned`, `milestoningMapSmall`, `TargetToModelMappingViaAllVersions`, `getModelChainRuntime`) already defined in the sibling files, and asserts the same behavior as `testWithHardcodedDate`. It exists to make the coexistence intent (M2M ignore vs. M2M2R preserve) explicit in this PR.

- [ ] **Step 1: Create `m2mIgnoresMilestoningCoexistenceGuard.pure` with the exact content below**

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

###Pure
import meta::pure::mapping::*;
import meta::pure::milestoning::*;
import meta::pure::graphFetch::tests::m2m2r::milestoning::*;
import meta::pure::graphFetch::execution::*;
import meta::pure::alloy::connections::alloy::specification::*;
import meta::pure::alloy::connections::alloy::authentication::*;
import meta::relational::runtime::*;
import meta::core::runtime::*;

// Coexistence guard for the "M2M ignores milestoning" change.
// The pure-M2M side of that change must NOT weaken M2M2R's milestoning behavior.
// This test mirrors the passing testWithHardcodedDate in the sibling file
// milestonedSourceToMilestonedTargetProperty.pure and asserts the M2M2R chain
// still applies the milestoning WHERE-clause at the relational leaf when a
// hardcoded businessDate is supplied.
function <<test.Test, test.AlloyOnly>>
   meta::pure::graphFetch::tests::m2m2r::milestoning::coexistenceGuard::testM2M2RStillFiltersHardcodedDate():Boolean[1]
{
  let mapping = meta::relational::tests::m2m2r::milestoning::milestonedSourceToMilestonedTargetProperty::TargetToModelMappingViaAllVersions;
  let runtime = getModelChainRuntime($mapping);

  let query = {|meta::relational::tests::milestoning::TargetProductMilestoned.all(%2023-10-15T00:00:00)->graphFetch(
      #{
        meta::relational::tests::milestoning::TargetProductMilestoned{
          id,
          name,
          synonymsMilestoned(%2020-10-15T00:00:00)
          {synonym}
        }
      }#
    )->serialize(
      #{
        meta::relational::tests::milestoning::TargetProductMilestoned{
          id,
          name,
          synonymsMilestoned(%2020-10-15T00:00:00)
          {synonym}
        }
      }#
    )
    ->meta::pure::mapping::from($mapping,$runtime)
    };
  let result = meta::legend::executeLegendQuery($query, [], ^meta::pure::runtime::ExecutionContext(), meta::relational::extension::relationalExtensions());
  assertJsonStringsEqual('{"builder":{"_type":"json"},"values":['+
                                            '{"id":2,"name":"ProductName2","synonymsMilestoned(2020-10-15T00:00:00+0000)":[{"synonym":"GS-Mod-S1"},{"synonym":"GS-Mod-S3"}]},'+
                                            '{"id":3,"name":"ProductName3","synonymsMilestoned(2020-10-15T00:00:00+0000)":[{"synonym":"GS-Mod-S3"}]'+
                                          '}]}', $result);
}
```

Rationale for the exact assertion string: it is a byte-for-byte copy of the assertion in `testWithHardcodedDate` inside `milestonedSourceToMilestonedTargetProperty.pure`. Using the exact same expected JSON keeps this test's behavior locked to the reference test.

- [ ] **Step 2: Build the M2M2R core-pure module to verify the new file compiles**

Run:
```bash
mvn clean install -DskipTests \
    -pl legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure \
    -am -T 4
```

Expected: `BUILD SUCCESS`. If Pure compilation fails, the error will name our new file. Common failure modes:
- Import path for `TargetProductMilestoned` missing (should not be needed since the FQN is used inline, but confirm).
- Package `meta::pure::graphFetch::tests::m2m2r::milestoning::coexistenceGuard` is fresh — that is fine, Pure allows implicit sub-package creation.

## Task 4: Verify the coexistence guard test executes and passes

The M2M2R milestoning tests are **not** wired into `Test_Relational_UsingPureClientTestSuite.java` at time of writing (verified by grep). This means the standard Maven `mvn test` will not run this test. We verify it two ways:

1. Static: the module build (Task 3, Step 2) already compiled the Pure source, so the function definition and reference paths are known good.
2. Dynamic: run it via the Pure IDE.

- [ ] **Step 1: Start the Pure IDE**

From the repo root, in a separate terminal:
```bash
mvn dependency:build-classpath -DincludeScope=compile -Dmdep.outputFile=/tmp/pure-ide-cp.txt \
    -pl legend-engine-pure/legend-engine-pure-ide/legend-engine-pure-ide-light-http-server -am -q
```

Then launch (per CLAUDE.md conventions):
```bash
java -cp "$(cat /tmp/pure-ide-cp.txt):legend-engine-pure/legend-engine-pure-ide/legend-engine-pure-ide-light-http-server/target/classes" \
    org.finos.legend.engine.ide.PureIDELight \
    server legend-engine-pure/legend-engine-pure-ide/legend-engine-pure-ide-light-http-server/src/main/resources/ideLightConfig.json
```

Open <http://127.0.0.1:9200/ide>.

If the maintainer already has a preferred way to launch the Pure IDE (e.g. IntelliJ run configuration), use that instead — the goal is only to get a running IDE for the next step.

- [ ] **Step 2: Run the guard test from the IDE**

In the Pure IDE UI:
1. Navigate to `meta::pure::graphFetch::tests::m2m2r::milestoning::coexistenceGuard::testM2M2RStillFiltersHardcodedDate`.
2. Right-click → Run test (or use the play icon in the IDE toolbar for `<<test.Test>>` functions).

Expected: the test passes. If it fails, the failure is a red flag — it would mean either (a) our test copy diverged from the reference `testWithHardcodedDate` or (b) the H2 fixtures behave differently in the IDE than in whatever runner the reference test uses. Diff the two functions line by line and reconcile.

- [ ] **Step 3: Cross-check by also running the reference test**

In the same IDE session, run `meta::pure::graphFetch::tests::m2m2r::milestoning::milestonedSourceToMilestonedTargetProperty::testWithHardcodedDate`.

Expected: passes.

If the reference passes and ours fails, the divergence is on our side — fix and re-run. If both fail, the H2 fixture is broken in this environment (not our problem for this PR; note it and continue).

Shut down the Pure IDE when done.

## Task 5: Commit the changes

- [ ] **Step 1: Verify what git will see**

Run:
```bash
git status
```

Expected output:
```
Untracked files:
  docs/superpowers/plans/2026-07-18-m2m-ignore-milestoning-initial-tests.md
  docs/superpowers/specs/2026-07-18-m2m-ignore-milestoning-initial-tests-design.md
  legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/legend/milestoning/IgnoreMilestoningM2M.pure
  legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/modelToModelToRelational/milestoned/m2mIgnoresMilestoningCoexistenceGuard.pure
```

There should also be a `M pom.xml` from the pre-existing branch state — do **not** stage this unless the user explicitly asks. The M2M-ignore work does not require pom changes.

- [ ] **Step 2: Stage only the four new files**

Run:
```bash
git add \
    docs/superpowers/plans/2026-07-18-m2m-ignore-milestoning-initial-tests.md \
    docs/superpowers/specs/2026-07-18-m2m-ignore-milestoning-initial-tests-design.md \
    legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/legend/milestoning/IgnoreMilestoningM2M.pure \
    legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/modelToModelToRelational/milestoned/m2mIgnoresMilestoningCoexistenceGuard.pure
```

- [ ] **Step 3: Commit**

Run:
```bash
git commit -m "$(cat <<'EOF'
test: add M2M ignore-milestoning probes and M2M2R coexistence guard

Adds three ToFix tests under
meta::pure::mapping::modelToModel::test::alloy::milestoning::ignore that
assert the intended end-state -- the pure M2M path should transparently
ignore the temporal.businesstemporal / temporal.processingtemporal /
temporal.bitemporal stereotypes on classes it operates over. Each test
executes graphFetch+serialize with no date argument anywhere in the
query, mapping, or JSON payload. The tests are expected to fail today;
the follow-up fix PR will remove the test.ToFix marker.

Also adds one M2M2R coexistence guard test at
meta::pure::graphFetch::tests::m2m2r::milestoning::coexistenceGuard
mirroring the passing testWithHardcodedDate reference. Its purpose is
to make the "M2M ignores milestoning but M2M2R preserves it" intent
explicit and to guard the M2M2R hardcoded-date path when the follow-up
fix lands.

Design and plan:
- docs/superpowers/specs/2026-07-18-m2m-ignore-milestoning-initial-tests-design.md
- docs/superpowers/plans/2026-07-18-m2m-ignore-milestoning-initial-tests.md
EOF
)"
```

- [ ] **Step 4: Verify the commit succeeded and shows the expected files**

Run:
```bash
git show --stat HEAD
```

Expected: four files listed; commit message matches Step 3. If any file is missing, `git status` will tell you; re-stage and amend if needed (per repo conventions, prefer a new commit if the missing file is a significant addition).

---

## Out of scope for this plan (deferred to follow-up PRs)

- **Fix PR:** implement the "M2M ignores milestoning" behavior. Design candidates already listed in the spec (`docs/superpowers/specs/2026-07-18-m2m-ignore-milestoning-initial-tests-design.md`, section "Deferred to follow-up PRs").
- **Coverage expansion:** target-milestoned and both-milestoned variants; simple-execute (non-graphFetch) variant; variable-date variant.
- **Wiring:** if we discover the M2M2R milestoning tests should be added to `Test_Relational_UsingPureClientTestSuite.java` so they run in CI, that is a separate change with a separate rationale (it would surface the existing ToFix cases in CI, not just our new guard).
