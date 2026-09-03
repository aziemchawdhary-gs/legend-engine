# M2M SQL Execution (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Widen the phase-1 M2M SQL collapse from "simple property transforms only" to the main real-world mapping shapes — mapping filters, root `filter()`, enumerations, and explode mappings — plus structured fallback telemetry and hardening of the phase-1 guards, flipping all three currently-fallback parity cases to collapsed.

**Architecture:** Everything builds on the phase-1 machinery, unchanged in shape: `Extension.postProcessExecutionPlan` → guard cascade in `collapse.pure` → whitelist in `translatability.pure` → SQL assembly in `jsonProjection.pure` over the synthetic source store from `sourceSynthesis.pure` → `SQLGraphSerializeExecutionNode`. Each phase-2 feature is (a) a whitelist widening, (b) a projection extension, and (c) a parity-case flip: the feature's parity case already exists in `TestM2MSqlParity` on the fallback manifest, so removing its `expected-fallbacks.json` entry makes the existing test demand collapse + byte parity — that IS the red test for each feature task. Every guard remains a fallback, never a failure.

**Tech Stack:** Java 11, Maven, Pure (compiled mode), DuckDB 1.3.0 (embedded, `org.duckdb:duckdb_jdbc`), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-17-m2m-sql-execution-design.md` (Sections 5–9) plus the shipped-state description in `docs/engineering/architecture/m2m-sql-execution.md` (Section 9 is the authoritative deferred list). **Deviation from the spec's §9 phasing table:** the spec lists "union" in phase 2; this plan defers union to phase 3 alongside merge and to-many complex properties, because union plans take a different node shape (`planStoreUnionGraphFetchExecution`, potentially multiple source connections) that needs its own design pass. This plan is authoritative on scope.

**Mechanics verified up front against DuckDB 1.3.0 (the exact jar the repo pins, `duckdb.version` in root pom):**
- `unnest(cast(json_extract(data, '$.tags') as VARCHAR[]))` in a SELECT list yields one row per element, zero rows for `[]` **and** for an absent path, and repeats sibling scalar columns per row — exactly M2M explode semantics.
- `CASE WHEN col = 'ROUGE' THEN 'RED' ... ELSE error('...') END` is lazy per row: matched rows pass, an unmatched row raises `Invalid Input Error` — mirroring the Java path's hard failure on an unmatched enum source value.
- `to_json(...)`/`json_extract` behave as phase 1 established; no new engine capability is needed for filters.

## Global Constraints

- **JDK 11 required for every mvn command:** `export JAVA_HOME=/home/aziem/jdk11/jdk-11.0.29+7` first, always. In this worktree also pass `-Dmaven.gitcommitid.skip=true`.
- **Always pass `clean`** to mvn for modules containing Pure plugins (stale target dirs cause "duplicate artifact present" failures).
- Build one module: `mvn clean install -DskipTests -pl <module-path> -am` from repo root. Run tests: `mvn clean test -pl <module-path> -Dtest=ClassName`. Cross-module test runs resolve from the local `.m2`: after changing an upstream module, `mvn clean install` it before testing a downstream one.
- Every new file (including `.pure`) needs the Apache 2.0 copyright header (Checkstyle-enforced; `mvn checkstyle:check -Dcheckstyle.config.location=checkstyle.xml`).
- Indentation: Java 4 spaces, Pure 2 spaces. Braces always, opening brace on its own line. No tabs.
- SLF4J only, never `System.out.println`. New log event types: module-local enum implementing `ILoggingEventType` (the shared `LoggingEventType` enum is `@Deprecated`). Never log payloads or URLs — scheme only (phase-1 rule, review-enforced).
- JSON comparison in tests: `JsonUnit.assertJsonEquals`, never `String.equals`.
- **Whitelist discipline (spec §5, binding):** a construct is only ever treated as translatable when positively known to be so; anything else must produce a *fallback with a reason string*, never an assert/fail on the collapse path. Extend `sqlTranslatableFunctionWhitelist` only when a parity case exercises the addition.
- **Parity discipline:** every whitelist widening lands with a `TestM2MSqlParity` case proving byte-identical output against the `JsonModelConnection` Java path, and plan-shape assertions (collapsed cases must contain `sqlGraphSerialize` and no `implementation`; fallback cases must keep `inMemoryRootGraphFetch` + `storeStreamReading`). Never weaken `assertParity`'s comparison to make a case pass.
- **Commit messages carry NO `Co-Authored-By: Claude ...` or `Claude-Session:` trailers** — the FINOS CLA check fails on them (user's global CLAUDE.md; overrides any harness default). Commit at the end of every task.
- Pure protocol changes (none are planned here) would be additive to BOTH `vX_X_X` and `v1_33_0`. No Java or Pure protocol change is expected in this phase; if a task believes it needs one, stop and escalate.

**Module path abbreviations** (all relative to repo root):

| Alias | Path |
|---|---|
| `CORE-PURE` | `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core` |
| `INMEM-EXEC` | `legend-engine-core/legend-engine-core-base/legend-engine-core-executionPlan-execution/legend-engine-executionPlan-execution-store-inMemory` |
| `REL-CORE-PURE` | `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure` |
| `DUCKDB-PURE` | `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-dbExtension/legend-engine-xt-relationalStore-duckdb/legend-engine-xt-relationalStore-duckdb-pure` |
| `DUCKDB-PCT` | `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-dbExtension/legend-engine-xt-relationalStore-duckdb/legend-engine-xt-relationalStore-duckdb-PCT` |

**Phase-1 files this plan modifies constantly** (read them before any task):
- `REL-CORE-PURE/src/main/resources/core_relational/relational/modelToModelToRelational/m2mSql/translatability.pure` (the whitelist)
- `.../m2mSql/collapse.pure` (guard cascade)
- `.../m2mSql/jsonProjection.pure` (SQL assembly)
- `.../m2mSql/sourceSynthesis.pure` (synthetic store)
- `.../m2mSql/tests/testTranslatability.pure` (Pure unit tests; DuckDB-side Pure tests live in `DUCKDB-PURE/src/main/resources/core_relational_duckdb/relational/m2mSql/tests/`)
- `DUCKDB-PCT/src/test/java/.../m2msql/TestM2MSqlParity.java` + `DUCKDB-PCT/src/test/resources/m2msql/expected-fallbacks.json` (parity harness)

---

### Task 1: Execution-time fallback telemetry

The design's end-state ("telemetry-driven coverage growth until Java fallback rates justify deprecating the Java M2M path", spec §9 phase 4+) needs one structured signal: *how often does a `SqlModelConnection` query actually run on the Java path?* Phase 1 only prints the rejection reason under `debug.debug`. The reliable, zero-protocol-change seam is execution time: whenever the Java in-memory executor runs a `StoreStreamReadingExecutionNode` whose connection is a `SqlModelConnection`, that is by definition a fallback execution — log it as a structured event. (Plan-time reason strings stay debug-only this phase: carrying them into the plan needs a protocol addition; revisit when fallback rates matter.)

**Files:**
- Create: `INMEM-EXEC/src/main/java/org/finos/legend/engine/plan/execution/stores/inMemory/plugin/M2MSqlFallbackLoggingEventType.java`
- Modify: `INMEM-EXEC/src/main/java/org/finos/legend/engine/plan/execution/stores/inMemory/plugin/InMemoryExecutionNodeExecutor.java:110-113` (the `visit(StoreStreamReadingExecutionNode)` method)
- Test: `INMEM-EXEC/src/test/java/org/finos/legend/engine/plan/execution/stores/inMemory/TestM2MSqlFallbackLogging.java`

**Interfaces:**
- Consumes: `StoreStreamReadingExecutionNode.connection` (protocol `Connection`), `org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.modelToModel.connection.SqlModelConnection` (same protocol module, already on the classpath), `LogInfo` + `ILoggingEventType` from `legend-shared` (mirror the phase-1 pattern in `M2MSqlGraphSerializeHelper.java:71`).
- Produces: enum `M2MSqlFallbackLoggingEventType.M2M_SQL_JAVA_FALLBACK_EXECUTED`; one INFO `LogInfo` per fallback execution carrying the source class path (`SqlModelConnection._class`) and the URL **scheme only** (substring before the first `:`, or `"none"`).

- [ ] **Step 1: Write the failing test.** Attach a logback `ListAppender` to `LoggerFactory.getLogger(InMemoryExecutionNodeExecutor.class)`, build a minimal `StoreStreamReadingExecutionNode` carrying a `SqlModelConnection` (set `_class = "test::S"`, `url = "data:application/json,{}"`), invoke the logging helper (Step 3 extracts one so the test doesn't need a full executor context), and assert exactly one event whose message contains `M2M_SQL_JAVA_FALLBACK_EXECUTED` and `"data"` but NOT the full url payload. Add a second case with a `JsonModelConnection` asserting zero events.
- [ ] **Step 2: Run it to fail** (`mvn clean test -pl <INMEM-EXEC> -Dtest=TestM2MSqlFallbackLogging`, expect compile failure — enum missing).
- [ ] **Step 3: Implement.** New enum (Apache header, mirrors `M2MSqlLoggingEventType`). In `InMemoryExecutionNodeExecutor.visit(StoreStreamReadingExecutionNode node)`, first statement: extract to a small static package-private method so the test can call it:

```java
static void logM2MSqlFallbackIfApplicable(StoreStreamReadingExecutionNode node, Identity identity)
{
    if (node.connection instanceof SqlModelConnection)
    {
        SqlModelConnection connection = (SqlModelConnection) node.connection;
        String url = connection.url;
        String scheme = (url != null && url.indexOf(':') > 0) ? url.substring(0, url.indexOf(':')) : "none";
        LOGGER.info("{}", new LogInfo(M2MSqlFallbackLoggingEventType.M2M_SQL_JAVA_FALLBACK_EXECUTED, identity.getName(), connection._class + " via " + scheme));
    }
}
```

  (Match the actual `LogInfo` constructor overloads used at `M2MSqlGraphSerializeHelper.java:71`; adjust field access for `_class` to whatever the POJO names it.)
- [ ] **Step 4: Run to green**, then run the module's full suite once.
- [ ] **Step 5: Sanity-check downstream:** `mvn clean test -pl <DUCKDB-PCT> -Dtest=TestM2MSqlParity` must still pass 8/8 (the three fallback cases will now emit the event; assert nothing about it there).
- [ ] **Step 6: Commit** (`git add` the three files; message `M2M SQL: structured fallback-executed telemetry event`).

---

### Task 2: Guard and projection hardening (phase-1 review follow-ups)

Four ledgered phase-1 findings, all small, all correctness-of-the-guard-rails:

**Files:**
- Modify: `REL-CORE-PURE/.../m2mSql/jsonProjection.pure:123-126` (`m2mSqlQuoteIdentifier`)
- Modify: `REL-CORE-PURE/.../m2mSql/translatability.pure:96-106` (auto-mapped complex recursion), `:48-57` (no change yet — context for Task 3)
- Modify: `REL-CORE-PURE/.../m2mSql/collapse.pure:124` (target constraint guard), `:220-227` (source constraint guard), `:229-233` (`treeClasses`), `:150-151` (`enableConstraints` consult)
- Modify: `CORE-PURE/src/main/resources/core/pure/executionPlan/executionPlan_generation.pure` (no-hook fast path, Step 5)
- Test: extend `REL-CORE-PURE/.../m2mSql/tests/testTranslatability.pure`, `DUCKDB-PURE/.../m2mSql/tests/testCollapse.pure`, and `CORE-PURE`'s `testPlanPostProcess.pure`

**Interfaces:**
- Consumes: `meta::pure::metamodel::type::Class.generalizations` / `->getAllTypeGeneralisations()` for inherited constraints; `StoreStreamReadingExecutionNode.enableConstraints` (`CORE-PURE/src/main/resources/core/pure/mapping/modelToModel.pure:232`).
- Produces: unchanged public signatures; new private helper `meta::relational::m2mSql::classAndSupertypeConstraintsExist(classes:Class<Any>[*]):Boolean[1]`.

- [ ] **Step 1: Quote escaping.** Failing Pure test in `testJsonProjection.pure`: a target property alias containing `"` must render doubled. Fix:

