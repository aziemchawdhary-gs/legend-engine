# M2M Execution via SQL (DuckDB) — Design

**Date:** 2026-08-17
**Status:** Approved design, pending implementation plan
**Author:** Aziem Chawdhary (with Claude)

## 1. Goal and motivation

Replace the Java-generation execution path for model-to-model (M2M) transforms and
queries with SQL execution, using embedded DuckDB as the local engine. Motivations
(all four, confirmed):

1. **Kill the Java codegen burden.** The Pure→Java expression lowering
   (`legend-engine-xt-javaPlatformBinding-pure`, `planConventions/*`) has silent
   coverage holes — a function absent from both the coder table and the prohibited
   list fails only at generation time (see
   `docs/superpowers/specs/2026-04-20-java-generation-map-pair-support-design.md`) —
   and has produced a stream of correctness bugs (variable shadowing #5027, null-opt
   return #5013, collection casts #5010).
2. **Performance / scale.** Vectorized columnar execution and real push-down. Today
   the plain `ModelConnection` path has *no* push-down: all filtering happens
   in-JVM, object by object (`docs/engineering/architecture/domain-concepts.md`,
   M2M section).
3. **Run where the data lives.** The end-state translation must be dialect-general
   so M2M can eventually execute inside warehouses (Snowflake, Databricks) as pure
   SQL, replacing the Snowflake M2M Java-UDF approach
   (`legend-engine-xt-snowflake-m2mudf-plan-executor`), which ships the whole Java
   engine into the database.
4. **Simplify the runtime.** Remove runtime JIT Java compilation
   (`EngineJavaCompiler` via `javax.tools`) from the M2M execution path:
   classloader churn, security surface, cold-start cost.

### Engine choice

DuckDB, confirmed. Evidence from this repo: DuckDB has the fewest PCT exclusions of
any dialect (263 total, only 2 in the RelationFunctions scope, vs H2 502,
Postgres 517), is fully in-process and in-memory by default (`jdbc:duckdb:` with
empty path, zero setup, no server), ships in the default extension collection, is
already the local compute engine for DataCube/REPL, and implements the
semi-structured dialect slot (`semiStructuredElementProcessor`, `->` navigation,
`unnest`, lambda processors, `to_json`/`json_object` dynaFns, `Variant` → `JSON`
column type). Alternatives considered: H2 (much weaker JSON support, more PCT
exclusions), Apache DataFusion/Calcite (entirely new integration, none of the
existing dialect work applies). DuckDB is the local/default engine; the design is
dialect-general so warehouses slot in later.

## 2. Architecture summary

Three existing subsystems are connected; one new capability is built:

1. **Source side (existing pieces, new wiring):** the JSON/object input is loaded
   into a temp table in embedded DuckDB with a single JSON (variant) column, one
   row per root source instance. A relational `Database` and a *synthetic
   semi-structured relational mapping* for the source classes are generated at plan
   time — each source property maps to variant navigation over the column.
2. **Transform (existing, reused):** with the source relationalized, the user's M2M
   mapping is exactly the m2m2r shape
   (`core_relational/relational/modelToModelToRelational/`) that the router's
   chained-mapping machinery already collapses into a single SQL statement.
   Property lambdas are lowered by the existing `pureToSQLQuery` + dialect
   extension stack — the PCT-tested layer — instead of the Java `planConventions`
   lowering.
3. **Output side (new):** a JSON-construction projection shaped by the target
   graphFetch tree is appended to the query, so the database emits the serialized
   result documents directly. A new execution node runs the SQL and streams the
   JSON out. No generated Java anywhere in the plan.

Rollout is **explicit opt-in via a new connection element** (Section 3). When a
query uses that connection but contains a construct the SQL path cannot yet
translate, planning **falls back to the existing Java path for the whole query**
(Section 6) — results are always correct, and structured fallback telemetry drives
the coverage roadmap toward eventually retiring the Java path.

### Data flow

```
Query: SourceClass.all()->graphFetch(#{...}#)->serialize(#{...}#)
  Router → M2M cluster → modelStoreContract.planGraphFetchExecution
    ├─ connection is SqlModelConnection?
    ├─ canPlanM2MSqlExecution(...)  — translatability whitelist
    │     └─ untranslatable → existing Java path + M2M_SQL_PLAN_FALLBACK log event
    ├─ calculateSourceTree(targetTree, mapping)         (existing)
    ├─ synthesize Database + semi-structured source mapping over JSON column
    ├─ chained-mapping collapse via pureToSQLQuery      (existing m2m2r mechanism)
    ├─ append JSON-construction projection from target tree
    └─ SQLGraphSerializeExecutionNode { ingest DDL+load, SQL text, engine conn }
  Runtime: create in-memory DuckDB → load input → execute one SQL statement
           → stream JSON-document column as JsonStreamingResult → response
```

## 3. User-facing model: `SqlModelConnection`

A new connection element in the ModelStore family, engine-neutral by design:

```
SqlModelConnection
{
  class: demo::SourcePerson;
  input: url ('file:/...') | data ('...json...');   // same shapes as JsonModelConnection
  engine: <optional reference to a RelationalDatabaseConnection>;
           // absent ⇒ embedded in-memory DuckDB, per-execution lifecycle
}
```

- Grammar + composer changes with a round-trip test (repo convention).
- Protocol POJO added **additively to both the current `v1_*_0` package and the
  versioned package** (protocol-versioning convention: no new version package for
  additive changes).
- Compiler extension builds the Pure metamodel class
  (`meta::external::store::model::SqlModelConnection extends PureModelConnection`).
- The `engine` field is the warehouse path: same element, different engine
  connection, no model rewrite.

Adoption requires a model change (using the connection), so no existing runtime
changes behavior on upgrade.

## 4. Source side: JSON into the engine

**No shredding.** The source is represented as one temp table per root source
class, single JSON column, one row per root instance:

```sql
CREATE TEMP TABLE m2m_src_0 (data JSON);
```

- **Ingestion:** a load step owned by the new execution node, following the
  `CreateAndPopulateTempTableExecutionNode` pattern (DuckDB already implements
  `createAndLoadTempTable`). For `url` inputs, `read_json` directly; for inline
  `data` or streamed input, insert rows (a top-level JSON array is unnested to one
  row per element at load time). DuckDB's `read_json_auto` is currently unused
  anywhere in the repo; this is its introduction.
- **Synthetic source mapping:** generated in Pure at plan time. Each source class
  property required by the calculated source tree maps to semi-structured
  navigation over `data` (`extractFromSemiStructured` / dialect `->`); nested
  complex properties remain navigation paths; collections use
  `SemiStructuredArrayFlatten`. The `calculateSourceTree` output
  (`core/pure/graphFetch/graphExtension.pure`) determines exactly which
  classes/properties the synthetic mapping must cover.
- **Lifecycle:** per-execution in-memory DuckDB database — create, load, query,
  close. Nothing persists between executions.
- **Dialect generality:** the semi-structured machinery is already implemented by
  DuckDB, Snowflake, Databricks, MemSQL, Postgres and H2 dialects, and the core
  Pure support (`pureToSQLQuery_variant.pure`) is dialect-agnostic.

A consequence of one-document-per-row: each output root derives from exactly one
input row, so generated SQL is predominantly row-local scalar expressions — no
correlated subqueries for nested structure — which keeps it fast and portable.

## 5. Transform: reuse of the Pure→SQL stack

| Feature | Translation | Mechanism |
|---|---|---|
| Property lambdas | SQL expressions | existing `pureToSQLQuery` lowering (replaces Java `planConventions`) |
| Class `~filter` + root `filter()` | `WHERE` | existing; upgrades plain-connection M2M from zero push-down to full push-down |
| Enum mappings | `CASE` | reuse `chain.pure` lowering of transformers to nested if/else (`createNestedIfElseLambdaFunction`), rendered by pureToSQLQuery |
| Explosion (`*` mappings) | `unnest` / `LATERAL FLATTEN` | existing `SemiStructuredArrayFlatten` dialect slot |
| Union (`OperationSetImplementation`) | `UNION ALL` (+ set-id tagging for per-branch subtrees) | existing relational set unions |
| Merge | join of branch subqueries; validation lambda → join predicate; per-property `COALESCE`/priority | new composition of existing pieces; **riskiest item**, lands last in the increment; untranslatable validation lambdas fall back |
| Auto-mapping / pass-through | identity navigation into the variant | trivial in this design |

**Deferred (falls back by design in increment 1):** `ModelChainConnection`
multi-hop (future: one CTE layer per hop), `graphFetchChecked`/constraints (defect
rows are representable in SQL but `IChecked` envelope semantics need their own
design pass), cross-store `XStorePropertyMapping`, milestoning (already ignored in
pure M2M — commit 74093cc4885), units/measures.

## 6. Translatability check and fallback

`canPlanM2MSqlExecution(setImpl, tree, extensions): M2MSqlTranslatability`, invoked
at the top of the contract's planning dispatch. Returns *translatable* or a
*reason* (construct + `SourceInformation`). Checks in order:

1. Connection is `SqlModelConnection` (otherwise: normal path, not a fallback).
2. Query shape is graphFetch → serialize (increment 1 scope).
3. Mapping features are within the supported set (Section 5 table).
4. Every property lambda passes a **whitelist walk** against the target dialect's
   supported-function registry — a positive supportability check, not
   try-and-catch, so rejection is cheap and reportable.

On *untranslatable*: plan exactly as today (Java path) and emit a structured log
event — new `LoggingEventType.M2M_SQL_PLAN_FALLBACK` with the reason. The check is
conservative-by-whitelist: a construct is SQL-planned only when positively known.
This inverts the Java lowering's failure mode (silent holes) into explicit,
telemetered gaps.

## 7. Output: SQL emits the JSON; execution without javac

- **JSON-construction projection**, shaped by the *target* graphFetch tree:
  `json_object('name', <expr>, 'addresses', <list-expr>, ...)` per root row,
  nesting via the dialect's JSON/array constructors (DuckDB `json_object`,
  `list_transform` — already dynaFns; Snowflake `OBJECT_CONSTRUCT`/`ARRAY_AGG`
  later). Missing constructors are added as dynaFns — additive, PCT-testable
  dialect work.
