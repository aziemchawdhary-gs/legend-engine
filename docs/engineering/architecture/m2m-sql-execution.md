# M2M Execution via SQL (DuckDB)

> **Related docs:**
> [Domain Concepts — M2M Mapping](domain-concepts.md#model-to-model-m2m-mapping) |
> [Execution Plans](execution-plans.md) | [Router & Pure-to-SQL](router-and-pure-to-sql.md) |
> [Source Tree Calculation](source-tree-calculation.md) | [Key Pure Areas](key-pure-areas.md)

> **Design & plan:**
> [`docs/superpowers/specs/2026-08-17-m2m-sql-execution-design.md`](../../superpowers/specs/2026-08-17-m2m-sql-execution-design.md) (spec) |
> [`docs/superpowers/plans/2026-08-17-m2m-sql-execution-phase1.md`](../../superpowers/plans/2026-08-17-m2m-sql-execution-phase1.md) (phase 0–1 implementation plan) |
> [`docs/superpowers/plans/2026-09-03-m2m-sql-execution-phase2.md`](../../superpowers/plans/2026-09-03-m2m-sql-execution-phase2.md) (phase 2 implementation plan) |
> [`docs/superpowers/plans/2026-11-04-m2m-sql-execution-phase3.md`](../../superpowers/plans/2026-11-04-m2m-sql-execution-phase3.md) (phase 3 implementation plan) — the plans are authoritative where they and the spec differ

> **Status:** Phases 0–3 implemented (opt-in). Everything below describes what ships today. What
> does not exist yet is listed in the [roadmap](#9-roadmap-phase-4-and-later) at the end of this
> document.

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
Phase 2 widens the whitelist to the common mapping shapes — mapping and root filters,
enumerations and enumeration mappings, explode mappings — without changing that structure.
Phase 3 adds conditionals and Boolean values, string functions, `Float` and `StrictDate`, typed
primitive collections, to-many complex properties, and computed, enumeration-mapped and filtered
nested objects, with root filters through them. Anything the whitelist does not recognize falls back to the Java path unchanged — the SQL path
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
5. The global node's tree is a `RoutedRootGraphFetchTree` whose target tree structurally matches
   the `serialize` call's own tree (`sameTreeShape` — compares class, property names/aliases,
   and recursion, because the two trees print differently: one is routed, one is a plain
   literal). Properties are matched by name, not position, and one difference is tolerated at the
   root level only: properties the fetch tree has and the serialize tree does not. The router adds
   what a root `filter()` reads to the fetch tree (a leaf, or a complex property with the leaves
   read through it) and moves it to the end. The SQL document is projected from the **serialize**
   tree for the same reason. A property left out of the document is only harmless when it has no
   effect beyond its own value, so after guard 9 the serialize tree is gated on its own
   (`m2mSqlSerializeTreeRejection`): an explode mapping it does not select, or an omitted
   enumeration-mapped property at any depth of an omitted subtree, is a fallback.
6. The root set implementation is exactly one `PureInstanceSetImplementation`, and none of the
   target-tree classes — or their superclasses — carry constraints (constraint checking is not
   yet translated). Skipped when the reader was built with `enableConstraints = false`: the Java
   path checks nothing then either.
7. The local graph fetch node is a plain `InMemoryRootGraphFetchExecutionNode` (not
   `InMemoryCrossStoreGraphFetchExecutionNode`) with `checked == false`. A root `filter` on it is
   allowed only when `rewriteTargetFilterToSource` can re-express it over the source (Section 5);
   that check runs after guard 9.
8. It reads from exactly one `StoreStreamReadingExecutionNode`, whose `connection` is a
   `SqlModelConnection` and which is itself unchecked.
9. `canPlanM2MSql(setImpl, targetTree, mapping)` (Section 5) returns `translatable = true`.
10. The source tree the inner query reads (`m2mSqlSourceTree`: what the transforms need plus what
    the root filter reads) only has properties the synthetic store can extract
    (`m2mSqlSourceTreeRejection`): a supported primitive or enumeration type, and a name that is a
    plain identifier.
11. None of the **source** classes, or their superclasses, carry constraints
    (`m2mSqlSourceConstraintRejection`) — again only when the reader has `enableConstraints`. The mirror of guard 6: on the Java path the source
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

Translated (phase 3 additions marked):

- Primitive property transforms built from `$src`, primitive literals, property accesses and the
  functions in `sqlTranslatableFunctionWhitelist()`; auto-mapped primitive properties; to-one
  complex properties whose transform is a plain source navigation.
- *(phase 3)* **Conditionals and Boolean values**: `if` (its condition may compare and combine like a
  filter; both branches are lambdas checked like any transform), `isEmpty`/`isNotEmpty`, and a
  Boolean-typed leaf computed from `sqlTranslatableFilterFunctions()` (`isBoss: $src.title ==
  'Boss'`). Every equality with a side that may be empty is spelled out null-safely (see Section 6),
  so an absent operand gives the Java path's `false`, not SQL `NULL`.
- *(phase 3)* **String functions**: `toUpper`, `toLower`, `length`, `replace`, `startsWith`,
  `endsWith`, `contains` and `toString`.
- *(phase 3)* **Scalar types**: `String`, `Integer` (extracted as 64-bit `BIGINT`), `Float`
  (`DOUBLE`), `Boolean`, `StrictDate` (`DATE`, printed `yyyy-MM-dd` on both paths) and enumerations.
- *(phase 3)* **Primitive collections** of `String`, `Integer`, `Float` and `Boolean`, mapped straight
  from a to-many source property of the same element type. An absent source array serializes as
  `[]`, as on the Java path.
- *(phase 3)* **To-many complex properties** (`addresses: $src.addresses`), including a to-many
  complex property inside an element, each element projected through DuckDB `list_transform`. Inside
  an element only plain navigations are translated.
- *(phase 3)* **Below the root, on a to-one nested set**: computed leaves, enumeration transformers and
  the nested set's own `~filter` (an object failing it is left out, its parent kept).
- A mapping-level `~filter` on the **root** set implementation that passes the whitelist extended with
  `sqlTranslatableFilterFunctions()` (`and`, `or`, `not`, `equal` and the numeric comparisons). It
  becomes a `WHERE` in the inner query. *(phase 3)* Negations of compound conditions are translated.
- A root `filter()` on the query that `rewriteTargetFilterToSource` can rewrite: each target
  property read is replaced by its source transform. *(phase 3)* The read may navigate through to-one
  complex properties (`$p.address.city`): each level's plain-navigation transform is composed
  (`navigateTarget`), including the `map(v_automap | ...)` form Pure compiles a property read on an
  optional receiver to.
- **Enumeration-typed** source properties, explicit or auto-mapped: extracted as `VARCHAR`, the
  enum's name on both sides.
- An `EnumerationMapping` **transformer** whose source values are of the transform's own type:
  values of its enumeration, strings or integers. It becomes a `CASE` in the outer document.
- A single **explode** property mapping on the root set, when the tree selects it as a leaf and its
  transform is `$src.<property>` for a to-many `String`, `Integer` or *(phase 3)* `Float` property.
  It becomes an `unnest` in the inner query.

Rejected (fall back to Java):

- Any function off the whitelist. `substring` in particular: DuckDB's rendering is 1-based with a
  length, Pure's is 0-based with an end index (pinned by `stringFunctionsUnsupported`).
- **Operations on a source collection** (`filter`, `map`, `size`, `exists`, `joinStrings`, aggregates)
  in a transform, and a mapping `~filter` or rewritten root filter that reads one. A collection is
  extracted as the text of its JSON array, which the relational stack treats as one scalar per row,
  so none of these would have Java semantics (phase 4: an element-level translator).
- An equality with a to-many side (`pureToSQLQuery` asserts on it).
- `Decimal` (DuckDB's JSON reader parses it as a double), `DateTime` (the Java path echoes the input's
  own sub-second precision, which no SQL timestamp format reproduces), `Number`, `Date` and
  `StrictTime`, whether serialized or only read by a transform or filter. So does a source property
  whose name is not a plain identifier.
- A collection whose source is not a to-many property of its own element type, and a to-one leaf
  whose transform reads a source collection (`$src.names->toOne()`).
- Inside an element of a to-many complex property: a computed leaf, an enumeration transformer or a
  nested `~filter`.
- A to-many complex property populated from a to-one source.
- `mappingClass` or any `localMappingProperty`; `subTypeTrees` anywhere in the target tree.
- A property with no `PurePropertyMapping` that does not resolve via genuine auto-mapping
  (`isPropertyAutoMapped`), and any complex property with no property mapping (the Java path leaves
  it empty).
- An enumeration **literal** (`Colour.RED`) anywhere in a transform or filter.
- A root `filter()` that reads through a to-many property, through a nested set with its own
  `~filter`, or a property with an enumeration transformer or an explode mapping; any variable other
  than the lambda's own parameter (query parameters included).
- An explode mapping on a nested set, more than one on a set, one the tree does not select as a leaf,
  or one whose transform is anything but a single source navigation to a to-many `String`,
  `Integer` or `Float` property.
- A complex property's transform that is not a **plain source-navigation chain**
  (`isPlainSourceNavigation`: `$src.address`, `$src.home.address`), and one mapped to the bare
  source object (`address: $src`), whose JSON path is empty. A transform that navigates through an
  optional source object (`city: $src.address.city`, compiled as `map`) is in this group too.

**Extending the whitelist**: to translate a new function, add it to
`sqlTranslatableFunctionWhitelist()`. It lists exact signatures, not a type×operator matrix:
`plus` over `String`, `Integer`, `Float` and `Number`; `minus` over `Number`; `times` over `Integer`
and `Number`; `divide` over `Number`; `toOne`; `if`; `isEmpty`/`isNotEmpty` over `[*]` and `[0..1]`;
and the string functions listed above. Filters, `if` conditions and Boolean leaves additionally admit
`sqlTranslatableFilterFunctions()`: `and`, `or`, `not`, `equal`, `lessThan`, `lessThanEqual`,
`greaterThan` and `greaterThanEqual` (the comparisons over `Number` only). The entries are exactly
what the parity corpus and the Pure unit tests exercise; extend only when a new translatable parity
case demands it, and add that case to the parity harness rather than the fallback manifest. To
translate a new mapping shape, add a case to `untranslatableReasons`/`rootLevelReasons` and to
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
2. **`jsonProjection.pure`**'s `buildM2MSqlQuery(setImpl, targetTree, rootFilters, connection,
   extensions, debug): M2MSqlQuery { setupSqls: String[*]; sqlQuery: String[1]; }` walks the target graph
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

**Filters.** The mapping's `~filter` and the rewritten root `filter()` (`rootFilters`) are both
boolean expressions over the root `$src`. `buildProjectExpression` applies one `->filter(...)` per
entry between `getAll` and `project`, and `pureToSQLQuery` renders them as the inner query's
`WHERE` over the semi-structured extracts. A root filter can read source properties nothing in the
target tree maps from, so the source tree the synthetic store is built from is first widened with
the filter's requirements (`ensureFunctionRequirementsFromLambda`).

**Enumeration mappings.** The inner query projects the transform's raw result. The outer document
wraps that column in a `CASE` (`m2mSqlEnumCaseReference`): one arm per source value (an
enumeration value is compared by name), `null` for an absent value, and a terminal `error(...)`
(the `sqlError` dynaFn) for a value the mapping has no entry for. DuckDB evaluates `error` only
for a row that reaches it, so this mirrors the Java path's hard failure on an unmatched value.

**Explode.** The exploded leaf is not projected. It is injected into the inner `SELECT`, through
the same post-processor hook as the presence columns (`withInjectedColumns`), as
`unnest(cast(json_extract(data, '$.<path>') as <element type>[]))` (the `unnestJsonArray` dynaFn).
That yields one row — and so one document — per element, repeats the sibling columns, and yields
no row for an empty or absent array. When the exploded leaf is all the tree selects, a constant
`m2m__row` column is projected so `project` has something to produce; the document never refers
to it.

**Null-safe equality (phase 3).** SQL `=` is `NULL` for an absent operand, and `NOT NULL` is not the
`true` the Java path evaluates for `!($x == 'a')`. `nullSafeFilter` rewrites every equality whose side
may be empty (`mayBeEmpty`: a lower bound of 0, or a property read through a possibly-empty base) as
`if(isEmpty(a) || isEmpty(b), | isEmpty(a) && isEmpty(b), | a == b)`. It is applied to the mapping and
root filters, to nested filters, and to every Boolean leaf and `if` condition.

**Collections (phase 3).** A to-many primitive leaf is extracted as the text of its JSON array
(`VARCHAR`, which turns a JSON `null` into SQL `NULL`) and placed in the document as
`coalesce(cast(x as JSON), cast('[]' as JSON))`. A typed `TYPE[]` extract would make the relational
stack flatten rows; a `SEMISTRUCTURED` one would leave the loader's JSON `null` visible.

**To-many complex properties (phase 3).** Not projected through the inner query. An
`M2MSqlArrayColumn` is injected next to the presence columns, and the outer document builds each
element with `m2mElementArray(textColumn, variable, elementObject)`:
`coalesce(to_json(list_transform(cast(cast(x as JSON) as JSON[]), e -> <element object>)), cast('[]'
as JSON))`. `buildElementObject` builds the element object from the nested tree over the lambda
variable, recursing for a to-many property inside an element.

**Nested filters (phase 3).** A nested to-one set's `~filter`, substituted over the parent's source
expression and made null-safe, becomes a Boolean column `<path>__m2mFilter` in the inner query. The
nested object's presence check is then `presence AND filter`.

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
  { "case": "multiplicityCoercion",       "reason": "collection property aliases is not mapped from a to-many source property of its own type" },
  { "case": "unmappedComplex",            "reason": "unmapped complex property not yet translated: address" },
  { "case": "explodeNarrowSerialize",     "reason": "explode property mapping is not selected as a leaf of the graph fetch tree" },
  { "case": "stringFunctionsUnsupported", "reason": "unsupported transform for property sub: unsupported function in transform: substring" },
  { "case": "toManyComplexComputed",      "reason": "computed property street inside a to-many complex property not yet translated" },
  { "case": "dateTimes",                  "reason": "property type not yet translated: seen" },
  { "case": "collectionFilter",           "reason": "unsupported mapping filter: the filter reads a source collection" }
]
```