```pure
function <<access.private>> meta::relational::m2mSql::m2mSqlQuoteIdentifier(name:String[1]):String[1]
{
  '"' + $name->replace('"', '""') + '"';
}
```

- [ ] **Step 2: Auto-mapped complex properties must recurse.** Today `untranslatablePropertyReasons` (`translatability.pure:96-106`), on the no-property-mapping branch, validates the property itself but never its subtree — an auto-mapped complex property with an untranslatable child slips past the gate and hits an assert in `flattenTargetTree`/`nestedSetImplementation` instead of falling back. Failing test first: a mapping where a complex property has no `PurePropertyMapping` but auto-maps by name, whose child property has an untranslatable transform in the nested class's mapping — `canPlanM2MSql` must return `translatable=false` with a reason, not crash. Fix inside the `$propertyMappings->isEmpty()` branch: when `isPropertyAutoMapped` and the enum check passes and `$propertyTree.subTrees->isNotEmpty()`, resolve the nested set via `$mapping->rootClassMappingByClass(...)` (same logic as `nestedSetImplementation`, `jsonProjection.pure:245-252`) and recurse `untranslatableReasons`; if unresolvable, return a reason string. Also require plain-navigation semantics symmetric to the mapped-complex path (an auto-mapped access IS a plain navigation, so only the resolution/recursion is new).
- [ ] **Step 3: Inherited constraints.** `treeClasses`-based guards (`collapse.pure:124`, `:223`) read `$c.constraints` — constraints declared on a superclass are invisible, so a constrained-by-inheritance class silently loses its check. Failing test: source class extending a constrained superclass must produce the `'constraints on the source classes are not yet translated'` fallback. Fix: check `$c->getAllTypeGeneralisations()->filter(t | $t->instanceOf(Class))->cast(@Class<Any>).constraints` (or the repo's equivalent helper — verify `meta::pure::functions::meta::getAllTypeGeneralisations` resolves in this module; if not, walk `.generalizations.general.rawType` transitively) in both guards via one shared helper.
- [ ] **Step 4: Consult `enableConstraints`.** When the plan was generated with constraints disabled (`$reader.enableConstraints == false` on the `StoreStreamReadingExecutionNode`), the Java path checks nothing, so falling back "because constraints exist" is pure lost coverage. Thread the reader's `enableConstraints` from `m2mSqlLocalNodeRejection` (`collapse.pure:147`) into both constraint guards; skip them when false. NOTE the asymmetry deliberately kept: target-class constraint checking on graphFetch is also gated by `enableConstraints`, so both guards key off the same flag. Add a `testCollapse.pure` case with `enableConstraints=false` and a constrained class that must now collapse.
- [ ] **Step 5: No-hook fast path.** The phase-1 fold in `CORE-PURE/src/main/resources/core/pure/executionPlan/executionPlan_generation.pure` (the `postProcessExecutionPlan` fold plus the post-fold recompute of `authDependent`/kerberos around lines 210-230) runs its plan copy/recompute even when NO extension registers a hook — every non-M2M-SQL deployment pays it. Guard the whole block with `if($extensions.postProcessExecutionPlan->isEmpty(), | $plan, | <existing fold + recompute>)`. The existing `testPlanPostProcess.pure` tests must still pass; add one asserting a hookless plan comes back identical (`==` on the printed plan is sufficient).
- [ ] **Step 6: Run** the Pure test modules (`mvn clean test` on `REL-CORE-PURE`'s and `DUCKDB-PURE`'s test runner modules — same runners phase 1 used: `Test_Pure_Relational`, `Test_Pure_Relational_DuckDB`, plus `Test_Pure_Core` for Step 5) and `TestM2MSqlParity` (8/8).
- [ ] **Step 7: Commit.**

---

### Task 3: Mapping `~filter` push-down + comparison/boolean whitelist

Flips the `mappingFilter` parity case. The mapping's `~filter` lambda is over `$src` (the source class), so it needs no target-to-source rewriting — substitute its parameter and stick a `->filter(...)` between `getAll` and `project` in the inner query; `pureToSQLQuery` renders the WHERE against the semi-structured extracts.

**Files:**
- Modify: `REL-CORE-PURE/.../m2mSql/translatability.pure:48-57` (`rootLevelReasons`), `:59-69` (`untranslatableReasons` — root/nested distinction), `:230-243` (whitelist)
- Modify: `REL-CORE-PURE/.../m2mSql/jsonProjection.pure:74-106` (`buildM2MSqlQuery`), `:298-323` (`buildProjectExpression`)
- Modify: `DUCKDB-PCT/src/test/resources/m2msql/expected-fallbacks.json` (remove `mappingFilter`)
- Test: `TestM2MSqlParity.java` (new `comparisonFilter` case), `testTranslatability.pure`, `testJsonProjection.pure`

**Interfaces:**
- Consumes: `PureInstanceSetImplementation.filter : LambdaFunction<Any>[0..1]`; existing `substituteSourceVariable` (`jsonProjection.pure:258-263`), `explainUntranslatableExpression` (`translatability.pure:172`).
- Produces: `untranslatableReasons(setImpl, tree, mapping, isRoot:Boolean[1])` (root call passes `true`, the nested-set recursion at `translatability.pure:136` passes `false`). `isRoot` gates root-only features: this task's filter acceptance (a nested set's `~filter` semantics on the Java graphFetch path are unverified, so nested filters keep falling back) and later Task 7's explode acceptance; `buildProjectExpression(srcClass:Class<Any>[1], columns:M2MSqlColumn[*], filters:ValueSpecification[*]):FunctionExpression[1]` — `filters` are boolean expressions over the root `$src` variable, ANDed by wrapping `getAll` in one `->filter()` per entry. Task 4 reuses `filters` for the rewritten root filter.

