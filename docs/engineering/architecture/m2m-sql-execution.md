# M2M Execution via SQL (DuckDB)

> **Related docs:**
> [Domain Concepts — M2M Mapping](domain-concepts.md#model-to-model-m2m-mapping) |
> [Execution Plans](execution-plans.md) | [Router & Pure-to-SQL](router-and-pure-to-sql.md) |
> [Source Tree Calculation](source-tree-calculation.md) | [Key Pure Areas](key-pure-areas.md)

> **Design & plan:**
> [`docs/superpowers/specs/2026-08-17-m2m-sql-execution-design.md`](../../superpowers/specs/2026-08-17-m2m-sql-execution-design.md) (spec) |
> [`docs/superpowers/plans/2026-08-17-m2m-sql-execution-phase1.md`](../../superpowers/plans/2026-08-17-m2m-sql-execution-phase1.md) (phase 0–1 implementation plan, authoritative where it and the spec differ)

> **Status:** Phase 0–1 implemented (vertical slice, opt-in). Everything below describes what
> ships today. The spec's Section 5/9 phasing table and the plan's "Deferred" section describe
> what does not exist yet — see [Phase-2 roadmap](#phase-2-roadmap) at the end of this document.

---

## 1. Motivation

Model-to-model (M2M) mappings historically have three execution paths (see
[Domain Concepts — M2M Mapping](domain-concepts.md#model-to-model-m2m-mapping)), and the plain
`ModelConnection` path — object-graph-in, object-graph-out over JVM-resident instances — has no
push-down at all: filtering, projection, everything happens in-JVM, object by object, after the
mapping lambda is lowered to generated Java bytecode (`legend-engine-xt-javaPlatformBinding-pure`).
That Java lowering has silent coverage holes (a function absent from both the coder table and the
prohibited list fails only at plan-generation time) and has produced a steady stream of
correctness bugs.

This work adds a fourth path: an M2M graph-fetch query over a new opt-in connection element
executes as a single generated SQL statement against embedded DuckDB instead of generated Java.
Full rationale — engine choice, alternatives considered, and the four confirmed motivations
(kill the Java codegen burden, performance/push-down, "run where the data lives" toward
warehouse dialects, remove runtime `javac`) — is in the design spec, Sections 1 and 10.

Phase 1 is a vertical slice: one connection element, one query shape
(`ClassName.all()->graphFetch(#{...}#)->serialize(#{...}#)`), a conservative translatability
whitelist, and a parity harness proving byte-identical output against the existing Java path.
Anything the whitelist does not recognize falls back to the Java path unchanged — the SQL path
is additive and never changes behavior for existing models.

## 2. `SqlModelConnection`

A new connection element, engine-neutral by design:

```pure
###Connection
SqlModelConnection demo::MySqlConnection
{
  class: demo::SourcePerson;
  url: 'my_url';
}
```

- Protocol POJO: `org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.modelToModel.connection.SqlModelConnection`
  (`class` field is `_class` in Java, following the repo's reserved-word convention), added
  additively to both the current `v1_*_0` package and the `vX_X_X` package — an additive
  protocol change goes into both, not a new version sub-package.
- Compiler extension builds the Pure metamodel class
  `meta::external::store::model::SqlModelConnection extends PureModelConnection`, with `url`
  carried through to plan time.
- Grammar + composer round-trip through the existing `ModelConnection` grammar section
  (`class`/`url` fields mirror `JsonModelConnection`/`XmlModelConnection`).
- No `engine` field yet — phase 1 always executes against an embedded, per-execution,
  in-memory DuckDB instance. The design reserves an `engine` field (a reference to a
  `RelationalDatabaseConnection`) for warehouse dialects in a later phase; today the target
  connection used by the collapse is hard-coded by the registering `Extension` (see below), not
  read from the model.
- Adoption requires an explicit model change (swapping the connection element), so no existing
  runtime changes behavior on upgrade.

## 3. The seam: `Extension.postProcessExecutionPlan`

The spec originally proposed hooking into the M2M store contract's own planning dispatch
(`storeContract.pure`). That does not work: `contract::supports`
(`core/store/m2m/storeContract.pure:57-61`) explicitly excludes `serialize` from the M2M store
cluster, so `serialize` is never routed to the M2M contract in the first place — it is planned
as a `PureExpressionPlatformExecutionNode` (a "platform" node, executed as generated Java)
wrapping whatever the M2M contract produced for the graph fetch underneath it. There is no
contract-level seam that sees the shape this design needs to rewrite.

Instead, the collapse is implemented as a **whole-plan rewrite** via a new field on
`meta::pure::extension::Extension`:

```pure
postProcessExecutionPlan : Function<{ExecutionPlan[1], Extension[*], DebugContext[1] -> ExecutionPlan[1]}>[0..1];
```

(`core/pure/extensions/extension.pure`.) Every registered `Extension` may contribute one such
function; `PlanGenerator.generateExecutionPlanAsPure` folds them over the plan at the end of
Pure's `executionPlan(...)` assembly — **after** the router, store contracts, and the M2M
in-memory/platform nodes have all run, but **before** `PlatformBinder.bindPlan` generates any
Java for the nodes the collapse is about to remove. This ordering was confirmed against
`PlanGenerator.java:101-165`: `executionPlan(...)` runs first, `platform.bindPlan(...)` is a
separate, later step.

The DuckDB module registers the M2M collapse through this hook:

```pure
function meta::relational::m2mSql::duckDB::duckDBM2MSqlExtension():Extension[1]
{
  ^Extension(
     type = 'm2mSqlDuckDB',
     postProcessExecutionPlan = m2mSqlPlanPostProcessor(
        ^RelationalDatabaseConnection(
           type = DatabaseType.DuckDB,
           datasourceSpecification = ^DuckDBDatasourceSpecification(path = ''),
           authenticationStrategy = ^TestDatabaseAuthenticationStrategy())));
}
```

registered via SPI (`DuckDBM2MSqlPureCoreExtension`, `META-INF/services/...LegendPureCoreExtension`)
so any server that loads the DuckDB extension picks it up automatically. This is a deliberately
wide blast radius — the postprocessor walks *every* plan generated with the extension active,
not just plans that use `SqlModelConnection` — but every guard below fails closed, so it is
provably inert on any plan that doesn't match the exact shape this phase targets.

**Classpath asymmetry.** The registration lives in the DuckDB *plan-generation* module, so a
deployment carrying that module will collapse matching plans whether or not it can run the result:
any such server must also carry the relational *execution* module and the DuckDB JDBC driver, or the
collapsed `SQLGraphSerializeExecutionNode` will fail at execution time with no fallback left.

A rewriting `postProcessExecutionPlan` hook is also responsible for the plan-level aggregates
derived from the node tree, which are computed before the hook runs. `authDependent` and `kerberos`
are recomputed from the post-processed tree by `executionPlan(...)` itself;
`processingTemplateFunctions` is not (a stale support function is inert, whereas a stale
`authDependent` would change how the plan is executed). See the field's own comment in
`extension.pure`.

## 4. The collapse: `collapse.pure`

Entry point: `meta::relational::m2mSql::m2mSqlPlanPostProcessor`
(`core_relational/relational/modelToModelToRelational/m2mSql/collapse.pure`). It recurses over
the plan tree (`collapseNode`), descending through `SequenceExecutionNode` (the only wrapper the
plan funnel puts above a `serialize` node) and attempting the match at each
`PureExpressionPlatformExecutionNode`.

**Every guard is a fallback, never a failure**: if any check does not hold, the node is returned
unchanged (debug-mode logs the reason) and the plan runs on the Java path exactly as it would
without this extension registered. The checks, in the order `m2mSqlCollapseRejection` /
`m2mSqlGlobalNodeRejection` / `m2mSqlLocalNodeRejection` apply them:

1. The platform node's expression is a call to the one whitelisted `serialize` overload
   (`serialize_T_MANY__RootGraphFetchTree_1__String_1_` — the checked and
   `AlloySerializationConfig` overloads are excluded because they change the emitted document
   shape).
2. **Curtailed-serialize tripwire**: the `serialize` expression itself must carry exactly its two
   parameters, with the first (the graph-fetch collection) already an empty placeholder — the
   marker that `serializeFunctionProcessor` substitutes for the sub-expression it moved into the
   child node. If the residual expression carries anything else, the collapse would silently
   drop it, so this is checked explicitly rather than assumed.
3. The platform node wraps exactly one child, and it is a `StoreMappingGlobalGraphFetchExecutionNode`.
4. The global node has no cross-store children (`children`/`xStorePropertyMapping` empty) — the
   whole M2M cluster must be a single store.
5. The global node's tree is a `RoutedRootGraphFetchTree` whose target tree structurally equals
   the `serialize` call's own tree (`sameTreeShape` — compares class, property names/aliases,
   and recursion, because the two trees print differently: one is routed, one is a plain
   literal).
6. The root set implementation is exactly one `PureInstanceSetImplementation`, and none of the
   target-tree classes carry constraints (constraint checking is not yet translated).
7. The local graph fetch node is a plain `InMemoryRootGraphFetchExecutionNode` (not
   `InMemoryCrossStoreGraphFetchExecutionNode`), with no `filter` and `checked == false`.
8. It reads from exactly one `StoreStreamReadingExecutionNode`, whose `connection` is a
   `SqlModelConnection` and which is itself unchecked.
9. `canPlanM2MSql(setImpl, targetTree, mapping)` (Section 5) returns `translatable = true`.
10. None of the **source** classes carry constraints
    (`m2mSqlSourceConstraintRejection`). The mirror of guard 6: on the Java path the source
    document is deserialized into real source instances and the stream reader enforces their
    classes' constraints, but the SQL path never materializes a source object, so the checks would
    silently stop running. The source classes are the classes of `calculateSourceTree(targetTree,
    mapping, extensions)` — the same tree `buildM2MSqlQuery` synthesizes the source store from —
    which covers the root `srcClass`, every nested set implementation's `srcClass`, and any
    intermediate class a transform navigates through. Checked last, so it only ever runs on a shape
    the collapse would otherwise have accepted.

On success, `buildM2MSqlQuery` (Section 6) builds the SQL and the whole subtree — platform node,
global graph fetch node, in-memory node, store-stream-reading node — is replaced by a single
`meta::relational::m2mSql::SQLGraphSerializeExecutionNode`.

## 5. Translatability whitelist: `translatability.pure`

`canPlanM2MSql(setImpl, targetTree, mapping): M2MSqlTranslatability` is the gate the collapse
consults last. It is a **conservative whitelist**: a construct is treated as SQL-translatable
only when it is positively recognized, never by trying-and-catching a failure. Anything not
recognized returns a `reason` string and the plan falls back.

Rejected (fall back to Java):

- A mapping-level `~filter` on the root `PureInstanceSetImplementation` (`~filter` push-down is
  phase 2).
- `mappingClass` or any `localMappingProperty` (local mapping properties not yet translated).
- `subTypeTrees` anywhere in the target tree (subtype graph fetch not yet translated).
- A to-many **complex** property (`propertyTree.subTrees` non-empty and the property's
  multiplicity upper bound is not one) — the flat single-row JSON projection cannot express one
  object per source element; this must be rejected here rather than in `jsonProjection.pure`,
  which asserts instead of falling back.
- A property with no `PurePropertyMapping` that also does not resolve via genuine
  auto-mapping (`isPropertyAutoMapped` — same-name/type pass-through, not merely an omitted
  mapping).
- An **enumeration-typed** source property, whether reached through an explicit transform or
  through auto-mapping — the synthetic source store's semi-structured extraction only knows
  primitive types and `fail`s hard (not a graceful rejection) on an `Enumeration`, so this must
  be caught before synthesis runs.
- Any `transformer` on a property mapping (enum transformers — `CASE` lowering is phase 2).
- `explodeProperty == true` (explosion via `unnest` is phase 2).
- A complex property's transform that is not a **plain source-navigation chain**
  (`isPlainSourceNavigation`: `$src`, `$src.address`, `$src.home.address` — a bare variable or a
  chain of `Property` accesses on one, nothing else, e.g. not `$src.address->toOne()`). The JSON
  projection derives the nested object's presence check from this expression as a JSON path, so
  anything else (even a call in the arithmetic/string whitelist) would crash the projection
  rather than degrade gracefully.
- A complex property mapped to the bare source object (`address: $src`). It *is* a plain source
  navigation, but its JSON path is empty, which is no path at all: the presence column would read
  `'$.'` and be permanently false. Rejected explicitly rather than left to the projection.
- A property transform lambda expression that isn't one of: `$src` itself, a primitive literal,
  a property access on a translatable operand, or a call to a function in
  `sqlTranslatableFunctionWhitelist()` on translatable operands
  (`explainUntranslatableExpression`).

**Extending the whitelist**: to translate a new function, add it to
`sqlTranslatableFunctionWhitelist()` — currently exactly nine signatures, not a full
type×operator matrix: `plus` over `String`, `Integer`, `Float`, and `Number`; `minus` over
`Number` only; `times` over `Integer` and `Number`; `divide` over `Number` only (fixed
`Number[1], Number[1] -> Float[1]` arity); and `toOne`. The entries so far are exactly what the
parity corpus proves safe; extend only when a new translatable parity case demands it, and add
that case to the parity harness rather than the fallback manifest. To translate a new mapping
shape (e.g. `~filter`), add a case to `untranslatableReasons`/`rootLevelReasons` and to
`m2mSqlLocalNodeRejection`/`m2mSqlGlobalNodeRejection` in `collapse.pure` if the shape also needs
a structural guard, then implement the corresponding SQL emission in `jsonProjection.pure` /
`sourceSynthesis.pure`.

## 6. Query construction: `sourceSynthesis.pure` + `jsonProjection.pure`

Two pieces, both existing machinery newly wired together rather than new SQL-generation code:

1. **`sourceSynthesis.pure`** builds a synthetic relational `Database` and a semi-structured
   `PureInstanceSetImplementation`-shaped source mapping over one temp table
   (`m2m_src_0 (data JSON)`, one row per root source instance, unqualified — the `default` schema
   is elided in emitted SQL). Each source property required by the target tree maps to
   semi-structured navigation over `data` (`extractFromSemiStructured` / dialect `->`), reusing
   the same dialect slot Snowflake/Databricks/Postgres/H2/DuckDB already implement for
   `Variant`/JSON columns — no new SQL-generation surface for the *source* side.
2. **`jsonProjection.pure`**'s `buildM2MSqlQuery(setImpl, targetTree, connection, extensions,
   debug): M2MSqlQuery { setupSqls: String[*]; sqlQuery: String[1]; }` walks the target graph
   fetch tree (`flattenTargetTree`), inlining each property mapping's transform lambda (composed
   over nested transforms by substituting the parent's expression for the nested lambda's `$src`)
   into an inner `project(...)` against the synthetic source mapping — this is the same
   chained-mapping (m2m2r) collapse machinery the router already uses for
   `ModelChainConnection`-over-relational mappings, so **property-lambda lowering reuses the
   PCT-tested `pureToSQLQuery` stack**, not a new expression lowering. The inner query's columns
   are wrapped in an outer `SELECT` that assembles the JSON document with DuckDB's
   `json_object`/`json_array` dynaFns (added to the DuckDB dynaFn registry as part of this work).

**Null-parity fix.** The Java M2M serializer emits explicit `null`s for present-but-null leaves,
but an *absent* nested complex object needs to disappear from the document entirely, not appear
as `{"street": null}`. The inner projection alone cannot distinguish "object present, all
selected leaves null" from "object absent" — both flatten to SQL `NULL` columns. The fix is a
**presence column per nested complex property**
(`M2MSqlPresenceColumn { columnName; sourcePath }`, injected via the synthetic connection's
`sqlQueryPostProcessors` hook): `json_extract_string(data, '<sourcePath>') is not null`, and the
outer projection wraps the nested `json_object(...)` call in a `CASE` on that column so an absent
source object serializes as SQL `NULL` (omitted key) rather than a populated-but-null object.

## 7. Runtime: `SQLGraphSerializeExecutionNode`

Protocol node (`_type: "sqlGraphSerialize"`,
`org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.SQLGraphSerializeExecutionNode`):

```java
public class SQLGraphSerializeExecutionNode extends ExecutionNode
{
    public DatabaseConnection connection;
    public List<String> setupSqls = Collections.emptyList();
    public String sqlQuery;
    public String url;
}
```

Executed by `M2MSqlGraphSerializeHelper.execute` (registered through
`RelationalExecutionExtension`, reusing the relational store's connection acquisition — no new
store type):

1. Materializes `url` to a local temp file (`Files.createTempFile`), copying the stream in —
   works uniformly for `file:` and `data:` URLs (the production shape, where the URL body *is*
   the full input document).
2. Runs each `setupSqls` entry on one JDBC connection, substituting the literal
   `{{M2M_INPUT_PATH}}` token with the temp file's absolute, single-quote-escaped path (the
   token must appear inside single quotes in the setup SQL).
3. Runs `sqlQuery` on the same connection and streams the single `"doc"` column as a
   `JsonStreamingResult`, matching the Java M2M serializer's own multiplicity envelope exactly:
   a **bare object** for a one-row result, a **JSON array** for N rows (`[]` for zero rows) —
   verified live against the Java path's actual output, not assumed.
4. **Logging**: only the URL *scheme* is ever logged (`M2MSqlLoggingEventType`), never the URL
   itself — a `data:` URL's body is the whole input document, so logging it would leak user data.
5. **Cleanup**: a full close cascade (result set → statement → connection → delete temp file) on
   both the success path (wrapped into the streamed `Result`'s `close()`) and any exception path
   (`finally` block), each step individually try/caught and logged rather than let one failure
   mask another.
6. Registered in both middle-tier plan-execution authorizer selectors (the node carries a
   connection and must be recognized by authorization exactly like a relational node).

The node is entirely self-contained and serializable: setup DDL/load SQL and the query text are
both plain strings on the plan, so a generated plan is as inspectable and debuggable in the plan
viewer as any relational plan — no generated Java class to decompile.

## 8. Parity harness and `expected-fallbacks.json`

`TestM2MSqlParity` (`legend-engine-xt-relationalStore-duckdb-PCT`) is the coverage-growth
mechanism: it runs the same M2M model/mapping/query grammar through a `JsonModelConnection`
(Java path) and a `SqlModelConnection` (SQL path, with `duckDBM2MSqlExtension` registered) and
asserts:

- For a case expected to translate: the SQL-path plan contains a `sqlGraphSerialize` node and no
  leftover Java-platform implementation, **and** the two paths' serialized JSON output is
  byte-identical (`JsonUnit.assertJsonEquals`).
- For a case expected to fall back: the SQL-path plan still contains the Java in-memory
  graph-fetch nodes (proving the collapse correctly declined), and the case is listed in
  `src/test/resources/m2msql/expected-fallbacks.json` with the exact rejection reason string:

```json
[
  { "case": "mappingFilter",   "reason": "mapping filter not yet translated" },
  { "case": "enumTransform",   "reason": "enumeration transformer not yet translated" },
  { "case": "explodeMapping",  "reason": "explode property mapping not yet translated" }
]
```

This mirrors the PCT-exclusions-manifest convention already used elsewhere in the repo
(`docs/pct/expected-failures-howto.md`): the manifest makes the current coverage boundary
explicit and reviewable, and growing coverage is a two-line diff — implement the new
translation, delete the manifest entry, move the corpus case's assertions from "expect
fallback" to "expect collapse + parity". As of phase 1, 5 of the 8 parity-corpus cases collapse
and pass byte-parity; the remaining 3 are exactly the manifest's three entries.

## 9. Phase-2 roadmap

Deferred, each currently falling back safely to the Java path via the Section 5 whitelist (see
also spec Section 9 and the plan's "Deferred to the Phase-2 plan" section):

- **Filter push-down**: mapping `~filter` and root `filter()` — the plain `ModelConnection` path
  has zero push-down today, so this is the biggest single win; upgrades the whitelist and adds a
  `WHERE` clause to the inner query.
- **Enumeration mappings**: lower a property mapping's `transformer` to `CASE`, reusing
  `chain.pure`'s existing `createNestedIfElseLambdaFunction` lowering of enum transformers,
  rendered through `pureToSQLQuery` like everything else.
- **Explosion** (`explodeProperty` / `*` mappings): via the `SemiStructuredArrayFlatten` dialect
  slot (`unnest` / `LATERAL FLATTEN`), already implemented for source-side navigation.
- **Union** (`OperationSetImplementation`): `UNION ALL` with per-branch set-id tagging — existing
  relational set-union machinery.
- **To-many complex properties**: needs a JSON-aggregation projection (one object per source
  element via `unnest` + array aggregation), not the current flat single-row shape — depends on
  explosion support.
- **Merge**: join of branch subqueries, validation lambda → join predicate, per-property
  `COALESCE`/priority — flagged in the spec as the riskiest item, intentionally last.
- **Metamodel-level outer query** instead of the current string-wrap: today `buildM2MSqlQuery`
  assembles the outer JSON-projection `SELECT` as SQL text around the inner `pureToSQLQuery`
  output; a metamodel-level `SQLQuery` AST composition (matching how `pureToSQLQuery` itself
  works) would make the outer query subject to the same dialect abstraction as the inner one —
  needed before warehouse dialects, since string-wrapped SQL is DuckDB-syntax-specific.
- **Warehouse dialects** via the reserved `SqlModelConnection.engine` field (a
  `RelationalDatabaseConnection` reference) — Snowflake `OBJECT_CONSTRUCT`/`ARRAY_AGG` in place
  of DuckDB `json_object`/`json_array`, replacing the Snowflake M2M Java-UDF path
  (`legend-engine-xt-snowflake-m2mudf-plan-executor`).
- **Chains as CTE layers**, **checked/constraints** (`IChecked` envelope semantics), **cross-store**
  (`XStorePropertyMapping`), **TDS-over-M2M** (cheap follow-on once the transform is already
  relational — currently *asserted impossible* on a plain `ModelConnection`). On constraints
  specifically: the collapse today only guards *target*-class constraints (Section 4, guard 6);
  the equivalent guard for the *source* class is still missing —
  `StoreStreamReadingExecutionNode.enableConstraints` is not yet consulted, so a constrained
  source class silently loses its check on the SQL path (known gap, tracked for phase 2).
- **Plan-time fallback telemetry**: the design spec's `LoggingEventType.M2M_SQL_PLAN_FALLBACK`
  structured log event does not exist yet — phase 1 only prints the rejection reason under
  `debug.debug`, via `print(...)`, not a structured/queryable log line.
- **Server-config-driven extension wiring**: today `duckDBM2MSqlExtension` is unconditionally
  registered by SPI whenever the DuckDB module is on the classpath; making the collapse
  opt-in/out per server deployment (beyond "does the model use `SqlModelConnection`") is not
  built.