This mirrors the PCT-exclusions-manifest convention already used elsewhere in the repo
(`docs/pct/expected-failures-howto.md`): the manifest makes the current coverage boundary
explicit and reviewable, and growing coverage is a two-line diff — implement the new
translation, delete the manifest entry, move the corpus case's assertions from "expect
fallback" to "expect collapse + parity".

The corpus as of phase 3 (46 cases, 39 collapsed):

| Case | Exercises | Status |
|---|---|---|
| `simpleRename` | string concatenation transform | collapsed |
| `computedPrimitive` | integer arithmetic | collapsed |
| `largeInteger` | an `Integer` above 2^31-1, extracted and incremented | collapsed |
| `nestedComplexToOne` | nested to-one object, absent object and absent optional leaf | collapsed |
| `nestedScalars` | `String`, `Integer` and `Boolean` leaves of a nested object | collapsed |
| `autoMapped` | property with no mapping | collapsed |
| `primitiveCollection` | `String[*]` leaf | collapsed |
| `stringCollectionShapes` | `String[*]` with duplicates, empty and absent arrays | collapsed |
| `integerCollection` | `Integer[*]` with an element above 2^31-1, empty and absent arrays | collapsed |
| `booleanCollection` | `Boolean[*]` leaf | collapsed |
| `multiplicityCoercion` | collection from a scalar, scalar from a collection | fallback |
| `mappingFilter` | boolean `~filter` | collapsed |
| `comparisonFilter` | `>=`, `==`, `!`, `&&` in a `~filter` | collapsed |
| `optionalEqualityFilter` | `==` in a `~filter` against an absent optional value | collapsed |
| `negatedCompoundFilter` | `!(a == x && b == y)` with an absent optional value | collapsed |
| `negatedOrFilter` | `!(a == x \|\| b == y)` with an absent optional value | collapsed |
| `collectionFilter` | a `~filter` reading a source collection | fallback |
| `booleanTransform` | a comparison as a property value, absent operand | collapsed |
| `negatedBooleanTransform` | a negated compound condition as a property value | collapsed |
| `conditionalTransform` | `if` and `isNotEmpty` in transforms | collapsed |
| `stringFunctions` | `toUpper`, `toLower`, `length`, `replace`, `startsWith`, `endsWith`, `contains`, `toString`; non-ASCII and negative inputs | collapsed |
| `stringFunctionsUnsupported` | `substring` | fallback |
| `floatValues` | `Float` pass-through, division, `Float[*]`, `-0.0` and long fractions | collapsed |
| `floatExplode` | explode of a `Float[*]` | collapsed |
| `strictDates` | `StrictDate` leaf | collapsed |
| `dateTimes` | `DateTime` leaf | fallback |
| `unmappedComplex` | complex property with no property mapping (Java leaves it `null`) | fallback |
| `toManyComplex` | to-many complex property, empty and absent arrays | collapsed |
| `toManyComplexNested` | to-many complex property inside an element | collapsed |
| `toManyComplexComputed` | computed leaf inside an element | fallback |
| `nestedComputedAndEnum` | computed leaf and enumeration mapping on a nested to-one set | collapsed |
| `nestedMappingFilter` | a nested set's own `~filter` | collapsed |
| `rootFilter` | root `filter()` over a computed property and an unfetched one | collapsed |
| `rootFilterDeepNavigation` | root `filter()` through a complex property the tree also fetches | collapsed |
| `rootFilterNestedNavigation` | root `filter()` through an optional complex property the tree does not fetch | collapsed |
| `rootFilterNestedNegated` | negated root `filter()` through an absent optional object | collapsed |
| `enumIdentity` | enumeration pass-through, absent value | collapsed |
| `enumTransform` | enumeration mapping: several source values per target, absent value | collapsed |
| `enumTransformUnmatched` | unmapped source value: both paths fail | collapsed |
| `explodeMapping` | explode, exploded leaf only | collapsed |
| `explodeMappingEmpty` | explode of an empty array: no documents | collapsed |
| `explodeMappingSibling` | explode with a sibling property, empty array between two non-empty | collapsed |
| `explodeInteger` | explode of an `Integer[*]`, one element above 2^31-1 | collapsed |
| `explodeComposite` | explode with a `~filter`, an absent array and a nested object | collapsed |
| `explodeCompositeRootFilter` | the same with a root `filter()` | collapsed |
| `explodeNarrowSerialize` | exploded property fetched but not serialized | fallback |