- [ ] **Step 1: RED.** Remove the `mappingFilter` entry from `expected-fallbacks.json`; run `TestM2MSqlParity#mappingFilter` — must fail on plan shape ("was expected to collapse to SQL").
- [ ] **Step 2: Widen the whitelist** (`sqlTranslatableFunctionWhitelist`) with the boolean/comparison set, each to be exercised by the parity cases below:

```pure
    and_Boolean_1__Boolean_1__Boolean_1_,
    or_Boolean_1__Boolean_1__Boolean_1_,
    not_Boolean_1__Boolean_1_,
    equal_Any_MANY__Any_MANY__Boolean_1_,
    lessThan_Number_1__Number_1__Boolean_1_,
    lessThanEqual_Number_1__Number_1__Boolean_1_,
    greaterThan_Number_1__Number_1__Boolean_1_,
    greaterThanEqual_Number_1__Number_1__Boolean_1_
```

  (Verify each FQN compiles; drop any that has no `pureToSQLQuery` route and note it in the report rather than forcing it.)
- [ ] **Step 3: Gate.** In `rootLevelReasons`, replace the unconditional filter rejection: when `isRoot` and `$setImpl.filter->isNotEmpty()`, run `explainUntranslatableExpression($setImpl.filter->toOne().expressionSequence->toOne())` — non-empty result becomes `'unsupported mapping filter: ' + reason`; when NOT `allowFilter` and a filter exists, keep the old `'mapping filter not yet translated'` (nested sets). Note the filter lambda's parameter may not be literally named `src`: `explainUntranslatableExpression` only admits `$src`, so first substitute the parameter with a canonical `src` `VariableExpression` (reuse `substituteSourceVariable`) and gate the *substituted* expression. Pure unit tests: translatable boolean filter accepted at root, rejected on nested set, untranslatable function in filter produces reason.
- [ ] **Step 4: Projection.** In `buildM2MSqlQuery`, build the filter expression once and pass it down:

```pure
  let mappingFilter = if($setImpl.filter->isEmpty(), | [],
                         | substituteSourceVariable($setImpl.filter->toOne(), $srcVar));
  ...
  expressionSequence = buildProjectExpression($srcClass, $columns, $mappingFilter)
```

  In `buildProjectExpression`, wrap `getAll` per filter entry:

```pure
  let filtered = $filters->fold({f, acc |
    ^SimpleFunctionExpression
    (
      func             = filter_T_MANY__Function_1__T_MANY_,
      functionName     = 'filter',
      importGroup      = system::imports::coreImport,
      genericType      = ^GenericType(rawType = $srcClass),
      multiplicity     = ZeroMany,
      parametersValues = [$acc, ^InstanceValue(genericType = ^GenericType(rawType = FunctionDefinition, typeArguments = ^GenericType(rawType = Any)), multiplicity = PureOne, values = buildFilterLambda($srcClass, $f))]
    )->evaluateAndDeactivate()
  }, $getAll->cast(@ValueSpecification));
```

  where `buildFilterLambda` is `buildColumnLambda` (`jsonProjection.pure:275`) with `returnType = Boolean`/`returnMultiplicity = PureOne` — factor a shared private helper rather than duplicating the classifier construction.
- [ ] **Step 5: GREEN.** `TestM2MSqlParity#mappingFilter` passes collapsed with byte parity (expected: only Jane survives `~filter $src.active`).
- [ ] **Step 6: New parity case `comparisonFilter`** exercising the comparison/boolean whitelist: source `S_Person {name: String[1]; age: Integer[1];}`, mapping `~filter ($src.age >= 18) && !($src.name == 'Zed')`, three source rows (17/18/Zed), expect exactly the 18-year-old. Follow the existing case template verbatim (grammar constant + query constant + expected constant + `@Test` calling `assertParity`).
- [ ] **Step 7: Run** `TestM2MSqlParity` (now 9 cases), both Pure test runners, commit.

---

### Task 4: Root `filter()` push-down

`Person.all()->filter(p | ...)->graphFetch(...)->serialize(...)`. The plan funnel stores the original filter lambda — over the **target** class — on `InMemoryRootGraphFetchExecutionNode.filter` (`CORE-PURE/src/main/resources/core/pure/mapping/modelToModel.pure:357`); phase 1 rejects any node carrying one (`collapse.pure:142`). Translate by rewriting each target-property access on the lambda parameter into that property's source transform, then feed the rewritten (now source-side) boolean into the Task-3 `filters` plumbing.

**Files:**
- Modify: `REL-CORE-PURE/.../m2mSql/translatability.pure` (new rewrite function + tests)
- Modify: `REL-CORE-PURE/.../m2mSql/collapse.pure:136-167` (`m2mSqlLocalNodeRejection` filter guard; thread result into the collapse), `:52-77` (`collapseSerializeNode` passes root filters to `buildM2MSqlQuery`)
- Modify: `REL-CORE-PURE/.../m2mSql/jsonProjection.pure:74` (`buildM2MSqlQuery` signature gains `rootFilters:ValueSpecification[*]`)
- Test: `TestM2MSqlParity.java` (`rootFilter` collapsed case + `rootFilterDeepNavigation` fallback case), `expected-fallbacks.json` (add `rootFilterDeepNavigation`), `testTranslatability.pure`

