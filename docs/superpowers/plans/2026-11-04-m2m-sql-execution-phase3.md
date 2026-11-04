# M2M SQL Execution (Phase 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Translate the rest of the single-class-mapping-tree M2M language to SQL — conditionals, boolean values, string functions, typed and object collections, collection operations, Float and date types, and the shapes phase 2 kept root-only — so that, for a mapping tree without unions, merges or chains, falling back to Java is the exception.

**Architecture:** Unchanged from phases 1–2: `Extension.postProcessExecutionPlan` → guard cascade (`collapse.pure`) → whitelist (`translatability.pure`) → one SQL statement built by `jsonProjection.pure` over the synthetic semi-structured source store (`sourceSynthesis.pure`) → `SQLGraphSerializeExecutionNode`. Every phase-3 feature is again (a) a gate widening, (b) a projection extension where needed, and (c) parity cases proving byte-identical output against the `JsonModelConnection` Java path. Scalar transform logic (conditionals, strings, collection functions over source arrays) goes through the existing relational Pure→SQL stack, which upstream PR #5097 extended with DuckDB array navigation, `filter`/`apply`/`reduce`/`size` and typed `TYPE[]` casts. Object collections are built in the outer document with DuckDB `list_transform`, because the relational router cannot project a class-typed path.

**Tech Stack:** Java 11 bytecode built on **JDK 17**, Maven, Pure (compiled mode in tests and production; interpreted mode in Pure IDE Light), DuckDB 1.3.0 (`duckdb.version` 1.3.0.0), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-17-m2m-sql-execution-design.md` (Sections 5–9) and the shipped-state reference `docs/engineering/architecture/m2m-sql-execution.md` (its Section 9 is the authoritative roadmap). This plan supersedes the spec's phase table for phase 3: the spec put merge and TDS-over-M2M here; this plan instead finishes single-tree coverage first and moves union, merge, chains and TDS to phase 4 (listed at the end), because they change the plan-node shape and need their own design pass.

**Mechanics verified up front against DuckDB 1.3.0** (the pinned jar), on a JSON column loaded the way `M2MSqlGraphSerializeHelper` loads it:
- `to_json(list_transform(cast(json_extract(data,'$.addresses') as JSON[]), x -> json_object('street', x->>'street', 'zip', cast(json_extract(x,'$.zip') as BIGINT))))` → `[{"street":"A","zip":1},{"street":"B","zip":2}]` for two elements, `[]` for an empty array, **SQL NULL for an absent key**.
- `to_json(cast(json_extract(data,'$.codes') as BIGINT[]))` → `[1,2,3000000000]`, `[]`, NULL for absent.
- `to_json(cast(json_extract(data,'$.f') as DOUBLE))` → `0.1`, `0.30000000000000004`, `1234567.891` (same shortest-round-trip text as Java `Double.toString` for these values).
- `strftime(cast(json_extract_string(data,'$.dt') as TIMESTAMP), '%Y-%m-%dT%H:%M:%S.%g+0000')` on `"2024-01-02T03:04:05.123Z"` → `2024-01-02T03:04:05.123+0000`; `cast(... as DATE)::VARCHAR` → `2024-01-02`.
- `len(cast(... as VARCHAR[]))`, `list_contains(...)`, `to_json(list_filter(..., t -> t <> 'x'))` behave as expected; `CASE WHEN` inside `json_object` works.
- **Decimal is not translatable on this ingestion:** DuckDB's JSON reader parses `12345678901234567890.123456789` as a double before any SQL runs (`json_extract` already returns `12345678901234567000.0`). Decimal stays a fallback in this phase (see phase 4).

## Global Constraints

- **JDK 17** for every Maven command: `. /home/aziem/bin/jdk17.sh` first (sets `JAVA_HOME` and `MAVEN_OPTS="-Xms12G -Xmx24G"`). The root pom enforces `[17,18)`.
- Every Maven command carries `-T 3 -Dmaven.gitcommitid.skip=true -Drevision=m2msql-SNAPSHOT` (the revision isolates this branch's jars in `~/.m2`; never commit a changed `<revision>`).
- Always `clean` for modules with Pure plugins. Build only the modules a task touches, upstream first: change in `REL-CORE-PURE` → `clean install` it, then `clean install` `DUCKDB-PURE`, then `clean test` `DUCKDB-PCT`.
- Do not run Maven while Pure IDE Light is running (it serves from `target/` directories that `clean` deletes).
- New tests: JUnit 5 where the module has it (the DuckDB PCT module does). Every new file, including `.pure`, carries the Apache 2.0 header. Java 4 spaces, Pure 2 spaces, braces always, no tabs. SLF4J only.
- JSON comparison in Java tests: `JsonAssert.assertJsonEquals`.
- **Whitelist discipline (spec §5, binding):** a construct is translated only when positively known to be; anything else returns a fallback reason string, never an assert or `fail` on the collapse path. Add a function to a whitelist only together with a parity case that exercises it.
- **Parity discipline:** every widening lands with `TestM2MSqlParity` cases proving byte-identical output against the `JsonModelConnection` path, including an absent/empty input row; collapsed cases must contain `sqlGraphSerialize` and no `implementation`; fallback cases are listed in `expected-fallbacks.json` with their reason. Never weaken `assertParity`.
- **Interpreted-mode rule (learned in phase 2):** in Pure code on the collapse path, read `.values` of an `InstanceValue`, `.parametersValues` and `.expressionSequence` through `->evaluateAndDeactivate()`, and build lambdas with `^LambdaFunction<Any>(expressionSequence = …)->withClassifierType(…)` (`jsonProjection.pure`), never `^LambdaFunction<{…}>(classifierGenericType = …)`. Compiled-mode tests cannot catch a violation; Task 10 checks the examples in IDE Light.
- Commit at the end of every task. **No `Co-Authored-By: Claude` or `Claude-Session:` trailers** (FINOS CLA check).
- No protocol change is planned. If a task needs one, stop and escalate; protocol additions go into both `vX_X_X` and `v1_33_0`.

**Module aliases** (relative to the repo root):

| Alias | Path |
|---|---|
| `REL-CORE-PURE` | `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure` |
| `DUCKDB-PURE` | `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-dbExtension/legend-engine-xt-relationalStore-duckdb/legend-engine-xt-relationalStore-duckdb-pure` |
| `DUCKDB-PCT` | `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-dbExtension/legend-engine-xt-relationalStore-duckdb/legend-engine-xt-relationalStore-duckdb-PCT` |
| `M2MSQL` | `REL-CORE-PURE/src/main/resources/core_relational/relational/modelToModelToRelational/m2mSql` |

**Standard test commands** (each task names which it needs):
- Pure unit tests: `mvn clean install $OPTS -Dmaven.test.failure.ignore=true -Dtest=Test_Pure_Relational -pl REL-CORE-PURE` — expected: only the 3 pre-existing calendar failures (`testMostRecentDayOfWeekWithDate`, `testPreviousDayOfWeekWithDate`, `testDayOfWeekNumberFunction`).
- DuckDB Pure tests: `mvn clean install $OPTS -Dmaven.test.failure.ignore=true -Dtest=Test_Pure_Relational_DuckDB -pl DUCKDB-PURE` — expected: all pass.
- Parity: `mvn clean test $OPTS -Dtest=TestM2MSqlParity -pl DUCKDB-PCT` — expected: all pass.

## Review Focus

1. **Absent versus empty collections.** A to-many property whose key is absent from the source JSON yields SQL NULL from every extract, while the Java path serializes `[]`. Expected: `[]` on both paths. Pinned in Tasks 4 and 5 by an absent-key row in every collection parity case.
2. **Null operands in scalar functions.** `toUpper`, `trim`, `+` and comparisons on an absent optional source property. Java either propagates an empty value or rejects the call at compile time; SQL propagates NULL. Expected: the same document on both paths. Pinned in Tasks 1–3 by an absent-property row in each case.
3. **DateTime inputs in other shapes.** No milliseconds, a non-UTC offset (`+05:00`), a bare date passed to a DateTime property. Expected: the same text as Java's serializer, or a fallback. Pinned in Task 7 by a parity case per input shape.
4. **String semantics that differ by engine.** `trim` (Unicode whitespace versus spaces), `substring` (0- versus 1-based), case mapping of non-ASCII letters, `indexOf` (DuckDB's dynaFn is 1-based against Pure's 0-based; see the TODO in `duckdbExtension.pure`). Expected: either identical output or the function stays off the whitelist. Pinned in Task 3 by a non-ASCII and boundary-index row.
5. **Element order and duplicates in list operations.** `list_transform`/`list_filter` preserve input order, which the Java path also does. Expected: order and duplicates preserved. Pinned in Tasks 4–6 by inputs with repeated elements in a non-sorted order.

---

### Task 1: Conditional and boolean-valued transforms

Phase 2 kept comparison and boolean functions filter-only, because `isBoss: $src.title == 'Boss'` yields `false` on Java and SQL NULL when `title` is absent. This task makes boolean values and `if` translatable, with absence handled explicitly.

**Files:**
- Modify: `M2MSQL/translatability.pure` (`explainUntranslatable`, `sqlTranslatableFunctionWhitelist`, `sqlTranslatableFilterFunctions`)
- Modify: `M2MSQL/jsonProjection.pure` (`flattenTargetTree` leaf branch: wrap boolean leaves)
- Modify: `DUCKDB-PCT/src/test/resources/m2msql/expected-fallbacks.json` (remove `booleanTransform`)
- Test: `TestM2MSqlParity.java` (new `conditionalTransform`, `booleanTransform` flipped), `M2MSQL/tests/testTranslatability.pure`

**Interfaces:**
- Consumes: `explainUntranslatable(vs, whitelist)`, `explainUntranslatableExpression(vs)` (transform whitelist), `explainUntranslatableFilterExpression(vs)` (filter whitelist).
- Produces: `if_Boolean_1__Function_1__Function_1__T_m_`, `isEmpty_Any_MANY__Boolean_1_`, `isNotEmpty_Any_MANY__Boolean_1_` on the transform whitelist; the filter-only functions also admitted in transforms when the leaf is Boolean-typed; a private `meta::relational::m2mSql::nullSafeBooleanLeaf(expr:ValueSpecification[1]):ValueSpecification[1]` that wraps a Boolean-typed transform so an absent operand reads as `false`.

- [ ] **Step 1: Failing parity cases.** In `TestM2MSqlParity`, add `conditionalTransform`: source `S_Person {name: String[1]; age: Integer[1]; nickname: String[0..1];}`, target `Person {label: String[1]; hasNickname: Boolean[1];}`, mapping `label: if($src.age >= 18, |'adult: ' + $src.name, |'minor: ' + $src.name), hasNickname: $src.nickname->isNotEmpty()`, url `[{"name":"Jane","age":34,"nickname":"J"},{"name":"Tim","age":12}]`, expected `[{"label":"adult: Jane","hasNickname":true},{"label":"minor: Tim","hasNickname":false}]`. Remove `booleanTransform` from `expected-fallbacks.json` (its expected output stays `[{"isBoss":false},{"isBoss":true}]`).
- [ ] **Step 2: Run** parity. Expected: `conditionalTransform` and `booleanTransform` fail on plan shape ("was expected to collapse to SQL").
- [ ] **Step 3: Whitelist.** Add `if`, `isEmpty`, `isNotEmpty` to `sqlTranslatableFunctionWhitelist()`. `if`'s branches are `InstanceValue`s holding lambdas: in `explainUntranslatable`, before the primitive-literal check, accept an `InstanceValue` whose single value is a zero-parameter `LambdaFunction` by checking that lambda's `expressionSequence->evaluateAndDeactivate()->toOne()` recursively (any other lambda keeps `'unsupported literal in transform'`). Admit the filter-only functions (`sqlTranslatableFilterFunctions()`) in a transform only when the property's type is `Boolean`: in `untranslatablePropertyReasons`, check a Boolean-typed leaf with `explainUntranslatableFilterExpression` instead of `explainUntranslatableExpression`.
- [ ] **Step 4: Null-safe Boolean leaf.** In `flattenTargetTree`, when the leaf property's type is `Boolean`, wrap `$expr` before `buildColumnLambda`:

```pure
function <<access.private>> meta::relational::m2mSql::nullSafeBooleanLeaf(expr:ValueSpecification[1]):ValueSpecification[1]
{
  // `[] == 'x'` is false on the Java path and NULL in SQL; coalescing the whole leaf to false
  // gives the Java value whenever NULL can only come from an absent operand.
  ^SimpleFunctionExpression
  (
    func             = meta::pure::functions::lang::if_Boolean_1__Function_1__Function_1__T_m_,
    functionName     = 'if',
    importGroup      = system::imports::coreImport,
    genericType      = ^GenericType(rawType = Boolean),
    multiplicity     = PureOne,
    parametersValues = [ $expr->isNotEmptyCall(), ^InstanceValue(values = $expr->zeroArgLambda(), genericType = ^GenericType(rawType = LambdaFunction), multiplicity = PureOne), ^InstanceValue(values = false->zeroArgLambda(), genericType = ^GenericType(rawType = LambdaFunction), multiplicity = PureOne) ]
  )->evaluateAndDeactivate();
}
```

  If the relational router cannot route this `if(isNotEmpty(x), |x, |false)` shape, use the DuckDB `coalesce` route instead: register no new dynaFn — `meta::pure::functions::collection::isEmpty` and `if` already route — and record which form landed in the task report. `isNotEmptyCall`/`zeroArgLambda` are small private builders in the same file; build lambdas with `^LambdaFunction<Any>(expressionSequence = …)->withClassifierType(^FunctionType(returnMultiplicity = PureOne, returnType = …))`.
- [ ] **Step 5: Pure unit tests** in `testTranslatability.pure`: `if` with whitelisted branches is translatable; `if` with a `trim` in one branch reports `'unsupported function in transform: trim'`; `$src.title == 'Boss'` is translatable for a `Boolean` target and rejected (`unsupported function in transform: equal`) for a `String` target that converts with `toString`.
- [ ] **Step 6: Run** Pure unit tests, DuckDB Pure tests and parity. Expected: all green; parity count +1.
- [ ] **Step 7: Commit** `M2M SQL: conditional and boolean-valued transforms`.

---

### Task 2: Null-safe negation and equality in filters

Closes review finding I1 properly: `!(($src.status == 'X') && ($src.region == 'EU'))` with an absent `status` keeps the row on Java and drops it in SQL, so phase 2 rejects `not` over a compound containing an equality.

**Files:**
- Modify: `M2MSQL/translatability.pure` (`explainUntranslatable`: drop the compound-negation rejection), `M2MSQL/jsonProjection.pure` (filters passed to `buildProjectExpression`)
- Modify: `expected-fallbacks.json` (remove `negatedCompoundFilter`)
- Test: `TestM2MSqlParity.java`, `testTranslatability.pure`

**Interfaces:**
- Consumes: `buildProjectExpression(srcClass, columns, filters)`, `mappingFilterOver`, `rewriteTargetFilterToSource`.
- Produces: private `meta::relational::m2mSql::nullSafeFilter(expr:ValueSpecification[1]):ValueSpecification[1]` that rewrites every `equal(a, b)` with an optional operand into `if(isEmpty(a) || isEmpty(b), |isEmpty(a) && isEmpty(b), |a == b)` before the filter reaches `buildProjectExpression`.

- [ ] **Step 1: Failing test.** Remove `negatedCompoundFilter` from the manifest. Add parity case `negatedOrFilter` on the same `optionalFilter` grammar with filter `!(($src.status == 'X') || ($src.region == 'US'))`, expected `{"name":"a"}`.
- [ ] **Step 2: Run** parity. Expected: both cases fail on plan shape.
- [ ] **Step 3: Implement** `nullSafeFilter` (walk `SimpleFunctionExpression`s through `parametersValues->evaluateAndDeactivate()` and `InstanceValue.values->evaluateAndDeactivate()`; rewrite `equal` when either operand's multiplicity lower bound is 0) and apply it to the mapping filter and to each rewritten root filter in `buildM2MSqlQuery`. Delete the `'negation of an expression that combines an equality with other conditions'` branch from `explainUntranslatable`, and the `isEquality` helper if nothing else uses it.
- [ ] **Step 4: Unit test** in `testTranslatability.pure`: the negated-compound mapping from phase 2 is now translatable.
- [ ] **Step 5: Run** Pure unit tests, DuckDB Pure tests, parity. Expected: green.
- [ ] **Step 6: Commit** `M2M SQL: null-safe equality under negation`.

---

### Task 3: String functions

**Files:**
- Modify: `M2MSQL/translatability.pure` (`sqlTranslatableFunctionWhitelist`)
- Test: `TestM2MSqlParity.java` (new `stringFunctions`, `stringFunctionsBoundaries`), `testTranslatability.pure`

**Interfaces:**
- Produces: whitelist entries `toUpper_String_1__String_1_`, `toLower_String_1__String_1_`, `length_String_1__Integer_1_`, `startsWith_String_1__String_1__Boolean_1_`, `endsWith_String_1__String_1__Boolean_1_`, `contains_String_1__String_1__Boolean_1_`, `substring_String_1__Integer_1__Integer_1__String_1_`, `replace_String_1__String_1__String_1__String_1_`, `joinStrings_String_MANY__String_1__String_1_`, `toString_Any_1__String_1_` — each kept only if its parity case passes.

- [ ] **Step 1: Failing parity case** `stringFunctions`: target `Item {upper: String[1]; lower: String[1]; len: Integer[1]; sub: String[1]; rep: String[1]; joined: String[1]; isA: Boolean[1]; num: String[1];}` from `S_Item {name: String[1]; tags: String[*]; n: Integer[1];}` with `upper: $src.name->toUpper(), lower: $src.name->toLower(), len: $src.name->length(), sub: $src.name->substring(1, 3), rep: $src.name->replace('a', 'o'), joined: $src.tags->joinStrings(','), isA: $src.name->startsWith('A'), num: $src.n->toString()`; rows `{"name":"Anna","tags":["x","y","x"],"n":7}` and `{"name":"élan","tags":[],"n":-1}`. Expected values come from the Java path: run the case once with the `${CONNECTION_TYPE}` grammar on `JsonModelConnection` only (temporarily call `execute` directly), paste its output as the expected constant, then restore `assertParity`.
- [ ] **Step 2: Run** parity. Expected: fails on plan shape.
- [ ] **Step 3: Whitelist** the ten functions above (verify each FQN compiles).
- [ ] **Step 4: Run** parity. For each function whose SQL output differs from Java (expected candidates: `substring` index base, `toUpper` on `é`, `joinStrings` on `[]`), remove it from the whitelist, move its property into a separate fallback case `stringFunctionsUnsupported` with the reason string, and record the difference in the task report. Do **not** whitelist `trim` or `indexOf` (Review Focus 4) unless a parity case with leading `\t`/non-breaking space and a 0-based index proves equality.
- [ ] **Step 5: Unit test:** one whitelisted and one rejected string function each produce the expected reason.
- [ ] **Step 6: Run** the three standard commands. Expected: green.
- [ ] **Step 7: Commit** `M2M SQL: string functions in transforms`.

---

### Task 4: Typed primitive collections

Lifts the phase-2 `'non-string primitive collection not yet translated'` gate for the types proven here.

**Files:**
- Modify: `M2MSQL/translatability.pure` (`propertyShapeReasons`, `mappedLeafReasons`, `autoMappedLeafReasons`)
- Modify: `M2MSQL/sourceSynthesis.pure` (`buildSourcePropertyMapping`: to-many primitive extract type `TYPE[]`)
- Modify: `M2MSQL/jsonProjection.pure` (`m2mSqlLeafReference`: collection leaf → `toJson` of the typed list, NULL → `[]`)
- Modify: `expected-fallbacks.json` (remove `integerCollection`)
- Test: `TestM2MSqlParity.java` (`integerCollection` flipped; new `booleanCollection`), `DUCKDB-PURE/.../tests/testSourceSynthesis.pure`

**Interfaces:**
- Consumes: the shared `extractFromSemiStructured` type list, which since #5097 accepts `TYPE[]` and renders `cast(json_extract(...) as TYPE[])` on DuckDB.
- Produces: to-many primitive source properties of `String`, `Integer`, `Boolean` extracted as `VARCHAR[]`, `BIGINT[]`, `BOOLEAN[]`; collection leaves rendered as `coalesce(to_json(<list>), '[]')` (dynaFn `toJson` already exists; `coalesce` routes through the existing `coalesce` dynaFn).

- [ ] **Step 1: Failing tests.** Remove `integerCollection` from the manifest (expected stays `{"codes":[1,2,3]}`); change its url to `[{"codes":[1,2,3000000000,2]},{"codes":[]},{}]` and expected to `[{"codes":[1,2,3000000000,2]},{"codes":[]},{"codes":[]}]` (Review Focus 1 and 5). Add `booleanCollection` similarly. Add a `testSourceSynthesis.pure` case asserting an `Integer[*]` source property synthesizes extract type `BIGINT[]`.
- [ ] **Step 2: Run** DuckDB Pure tests and parity. Expected: the new/changed cases fail.
- [ ] **Step 3: Implement.** In `buildSourcePropertyMapping`, for a to-many primitive pass `pureTypeToSemiStructuredExtractType(t) + '[]'`. Replace the String-only check in `propertyShapeReasons`/`mappedLeafReasons`/`autoMappedLeafReasons` with "element type in `[String, Integer, Boolean]`" (Float joins in Task 6). In `m2mSqlLeafReference`, render a collection leaf as `coalesce(toJson(ref), '[]')` instead of `parseJson(ref)`.
- [ ] **Step 4: Run** the three standard commands; update the pinned SQL strings that change (`primitiveCollection` projections) from the actual output after checking they still read the array. Expected: green.
- [ ] **Step 5: Commit** `M2M SQL: typed primitive collections`.

---

### Task 5: To-many complex properties

The largest single shape the Java path supports that SQL does not: `addresses: Address[*]`, one JSON object per source element. Built in the outer document with `list_transform`, because the router cannot project a class-typed path.

**Files:**
- Modify: `M2MSQL/translatability.pure` (`propertyShapeReasons`: drop `'to-many complex property not yet translated'` for the supported shape; new `toManyComplexReasons`)
- Modify: `M2MSQL/jsonProjection.pure` (new `M2MSqlArrayColumn`; `flattenTargetTree`; `withInjectedColumns`; `buildJsonDocumentExpression` element builder)
- Modify: `REL-CORE-PURE/.../sqlQueryToString/dbExtension.pure` registry, `DUCKDB-PURE/.../sqlQueryToString/duckdbExtension.pure` (dynaFns `m2mElementArray`, `m2mElement`)
- Modify: `expected-fallbacks.json`
- Test: `TestM2MSqlParity.java` (new `toManyComplex`, `toManyComplexNested`, `toManyComplexComputed` fallback), `testTranslatability.pure`, `DUCKDB-PURE/.../tests/testJsonProjection.pure`

**Interfaces:**
- Produces:
  - `Class meta::relational::m2mSql::M2MSqlArrayColumn { columnName: String[1]; sourcePath: String[1]; }` — injected as `json_extract("root".data, '<sourcePath>') as "<columnName>"`, so the inner row carries the raw JSON array.
  - dynaFn `m2mElementArray(arrayColumn, elementObject)` rendering on DuckDB as `coalesce(to_json(list_transform(cast(%s as JSON[]), m2m_e -> %s)), '[]')`.
  - dynaFn `m2mElement()` rendering as `m2m_e` (the lambda variable), used as the base of element leaf extracts.
  - Element leaves are `extractFromSemiStructured(m2mElement(), '<path>', '<TYPE>')`; element sub-objects are `if(isNotNull(extractFromSemiStructured(m2mElement(), '<path>', 'VARCHAR')), jsonObject(...), NULL)` — the same presence idea as phase 1.
  - `toManyComplexReasons(propertyMapping, propertyTree, mapping):String[*]` — accepts only: the transform is a plain source navigation to a to-many class-typed source property; every leaf under it (recursively) is a plain navigation of its nested set; nested to-many complex recursion allowed; no explode, enum transformer, filter or local property inside.

- [ ] **Step 1: Failing parity cases.** `toManyComplex`: `Person {name: String[1]; addresses: Address[*];}`, `Address {street: String[1]; zip: Integer[0..1];}` from matching source classes, mapping `addresses: $src.addresses` plus an `Address` class mapping with plain navigations; url `[{"name":"Jane","addresses":[{"street":"B","zip":2},{"street":"A"},{"street":"B","zip":2}]},{"name":"Tim","addresses":[]},{"name":"Joe"}]`. Expected from the Java path (capture as in Task 3 Step 1; Review Focus 1 and 5 — the absent row must be `"addresses":[]` if Java says so). `toManyComplexNested`: `Address.lines: Line[*]` one level deeper. `toManyComplexComputed`: `street: $src.street->toUpper()` inside the element — fallback with reason `'computed property street inside a to-many complex property not yet translated'`.
- [ ] **Step 2: Run** parity. Expected: the two collapsed cases fail on plan shape.
- [ ] **Step 3: dynaFns.** Register `m2mElement` and `m2mElementArray` in the `DynaFunctionRegistry` list (`dbExtension.pure`, alphabetical) and render in `duckdbExtension.pure`:

```pure
    dynaFnToSql('m2mElement',             $allStates,            ^ToSql(format='m2m_e')),
    dynaFnToSql('m2mElementArray',        $allStates,            ^ToSql(format='coalesce(to_json(list_transform(cast(%s as JSON[]), m2m_e -> %s)), \'[]\')')),