Two behaviours the corpus pins that are worth knowing: an enumeration source value with no entry
in the enumeration mapping is an error on both paths, and an empty or absent exploded collection
produces no document for that source row on both paths.

## 9. Roadmap (phase 4 and later)

Deferred, each currently falling back safely to the Java path via the Section 5 whitelist (see
also spec Section 9 and the phase-3 plan's "Deferred to phase 4" section):

- **Collection operations** (`filter`, `map`, `size`, `exists`, `joinStrings`, aggregates over a
  source collection) and filters reading collections: needs an element-level translator emitting
  DuckDB `list_filter`/`list_transform`/`len`/`list_aggregate` over the parsed array, outside
  `pureToSQLQuery`. The same translator would lift the restrictions inside to-many complex elements
  (computed leaves, enumeration mappings, nested filters).
- **Navigation through optional source objects in transforms** (`city: $src.address.city`, which Pure
  compiles as `map`).
- **Union** (`OperationSetImplementation`): `UNION ALL` with per-branch set-id tagging, a different
  node shape.
- **Merge**: join of branch subqueries, validation lambda → join predicate, per-property
  `COALESCE`/priority — flagged in the spec as the riskiest item.
- **Model chains as CTE layers**, **subtype graph fetch trees**, **local mapping properties**.
- **Checked/constraints**: `IChecked` envelope semantics, and translating (not just gating on)
  constraints. Both target- and source-class constraints are *guarded* today (Section 4).
- **Decimal**: needs ingestion that keeps numbers as text. **DateTime**: needs a rendering of the
  input's own precision.
- **Enumeration literals**, **query parameters** in filters, `substring`, equality over collections.
- **A generated-SQL size and nesting-depth guard**: everything collapses to one statement today.
- **Plan-time fallback reason telemetry**: the rejection *reason* is still only printed under
  `debug.debug`; carrying it into the plan needs a protocol addition. The execution-time signal is
  the structured `M2M_SQL_JAVA_FALLBACK_EXECUTED` event (source class and URL scheme only).
- **TDS-over-M2M** and **cross-store** (`XStorePropertyMapping`).
- **Server-config-driven extension wiring**: `duckDBM2MSqlExtension` is registered by SPI whenever
  the DuckDB module is on the classpath.
- Phase 5: a **metamodel-level outer query** instead of the current string-wrap (today
  `buildM2MSqlQuery` assembles the outer JSON-projection `SELECT` as SQL text around the inner
  `pureToSQLQuery` output), then **warehouse dialects** via the reserved `SqlModelConnection.engine`
  field — Snowflake `OBJECT_CONSTRUCT`/`ARRAY_AGG` in place of DuckDB `json_object`/`json_array`,
  replacing the Snowflake M2M Java-UDF path.