**Interfaces:**
- Consumes: `flattenTargetTree`'s per-property source-expression derivation — **first factor it out** as `meta::relational::m2mSql::sourceExpressionForProperty(setImpl:PureInstanceSetImplementation[1], propertyName:String[1], srcExpr:ValueSpecification[1]):ValueSpecification[0..1]` (the `$pms`-lookup + `autoMappedSourceExpression`/`substituteSourceVariable` block at `jsonProjection.pure:197-202`, returning `[]` when neither resolves instead of asserting), used by both call sites.
- Produces: `meta::relational::m2mSql::rewriteTargetFilterToSource(filter:LambdaFunction<Any>[1], setImpl:PureInstanceSetImplementation[1], srcVar:VariableExpression[1]):ValueSpecification[0..1]` — the rewritten source-side expression, or `[]` when not rewritable. `buildM2MSqlQuery(setImpl, targetTree, rootFilters, connection, extensions, debug)` concatenates `rootFilters` with the mapping filter into `buildProjectExpression`'s `filters`.

- [ ] **Step 1: RED.** Add parity case `rootFilter` (NOT in the manifest): reuse the `simpleRename` model, url with two people, query `|...Person.all()->filter(p | $p.fullName == 'Jane Doe')->graphFetch(...)->serialize(...)`; expect only Jane's document and a collapsed plan. It fails on plan shape (filter guard rejects).
- [ ] **Step 2: The rewriter** in `translatability.pure`:

```pure
function meta::relational::m2mSql::rewriteTargetFilterToSource(filter:LambdaFunction<Any>[1], setImpl:PureInstanceSetImplementation[1], srcVar:VariableExpression[1]):ValueSpecification[0..1]
{
  let parameters = $filter->functionType().parameters->evaluateAndDeactivate();
  if($parameters->size() != 1,
     | [],
     | $filter.expressionSequence->evaluateAndDeactivate()->toOne()
              ->rewriteTargetExpression($parameters->at(0).name, $setImpl, $srcVar));
}

function <<access.private>> meta::relational::m2mSql::rewriteTargetExpression(vs:ValueSpecification[1], paramName:String[1], setImpl:PureInstanceSetImplementation[1], srcVar:VariableExpression[1]):ValueSpecification[0..1]
{
  $vs->match(
  [
    v:VariableExpression[1] |
      // The bare lambda parameter outside a property access has no source-side meaning.
      if($v.name == $paramName, | [], | $v),
    sfe:SimpleFunctionExpression[1] |
      let params = $sfe.parametersValues->evaluateAndDeactivate();
      if($sfe.func->instanceOf(Property),
         // Only direct properties of the root target class this phase: $p.name yes, $p.address.city no.
         | if(($params->size() == 1) && $params->at(0)->instanceOf(VariableExpression) && ($params->at(0)->cast(@VariableExpression).name == $paramName),
              | sourceExpressionForProperty($setImpl, $sfe.func->cast(@Property<Nil,Any|*>).name->toOne(), $srcVar),
              | []),
         {|
           let rewritten = $params->map(p | $p->rewriteTargetExpression($paramName, $setImpl, $srcVar));
           if($rewritten->size() != $params->size(),
              | [],
              | ^$sfe(parametersValues = $rewritten)->evaluateAndDeactivate());
         });,
    i:InstanceValue[1] | $i,
    v:ValueSpecification[1] | []
  ]);
}
```

  Gate the rewritten result through `explainUntranslatableExpression` (it is now a source-side expression over `$src`); only a rewritable AND whitelisted filter collapses. Pure unit tests: direct-property rewrite, computed-source rewrite (`fullName` → `$src.firstName + ' ' + $src.lastName`), deep navigation returns `[]`, unwhitelisted function returns `[]`.