- **New plan node `SQLGraphSerializeExecutionNode`** (protocol POJO in both
  protocol packages, Pure node class, Java executor registered through the
  relational store's execution state, which already owns connection acquisition
  and temp-table management — no new store type). It owns the whole M2M cluster: runs ingestion
  (DDL + load), executes the single generated SQL statement over the engine
  connection, streams the JSON-document column as a `JsonStreamingResult` (the
  result type the Snowflake M2M UDF path already uses) directly into the response.
- **Plan properties:** serializable and reproducible — contains temp-table DDL,
  load step, and SQL text; inspectable in the plan viewer; far more debuggable
  than generated Java classes.
- **Result-shape scope:** increment 1 targets graphFetch→serialize. TDS-over-M2M —
  currently *asserted impossible* on a plain `ModelConnection` — becomes a cheap
  follow-on since the transform is already relational (Phase 3).
- **Error handling:** SQL failures wrap into `EngineException(EXECUTION)` carrying
  the generated SQL and the mapping element's `SourceInformation`; malformed input
  JSON surfaces as a load-step error with row context, mirroring
  `DataParsingException` semantics.

## 8. Testing strategy

1. **Phase-0 validation:** add the missing DuckDB semi-structured test collection
   (`core_relational_duckdb_pct/testSemistructured.pure`, mirroring the ~26-line
   H2/Snowflake/Databricks files) so the dialect support this design builds on is
   executed in CI first.