```

  A nested to-many inside an element needs its own variable name: make the variable name a parameter (`m2mElement(depthLiteral)` → `m2m_e<depth>`) if `toManyComplexNested` needs it, and record that in the report.
- [ ] **Step 4: Projection.** In `flattenTargetTree`, a to-many complex property emits an `M2MSqlArrayColumn` (path joined by `__`, `sourcePath = '$.' + sourceJsonPath`) and no columns for its subtree. In `withInjectedColumns`, append `^Alias(name = m2mSqlQuoteIdentifier(columnName), relationalElement = ^DynaFunction(name = 'extractFromSemiStructured', parameters = [$jsonRead, ^Literal(value = <path without '$.'>), ^Literal(value = 'SEMISTRUCTURED')]))` (the `SEMISTRUCTURED` return type keeps it as a JSON document, see `dbExtension.pure`). In `buildJsonDocumentExpression`, the to-many complex property becomes `m2mElementArray(m2mSqlColumnReference(columnName), <element json_object>)`, where the element object is built by a new `buildElementObject(tree, nestedSetImpl, mapping)` that maps each leaf to `extractFromSemiStructured(m2mElement(), <leaf source path relative to the element>, <extract type>)`.
- [ ] **Step 5: Gate.** `toManyComplexReasons` as specified; reuse `isPlainSourceNavigation` and `resolveNestedSetImplementation`.
- [ ] **Step 6: Pinned SQL** in `testJsonProjection.pure` for the `toManyComplex` shape, from the actual output.
- [ ] **Step 7: Run** the three standard commands. Expected: green.
- [ ] **Step 8: Commit** `M2M SQL: to-many complex properties via list_transform`.

---

### Task 6: Float

**Files:**
- Modify: `M2MSQL/sourceSynthesis.pure` (`provenPrimitiveTypes` gains `Float`)
- Modify: `M2MSQL/translatability.pure` (explode element types gain `Float`; collection element types gain `Float`)
- Test: `TestM2MSqlParity.java` (new `floatValues`), `testTranslatability.pure` (update `testUnprovenScalarTypesAreNotTranslatable` and `testExplodeOfFloatCollectionIsNotTranslatable`)

**Interfaces:**
- Consumes: `provenPrimitiveTypes()`, `pureTypeToSemiStructuredExtractType` (Float → `FLOAT`, which the DuckDB extract renders as `DOUBLE`).
- Produces: Float leaves, Float collections (`DOUBLE[]`), Float explode, `divide` results as leaves.

- [ ] **Step 1: Failing parity case** `floatValues`: `price: Float[1]` (pass-through), `ratio: Float[1]` (`$src.a / $src.b` with Integer `a`, `b`), `prices: Float[*]`; rows with `0.1`, `0.30000000000000004`, `1234567.891`, `1e-7`, `-0.0`, `100.0`, `1/3` and an empty `prices`. Expected from the Java path (capture). Plus `floatExplode` over `prices`.
- [ ] **Step 2: Run.** Expected: fails on plan shape (Float is unproven).
- [ ] **Step 3: Implement:** add `Float` to `provenPrimitiveTypes()`; extend the collection and explode element-type lists to `[String, Integer, Float, Boolean]`; make the explode element type `DOUBLE` (`unnestJsonArray` currently passes `FLOAT`, which is 32-bit in an unnest).
- [ ] **Step 4: Run.** If any value prints differently (expected candidates: `1e-7` → `1.0E-7` in Java, `-0.0`, `100.0` → `100.0` vs `100`), keep Float out of `provenPrimitiveTypes()`, record each divergence in the architecture doc's roadmap, and leave the test as a fallback case. Only an exact match on every row flips Float.
- [ ] **Step 5: Run** the three standard commands. Expected: green.
- [ ] **Step 6: Commit** `M2M SQL: Float values` (or `M2M SQL: pin Float divergences` if Step 4 kept it out).

---

### Task 7: StrictDate and DateTime

**Files:**
- Modify: `M2MSQL/sourceSynthesis.pure` (`provenPrimitiveTypes`; extract types `DATE`, `TIMESTAMP`)
- Modify: `M2MSQL/jsonProjection.pure` (leaf rendering: date leaves formatted for JSON)
- Modify: `DUCKDB-PURE/.../duckdbExtension.pure` (dynaFn `m2mJsonDateTime`, `m2mJsonStrictDate`), `dbExtension.pure` registry
- Test: `TestM2MSqlParity.java` (`dates`, `dateTimeShapes`), `testJsonProjection.pure`

**Interfaces:**
- Produces: dynaFn `m2mJsonDateTime(ts)` → `strftime(%s, '%Y-%m-%dT%H:%M:%S.%g+0000')` and `m2mJsonStrictDate(d)` → `strftime(%s, '%Y-%m-%d')`; the outer document wraps StrictDate/DateTime leaves in them.

- [ ] **Step 1: Failing parity cases.** `dates`: `born: StrictDate[1]`, `seen: DateTime[0..1]`, rows `{"born":"1990-05-17","seen":"2024-01-02T03:04:05.123Z"}` and `{"born":"2000-01-01"}`; expected from the Java path. `dateTimeShapes` (Review Focus 3): `seen` values `"2024-01-02T03:04:05Z"`, `"2024-01-02T03:04:05.1+05:00"`, `"2024-01-02T03:04:05.123456Z"`; expected from the Java path.
- [ ] **Step 2: Run.** Expected: fails on plan shape.
- [ ] **Step 3: Implement** the two dynaFns and the leaf wrapping; add `StrictDate` and `DateTime` to `provenPrimitiveTypes()`.
- [ ] **Step 4: Run.** For any input shape whose output differs (expected candidates: offset normalization, sub-millisecond precision), add a guard that cannot be made in SQL — the input shape is data, not schema — so: if `dateTimeShapes` diverges on any row, keep DateTime out of `provenPrimitiveTypes()` (StrictDate may still flip on its own) and pin the divergence in the roadmap.
- [ ] **Step 5: Run** the three standard commands. Expected: green.
- [ ] **Step 6: Commit** `M2M SQL: StrictDate and DateTime values`.

---

### Task 8: Collection operations in transforms

`count: $src.tags->size()`, `first: $src.tags->first()`, `kept: $src.tags->filter(t | $t != 'x')`, `upper: $src.tags->map(t | $t->toUpper())`, `has: $src.tags->contains('x')`, `any: $src.tags->exists(t | $t == 'y')`, `total: $src.amounts->sum()`. Routed through the relational semi-structured array support from #5097 (`array_size`, `array_first`, `array_filter`, `apply`, `reduce`).

**Files:**
- Modify: `M2MSQL/translatability.pure` (whitelist; lambda-valued arguments)
- Modify: `M2MSQL/translatability.pure` (`mappedLeafReasons`: a collection leaf may now be a collection-valued function of a to-many source property, not only a plain navigation)
- Test: `TestM2MSqlParity.java` (`collectionFunctions`, `collectionFunctionsEmpty`), `testTranslatability.pure`

**Interfaces:**
- Produces: whitelist entries `size_Any_MANY__Integer_1_`, `first_T_MANY__T_0_1_`, `filter_T_MANY__Function_1__T_MANY_`, `map_T_MANY__Function_1__V_MANY_`, `contains_Any_MANY__Any_1__Boolean_1_`, `exists_T_MANY__Function_1__Boolean_1_`, `sum_Integer_MANY__Integer_1_`, `sum_Float_MANY__Float_1_`, `max_Integer_MANY__Integer_0_1_`, `min_Integer_MANY__Integer_0_1_` — each kept only if routed and parity-equal; lambda arguments whose single parameter is the element and whose body passes the transform whitelist with that parameter admitted as a variable.

- [ ] **Step 1: Probe the routes first.** In a DuckDB Pure test (`testJsonProjection.pure`), call `buildM2MSqlQuery` with a mapping using each function, and record which ones produce SQL and which fail inside `pureToSQLQuery`. Write the result into the task report; only the functions that produce SQL go on.
- [ ] **Step 2: Failing parity cases** for the routed functions, rows `{"tags":["b","x","a","x"],"amounts":[3,1]}`, `{"tags":[],"amounts":[]}`, `{}` (Review Focus 1, 5). Expected from the Java path.
- [ ] **Step 3: Run.** Expected: fails on plan shape.
- [ ] **Step 4: Whitelist and lambdas.** Accept an `InstanceValue` holding a one-parameter `LambdaFunction` as an argument of a whitelisted collection function; check its body with that parameter's name admitted alongside `src` (extend `explainUntranslatable` with an `admittedVariables:String[*]` parameter, defaulting to `['src']`).
- [ ] **Step 5: Run** the three standard commands; for any function whose output differs on the empty or absent row, drop it from the whitelist and record why.
- [ ] **Step 6: Commit** `M2M SQL: collection operations in transforms`.

---

### Task 9: Below-the-root shapes and deeper root filters

Phase 2 accepted several shapes only at the root. Upstream's change to nested leaf extraction (nested leaves arrive as typed scalars, `->>'$'` / casts) removes the main reason.

**Files:**
- Modify: `M2MSQL/translatability.pure` (`mappedLeafReasons` computed-below-root rule, `enumTransformerReasons` `isRoot` rule, `filterableSourceExpression` / `rewriteTargetExpression` complex navigation)
- Modify: `M2MSQL/jsonProjection.pure` (`m2mSqlEnumCaseReference` for nested columns — no change expected; verify)
- Test: `TestM2MSqlParity.java` (`nestedComputed`, `nestedEnumTransform`, `rootFilterNestedNavigation` flipped from `rootFilterDeepNavigation`), `testTranslatability.pure`

**Interfaces:**
- Consumes: `sourceExpressionForProperty`, `resolveNestedSetImplementation`.
- Produces: `rewriteTargetExpression` follows a to-one complex property chain (`$p.address.city`) by composing each level's transform (`substituteSourceVariable`), mirroring `flattenTargetTree`; to-many navigation in a root filter still returns `[]`.

- [ ] **Step 1: Failing parity cases.** `nestedComputed`: `Address.label: $src.city + ', ' + $src.country`. `nestedEnumTransform`: an enumeration mapping on `Address.kind`. Move `rootFilterDeepNavigation` off the manifest (rename the test to `rootFilterNestedNavigation`; expected unchanged). Keep nested `~filter` a fallback unless a parity case `nestedMappingFilter` shows the Java semantics (child set filtered → property `null`) are reproducible: write that case first and decide from its Java output.
- [ ] **Step 2: Run.** Expected: fails on plan shape.
- [ ] **Step 3: Implement** the three gate relaxations and the root-filter chain rewrite.
- [ ] **Step 4: Run** the three standard commands. Expected: green.
- [ ] **Step 5: Commit** `M2M SQL: computed and enum leaves below the root; nested root filters`.

---

### Task 10: Verification sweep, IDE check, docs

**Files:**
- Modify: `docs/engineering/architecture/m2m-sql-execution.md` (Sections 5, 6, 8, 9)
- Modify: `docs/superpowers/specs/2026-08-17-m2m-sql-execution-design.md` (status line)

- [ ] **Step 1: Full sweep** on the final commit: `mvn clean install $OPTS -DskipTests` (whole repo), then the three standard commands, the full `DUCKDB-PCT` module, `TestM2MGrammarCompileAndExecute`, the server module's `TestM2MSqlServerWiring,TestExtensions,Test_M2MMFT`, and `mvn checkstyle:check -o -fae -Dcheckstyle.config.location=$PWD/checkstyle.xml $OPTS`.
- [ ] **Step 2: Interpreted-mode check.** Start Pure IDE Light (see the architecture doc), create a scratch file under `/core_relational_duckdb/relational/m2mSql/` in package `meta::relational::tests::sqlQueryToString::duckDB::…`, and run one example per task through `meta::legend::executeLegendQuery` and `planToString`. Every one must execute and print. Delete the scratch file afterwards.
- [ ] **Step 3: Docs.** Update the translated/rejected lists, the parity table (every case and its status), and Section 9 to the phase-4 list below.
- [ ] **Step 4: Commit** `M2M SQL phase 3: docs and verification`.

---

## Task dependencies

```
Task 1 (conditionals/booleans) — independent; Task 3 and 8 reuse its lambda-argument handling
Task 2 (null-safe negation)    — independent
Task 3 (strings)               — after Task 1 (if/branch lambdas)
Task 4 (typed collections)     — independent
Task 5 (to-many complex)       — after Task 4 (collection leaf rendering)
Task 6 (Float)                 — after Task 4 (collection element types)
Task 7 (dates)                 — independent
Task 8 (collection operations) — after Tasks 1 and 4
Task 9 (below the root)        — after Task 1
Task 10 (sweep/docs)           — last
```

Suggested order: 1, 2, 3, 4, 5, 6, 7, 8, 9, 10.

## Deferred to phase 4 (explicitly not here)

Union (`OperationSetImplementation`, `UNION ALL`, different node shape), merge, model chains as CTE layers, subtype graph fetch trees, local mapping properties, checked/`IChecked` results and translating (not just gating on) constraints, Decimal (needs ingestion that keeps numbers as text), a generated-SQL size and nesting-depth guard, plan-time fallback-reason telemetry (protocol addition), TDS-over-M2M, cross-store. Phase 5: metamodel-level outer query and warehouse dialects through `SqlModelConnection.engine`.