- [ ] **Step 3: Guard rewiring** in `m2mSqlLocalNodeRejection` (`collapse.pure:142`): split `$inMemory.filter->isNotEmpty() || $inMemory.checked` — `checked` still rejects; a filter now attempts the rewrite (needs `$setImpl`, already computed below — reorder so the setImpl/verdict block wraps the filter check). On rewrite failure: reason `'root filter is not translatable to the source'`. On success the collapse path must hand the rewritten expression to `collapseSerializeNode` → `buildM2MSqlQuery` — since the guard and the builder both start from the node, recompute the rewrite in `collapseSerializeNode` (cheap, keeps the guard's `String[0..1]` contract intact) rather than widening the guard's return type.
- [ ] **Step 4: GREEN** on `rootFilter`. **Step 5:** add fallback case `rootFilterDeepNavigation` (filter `$p.address.city == 'X'` over the `nestedComplexToOne` model) to the manifest with reason `root filter is not translatable to the source`; assert it still byte-matches via the Java path.
- [ ] **Step 6: Run** `TestM2MSqlParity` (11 cases), Pure runners, commit.

---

### Task 5: Enumeration-typed source properties (identity pass-through)

Phase 1 rejects any enumeration anywhere because the synthetic store's extract-type helper `fail`s on `Enumeration` (`sourceSynthesis.pure:141-151`). But an enum value in the source JSON is just a string; extracting it as VARCHAR makes the identity case (same enum on both sides, or auto-mapped) work end to end — the Java serializer emits the enum name string, and so does the VARCHAR extract.

**Files:**
- Modify: `REL-CORE-PURE/.../m2mSql/sourceSynthesis.pure:141-151` (`pureTypeToSemiStructuredExtractType`)
- Modify: `REL-CORE-PURE/.../m2mSql/translatability.pure:100-105` (auto-mapped enum rejection), `:186-195` (`explainUntranslatableExpression` enum-property rejection)
- Test: `TestM2MSqlParity.java` (new `enumIdentity` case), `DUCKDB-PURE/.../m2mSql/tests/testSourceSynthesis.pure`, `testTranslatability.pure`

**Interfaces:**
- Consumes/Produces: no signature changes. After this task the only remaining enum rejection is the `transformer` branch (`translatability.pure:107-108`), which Task 6 removes.

- [ ] **Step 1: RED.** Parity case `enumIdentity`: one enum `Color {RED, GREEN}` used by both source and target class (`color: Color[1]`), mapping `color: $src.color` (or auto-mapped — pick the explicit transform so `explainUntranslatableExpression`'s branch is exercised), url `{"color":"RED"}`, expected `{"builder":{"_type":"json"},"values":{"color":"RED"}}`. Not in the manifest → the test demands collapse; today it falls back (`enumeration-typed source property not yet translated`), so plan-shape fails.
- [ ] **Step 2: Extraction.** In `pureTypeToSemiStructuredExtractType`, before the primitive chain: `if($t->instanceOf(Enumeration), | 'VARCHAR', | ...)`. Pure unit test in `testSourceSynthesis.pure`: an enum-typed source property synthesizes a `RelationalPropertyMapping` whose extract type literal is `VARCHAR`.
- [ ] **Step 3: Whitelist.** Delete the two enum rejections: the auto-mapped branch check (`translatability.pure:102-104`) and the property-returning-`Enumeration` check inside `explainUntranslatableExpression` (`:188-189`) — keep the comments' knowledge by replacing them with a one-line note that VARCHAR extraction now handles enums (Task 5). Update the Pure unit tests that assert those rejections to assert acceptance instead.
- [ ] **Step 4: GREEN**, including the presence/null story: add a second source row shape to the case if cheap (optional enum property absent → Java path emits explicit `null`; the VARCHAR extract yields SQL NULL → same). Run all parity cases, Pure runners, commit.

---

### Task 6: Enumeration transformers → CASE

Flips the `enumTransform` parity case (`color: EnumerationMapping colorMapping: $src.color`). The inner query still projects the raw source value as VARCHAR (Task 5 made that work); the **outer** document builder wraps that column in a nested-`if` relational expression — rendered as CASE WHEN by the DuckDB processor — mapping each source value to its target enum name, with a terminal `error(...)` matching the Java path's hard failure on an unmatched source value (verified lazy per-row on DuckDB 1.3.0).

**Files:**
- Modify: `REL-CORE-PURE/.../m2mSql/translatability.pure:107-108` (transformer branch)
- Modify: `REL-CORE-PURE/.../m2mSql/jsonProjection.pure` (`M2MSqlColumn` gains enum-case data; `flattenTargetTree`, `buildJsonDocumentExpression`, `m2mSqlLeafReference` wiring)
- Modify: `REL-CORE-PURE/src/main/resources/core_relational/relational/sqlQueryToString/dbExtension.pure:1197` area (register dynaFn name `sqlError`)
- Modify: `DUCKDB-PURE/src/main/resources/core_relational_duckdb/relational/sqlQueryToString/duckdbExtension.pure:296` area (render `sqlError`)
- Modify: `DUCKDB-PCT/src/test/resources/m2msql/expected-fallbacks.json` (remove `enumTransform`)
- Test: `TestM2MSqlParity.java` (unmatched-value expectation), `testTranslatability.pure`, `testJsonProjection.pure`

**Interfaces:**
- Consumes: `PurePropertyMapping.transformer` → `EnumerationMapping<Any>.enumValueMappings` (each `EnumValueMapping` has `enum : Enum[1]`, `sourceValues : Any[*]`); phase-0 dynaFn registration precedent (`jsonObject` at `dbExtension.pure:1197` + `duckdbExtension.pure:296`).
- Produces: dynaFn `sqlError` rendering `error(%s)` on DuckDB; `M2MSqlEnumCase { sourceValue : Any[1]; targetName : String[1]; }` and `M2MSqlColumn.enumCases : M2MSqlEnumCase[*]` (empty = no transformer); `buildJsonDocumentExpression` consumes the projection's columns (signature gains the `M2MSqlProjection` or a column lookup — pick threading the projection through, keeping the tree walk as-is).

- [ ] **Step 1: RED.** Remove `enumTransform` from the manifest; `TestM2MSqlParity#enumTransform` fails on plan shape.
- [ ] **Step 2: Gate.** In `untranslatablePropertyReasons`, replace the transformer rejection: accept when the transformer `->instanceOf(EnumerationMapping)`, the transform passes `explainUntranslatableExpression` (post Task 5 an enum property access does), AND every `sourceValues` entry is an `Enum` or a `String`/`Integer` instance (other source-value types keep the old reason). Multi-valued `sourceValues` (`RED: [ROUGE, CARMIN]`) are fine — they become multiple WHEN arms.
- [ ] **Step 3: dynaFn.** Register `sqlError` in the `DynaFunctionRegistry` list (`dbExtension.pure:1197` area, alphabetical position) and render in `duckdbExtension.pure`:

```pure
    dynaFnToSql('sqlError',               $allStates,            ^ToSql(format='error(%s)')),
```

- [ ] **Step 4: Projection.** In `flattenTargetTree`, when `$pms->at(0).transformer->isNotEmpty()`, keep `transform` as the raw (substituted) source expression and populate `enumCases` from the enumValueMappings (`sourceValue` = each element of `sourceValues` normalized: an `Enum` becomes its `.name` string; `targetName` = `$evm.enum.name`). In `buildJsonDocumentExpression`'s leaf branch, when the matching column has `enumCases`, wrap the column reference in nested `if`/`equal` DynaFunctions ending in `sqlError`:

```pure
function <<access.private>> meta::relational::m2mSql::m2mSqlEnumCaseReference(columnName:String[1], cases:M2MSqlEnumCase[*], propertyName:String[1]):RelationalOperationElement[1]
{
  let ref = m2mSqlColumnReference($columnName);
  $cases->reverse()->fold({c, acc |
    ^DynaFunction
    (
      name       = 'if',
      parameters = [
                     ^DynaFunction(name = 'equal', parameters = [$ref, ^Literal(value = $c.sourceValue)]),
                     ^Literal(value = $c.targetName),
                     $acc
                   ]
    )->cast(@RelationalOperationElement)
  }, ^DynaFunction(name = 'sqlError', parameters = ^Literal(value = 'Unmatched enum source value for property ' + $propertyName))->cast(@RelationalOperationElement));
}
```

  (Phase 1 already renders `if`/`isNotNull` DynaFunctions through this exact path — `buildJsonDocumentExpression:339-347` — so only `sqlError` is new vocabulary. Verify `equal` renders `=` on DuckDB; if the dynaFn name differs in the relational vocabulary, use the one `isNotNull`'s neighbors use.)
- [ ] **Step 5: GREEN** on `enumTransform` (ROUGE → RED, collapsed, byte parity).
- [ ] **Step 6: Unmatched-value behavior.** Extend the case (or add `enumTransformUnmatched`): url `{"color":"NOIR"}` — assert BOTH paths throw (Java: enum resolution failure; SQL: `Invalid Input Error: Unmatched enum source value...`). Match on "both throw", not on message equality.
- [ ] **Step 7: Run** everything, commit.

---

### Task 7: Explode property mappings → `unnest`

Flips the `explodeMapping` parity case (`name*: $src.tags`, expected `[{"name":"a"},{"name":"b"},{"name":"c"}]` — one *document per element*, i.e. row multiplication). Mechanism (DuckDB-verified): inject `unnest(cast(json_extract(data, '$.tags') as VARCHAR[]))` as an extra SELECT column through the connection's `sqlQueryPostProcessors`, exactly like phase-1 presence columns (`jsonProjection.pure:146-172`); sibling scalar columns repeat per row, empty/absent arrays produce zero rows — both matching Java explode semantics.

**Files:**
- Modify: `REL-CORE-PURE/.../m2mSql/translatability.pure:109-110` (explode branch)
- Modify: `REL-CORE-PURE/.../m2mSql/jsonProjection.pure` (`M2MSqlProjection.explodes`, `flattenTargetTree`, `withPresenceColumns` → generalized injection, `buildProjectExpression` placeholder column, `buildM2MSqlQuery`)
- Modify: `dbExtension.pure` registry + `duckdbExtension.pure` (dynaFn `unnestJsonArray`)
- Modify: `expected-fallbacks.json` (remove `explodeMapping`)
- Test: `TestM2MSqlParity.java` (empty-array variant), `testTranslatability.pure`, `testJsonProjection.pure`

**Interfaces:**
- Consumes: `sourceJsonPath` (`jsonProjection.pure:177-188`), `pureTypeToSemiStructuredExtractType` (`sourceSynthesis.pure:141`), the presence-column injection pattern.
- Produces: dynaFn `unnestJsonArray(jsonColumn, pathLiteral, sqlTypeLiteral)` rendering on DuckDB as `unnest(cast(json_extract(%s, %s) as %s[]))` (the path literal passed pre-formed as `'$.tags'`); `M2MSqlExplodeColumn { columnName : String[1]; sourcePath : String[1]; elementSqlType : String[1]; }`; `M2MSqlProjection.explodes : M2MSqlExplodeColumn[*]`; `withInjectedColumns(connection, source, presences, explodes)` replacing `withPresenceColumns` (same alias-append mechanism, one post-processor).

- [ ] **Step 1: RED.** Remove `explodeMapping` from the manifest; plan-shape failure.
- [ ] **Step 2: Gate.** Replace the explode rejection in `untranslatablePropertyReasons`: accept when ALL of — the property tree is a leaf (`subTrees` empty); the transform `isPlainSourceNavigation` AND is a single-step navigation (`$src.<prop>`, checked by the navigated expression being the bare variable) to a **primitive** collection; the set implementation is the ROOT of the collapse (thread the same root/nested flag Task 3 introduced — a nested-set explode would multiply subtree rows under a single parent document, which the flat projection cannot express); and it is the ONLY explode mapping on that set (`$setImpl.propertyMappings->filter(pm | pm.explodeProperty == true)->size() == 1` — DuckDB zips multiple unnests, Java semantics for multiple explodes are unestablished). Everything else keeps a reason string. Pure unit tests per rejected shape.
- [ ] **Step 3: dynaFn** `unnestJsonArray`, registry + DuckDB rendering:

```pure
    dynaFnToSql('unnestJsonArray',        $allStates,            ^ToSql(format='unnest(cast(json_extract(%s, %s) as %s[]))', transform={p:String[3] | [$p->at(0), $p->at(1), $p->at(2)->replace('\'', '')]})),
```

  (Check how existing 3-arg dynaFns in `duckdbExtension.pure` un-quote a literal used as a type name — mirror `extractFromSemiStructured`'s handling; the type must land bare, the path as a quoted string.)
- [ ] **Step 4: Projection.** In `flattenTargetTree`'s leaf branch, when the property mapping has `explodeProperty == true`: emit no `M2MSqlColumn`, emit `M2MSqlExplodeColumn(columnName = $path->joinStrings('__'), sourcePath = '$.' + $expr->sourceJsonPath()->joinStrings('.'), elementSqlType = <element pure type>->pureTypeToSemiStructuredExtractType())` (export that helper from `sourceSynthesis.pure` — drop its `<<access.private>>` if needed). Generalize `withPresenceColumns` to `withInjectedColumns`, appending explode aliases as `^Alias(name = m2mSqlQuoteIdentifier($e.columnName), relationalElement = ^DynaFunction(name = 'unnestJsonArray', parameters = [$jsonRead, ^Literal(value = $e.sourcePath), ^Literal(value = $e.elementSqlType)]))`.
- [ ] **Step 5: Placeholder column.** `buildM2MSqlQuery` asserts `columns->isNotEmpty()` (`jsonProjection.pure:80`) — the `explodeMapping` case selects ONLY the exploded leaf, so the project would be empty. When `columns` is empty but injected columns exist, project one constant column instead:

```pure
  let effectiveColumns = if($projection.columns->isNotEmpty(), | $projection.columns,
                            | ^M2MSqlColumn(path = 'm2m__row', columnName = 'm2m__row',
                                            transform = buildColumnLambda($srcClass, ^InstanceValue(values = 1, genericType = ^GenericType(rawType = Integer), multiplicity = PureOne))));
```

  The outer document builder never references `m2m__row` (it walks the tree, and no tree path maps to it). Replace the assert with: at least one leaf overall (columns OR explodes).
- [ ] **Step 6: Outer doc.** The exploded leaf's document reference is a plain scalar column reference (each row carries one element), NOT `parseJson` — in `buildJsonDocumentExpression` the leaf's `isCollection` test uses the *target* property multiplicity (`name: String[1]` → already to-one), so no change should be needed; verify with the parity case and note in the report.
- [ ] **Step 7: GREEN** on `explodeMapping` (three documents, collapsed, byte parity — the multi-document envelope path, array of 3, is exercised for the first time on a single-source-row input).
- [ ] **Step 8:** Extend the case family: `explodeMappingEmpty` (url `{"tags":[]}`, expect `[]`) — verify the Java path agrees (zero instances serialize as `[]`); if the Java path emits something else, match IT and document.
- [ ] **Step 9: Run** everything, commit.

---

### Task 8: Verification sweep, docs, roadmap update

**Files:**
- Modify: `docs/engineering/architecture/m2m-sql-execution.md` (Section 5 whitelist description, Section 8 parity-case table, Section 9 roadmap — remove shipped items, keep phase-3 list: union, merge, to-many complex, chains/CTE, checked/constraints translation, metamodel-level outer query, warehouse dialects, server-config wiring, plan-time reason telemetry)
- Modify: `docs/superpowers/specs/2026-08-17-m2m-sql-execution-design.md` (status note: phase 2 implemented, pointer to this plan)
- Verify only (no code changes expected): full test sweep

**Interfaces:** none — documentation and verification.

- [ ] **Step 1: Full sweep on the final HEAD:** `Test_Pure_Relational` (3 pre-existing calendar failures allowed, nothing else), `Test_Pure_Relational_DuckDB`, `TestM2MSqlParity` (final count: 13+ cases — 10+ collapsed, `rootFilterDeepNavigation` + any remaining manifest entries fallback), `TestSQLGraphSerializeExecutionNode`, `TestM2MSqlFallbackLogging`, full `DUCKDB-PCT` module (JUnit3/4 discovery count must NOT drop — the vintage-engine regression from phase 1), `TestM2MGrammarCompileAndExecute`, `mvn checkstyle:check -Dcheckstyle.config.location=checkstyle.xml` on every touched module.
- [ ] **Step 2: Docs.** Update the architecture doc: whitelist section gains filters/enums/explode with their precise limits (root-set-only filter and explode, single explode per set, direct-target-properties-only root filter, enum source values must be enum/string/integer); Section 8's case table lists every parity case and its status; Section 9 prunes shipped items and records the two behavior notes (unmatched enum value now errors on both paths; empty/absent explode arrays produce zero documents).
- [ ] **Step 3: Manifest check.** `expected-fallbacks.json` must now contain only `rootFilterDeepNavigation` (plus any case a task legitimately re-added with its reason string matching the actual rejection).
- [ ] **Step 4: Authorizer positive test** (phase-1 deferred minor): both `RelationalMiddleTierPlanExecutionAuthorizer` selectors were extended in phase 1 to cover `SQLGraphSerializeExecutionNode`, but only negative-path coverage exists. Add one JUnit 5 test in the module owning those selectors asserting a plan containing a `SQLGraphSerializeExecutionNode` with a middle-tier connection IS selected for authorization. If constructing such a plan requires more than a protocol-POJO fixture, report the gap instead of building heavy scaffolding.
- [ ] **Step 5: Commit.**

---

## Task dependencies

```
Task 1 (telemetry)      — independent
Task 2 (hardening)      — independent
Task 3 (mapping filter) — first feature; introduces allowFilter flag + filters plumbing
Task 4 (root filter)    — needs Task 3 (filters parameter, root/nested flag)
Task 5 (enum identity)  — independent of 3/4
Task 6 (enum transformer) — needs Task 5
Task 7 (explode)        — needs Task 3's root/nested flag; sqlError/unnestJsonArray dynaFns independent of Task 6's
Task 8 (docs/sweep)     — last
```

Suggested execution order: 1, 2, 3, 4, 5, 6, 7, 8. Tasks 1+2 and 5 can run interleaved with 3/4 if parallelizing, but the translatability.pure edits overlap — serial is simpler and safe.

## Deferred to the Phase-3 plan (explicitly NOT here)

Union (`OperationSetImplementation` → UNION ALL, different plan node shape), merge, to-many complex properties (needs JSON aggregation / `list_transform` — DuckDB capability verified, design not done), chains as CTE layers, checked/`IChecked` envelopes, translating (rather than gating on) constraints, cross-store, metamodel-level outer query construction, warehouse dialects via the reserved `engine` field, plan-time fallback *reason* telemetry (needs a protocol addition), server-config-driven extension wiring. Each keeps falling back safely via the whitelist.