2. **Parity harness (centerpiece):** JUnit 5 suite that takes the existing M2M
   legend test corpus (`core/store/m2m/tests/legend/**`,
   `TestM2MGrammarCompileAndExecute` cases) and re-runs each with the connection
   swapped to `SqlModelConnection`, asserting:
   - the plan contains no Java-platform nodes when translation is expected;
   - results are `JsonUnit.assertJsonEquals`-identical to the Java path;
   - expected-fallback cases fall back with the expected reason.
   An **expected-fallback manifest** (PCT-exclusions-style JSON) makes coverage
   growth visible and reviewable per PR.
3. **Unit layers:** grammar round-trip; compiler tests; Pure tests asserting the
   synthetic database/mapping generation and generated SQL strings (relational
   test convention); Java executor tests for the new node; one translatability
   test per rejection reason.
4. **Dialect-generality guard:** generate-SQL-only tests against the Snowflake
   dialect for the same corpus (no execution — matches existing Snowflake feature
   testing), keeping the warehouse end-state honest from day one.

## 9. Phasing

| Phase | Deliverable |
|---|---|
| 0 | DuckDB semi-structured test collection; missing JSON-construction dynaFns |
| 1 | Vertical slice: connection element, synthetic source DB + mapping, ingestion, chained-mapping collapse for simple PISI mappings, JSON projection, `SQLGraphSerializeExecutionNode`, fallback wiring + logging, parity harness bootstrapped |
| 2 | Coverage: enum mappings, explosion, auto-mapping, filter push-down, union |
| 3 | Merge; TDS-over-M2M follow-on |
| 4+ | Chains as CTE layers; checked/constraints; cross-store; warehouse execution (Snowflake/Databricks via the `engine` field); telemetry-driven coverage growth until Java fallback rates justify deprecating the Java M2M path |

Every phase ships independently behind the connection element; existing users are
untouched until they adopt it.

## 10. Rejected alternatives

- **Direct M2M→SQL compiler** (bespoke planner emitting SQL, bypassing
  `pureToSQLQuery`): fastest first demo, but re-implements expression lowering and
  dialect handling — recreating in a new place exactly the maintenance problem
  (silent coverage holes) this project exists to remove.
- **Reuse relational graphFetch nodes over DuckDB:** least new code, but relational
  graphFetch assembles objects in *generated Java*
  (`IRelational*GraphFetchExecutionNodeSpecifics`), failing the codegen-removal
  and runtime-simplification goals.
- **Scalar-UDF escape hatch for untranslatable lambdas:** only works for embedded
  DuckDB (not warehouses) and reintroduces JVM↔SQL coupling; whole-query fallback
  chosen instead.
- **Hybrid SQL+JVM plans:** maximum push-down but results assembled across two
  engines with a much more complex planner; deferred indefinitely in favor of
  whole-query fallback.
- **Shredding JSON into normalized typed tables:** more "relational" but requires
  key synthesis, multi-table loads, and join-based reassembly; the variant-column
  design keeps ingestion trivial and SQL row-local.
- **H2 / DataFusion / Calcite as the engine:** see Section 1, Engine choice.

## 11. Key references

- `docs/engineering/architecture/domain-concepts.md` — M2M section, three
  execution paths.
- `docs/engineering/architecture/source-tree-calculation.md` —
  `calculateSourceTree`, the plan-time input to the synthetic source mapping.
- `docs/engineering/architecture/key-pure-areas.md` §4, §7; `key-java-areas.md`
  §7; `execution-plans.md` §4.2, §5.3.
- `core/store/m2m/storeContract.pure`, `core/pure/mapping/modelToModel.pure` —
  planning entry points to be extended.
- `core_relational/relational/modelToModelToRelational/` and
  `core/pure/router/store/routing.pure` (chained mappings) — the existing M2M→SQL
  collapse being generalized.
- `core_relational/relational/pureToSQLQuery/pureToSQLQuery_variant.pure` +
  `core_relational_duckdb/relational/sqlQueryToString/duckdbExtension.pure` —
  semi-structured machinery.
- `StreamResultToTempTableVisitor` / `DuckDBCommands.createAndLoadTempTable` —
  ingestion precedent.
- `legend-engine-xt-snowflake-m2mudf-plan-executor` — the Java-in-DB precedent
  this eventually replaces; source of `JsonStreamingResult` usage.
