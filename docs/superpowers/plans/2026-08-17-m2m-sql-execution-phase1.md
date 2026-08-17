# M2M SQL Execution (Phase 0 + Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the vertical slice of SQL-based M2M execution: a new `SqlModelConnection`, DuckDB validation groundwork, and a plan-rewrite path that turns `graphFetch→serialize` over a simple M2M mapping into one DuckDB SQL statement emitting JSON — with automatic whole-query fallback to the existing Java path.

**Architecture:** The existing Java-generation plan is always built first (that *is* the fallback). A new `Extension.postProcessExecutionPlan` hook, applied at the end of Pure plan assembly (before platform binding), pattern-matches `serialize → StoreMappingGlobalGraphFetch → InMemoryRoot(StoreStreamReading with SqlModelConnection)` and — when a conservative translatability whitelist passes — replaces that subtree with a new `SQLGraphSerializeExecutionNode` that loads the JSON input into embedded in-memory DuckDB and runs a single generated SQL statement whose one column is the serialized JSON document. Because the rewrite happens before platform binding, collapsed plans contain no generated Java at all.

**Tech Stack:** Java 11, Maven, Pure (compiled mode), DuckDB 1.3.0 (embedded, `org.duckdb:duckdb_jdbc`), ANTLR4 grammar, Jackson, JUnit 5 (new tests) / JUnit 3-4 suite pattern (only where mirroring existing PCT runners).

**Spec:** `docs/superpowers/specs/2026-08-17-m2m-sql-execution-design.md` — read it first; this plan implements its Phases 0–1. Where this plan deviates from the spec (the seam moved from the store contract to a plan post-processor because `contract::supports` excludes `serialize` — verified at `core/store/m2m/storeContract.pure:58-62`), this plan is authoritative.

## Global Constraints

- **JDK 11 required for every mvn command:** `export JAVA_HOME=/home/aziem/jdk11/jdk-11.0.29+7` first, always.
- **Always pass `clean`** to mvn for modules containing Pure plugins (stale target dirs cause "duplicate artifact present" failures).
- Build one module: `mvn clean install -DskipTests -pl <module-path> -am` from repo root. Run tests: `mvn clean test -pl <module-path> -Dtest=ClassName`.
- Every new file (including `.pure`) needs the Apache 2.0 copyright header (Checkstyle-enforced). Copy the header verbatim from any 2026-dated file, e.g. the Databricks `testSemistructured.pure`.
- Indentation: Java 4 spaces, Pure 2 spaces. Braces always, opening brace on its own line. No tabs.
- SLF4J only, never `System.out.println`. New log event types: module-local enum implementing `ILoggingEventType` (the shared `LoggingEventType` enum is `@Deprecated`).
- Grammar changes must update parser AND composer, with a round-trip test.
- JSON comparison in tests: `JsonUnit.assertJsonEquals`, never `String.equals`.
- Pure protocol changes are additive to BOTH `core/pure/protocol/vX_X_X/` and the newest versioned package (`v1_33_0`). Never a new version package. The Java protocol has a single (unversioned `v1`) location.
- Pure test functions in `core_relational_duckdb` must live in packages matching that repo's `definition.json` pattern; the DuckDB PCT package prefix is `meta::relational::tests::pct::duckDB` (capital `DB`).
- Work on a feature branch (use superpowers:using-git-worktrees at execution start). Commit at the end of every task.

**Module path abbreviations used below** (all relative to repo root):

| Alias | Path |
|---|---|
| `CORE-PURE` | `legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core` |
| `PROTOCOL` | `legend-engine-core/legend-engine-core-base/legend-engine-core-language-pure/legend-engine-protocol-pure` |
| `GRAMMAR` | `legend-engine-core/legend-engine-core-base/legend-engine-core-language-pure/legend-engine-language-pure-grammar` |
| `COMPILER` | `legend-engine-core/legend-engine-core-base/legend-engine-core-language-pure/legend-engine-language-pure-compiler` |
| `REL-CORE-PURE` | `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure` |
| `REL-PROTOCOL` | `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-protocol` |
| `REL-EXEC` | `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-execution/legend-engine-xt-relationalStore-executionPlan` |
| `DUCKDB-PURE` | `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-dbExtension/legend-engine-xt-relationalStore-duckdb/legend-engine-xt-relationalStore-duckdb-pure` |
| `DUCKDB-PCT` | `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-dbExtension/legend-engine-xt-relationalStore-duckdb/legend-engine-xt-relationalStore-duckdb-PCT` |
| `JAVA-BINDING-PURE` | `legend-engine-xts-java/legend-engine-xt-javaPlatformBinding-pure` |

---

### Task 1: DuckDB semi-structured test collection (Phase 0 validation)

The whole design builds on DuckDB's semi-structured dialect support, which currently has **no executed test collection** (H2/Snowflake/Databricks do). Add it first so CI proves the foundation.

**Files:**
- Create: `DUCKDB-PCT/src/main/resources/core_relational_duckdb_pct/testSemistructured.pure`
- Create: `DUCKDB-PCT/src/test/java/org/finos/legend/engine/plan/execution/stores/relational/test/duckdb/semistructured/Test_Relational_DuckDB_Semistructured.java`
- Modify: `DUCKDB-PCT/pom.xml`

**Interfaces:**
- Consumes: `meta::pure::test::collectParameterizedTests` (`CORE-PURE/src/main/resources/core/pure/test/testCollection.pure:35`), `meta::pure::testConnection::getTestConnection(DatabaseType.DuckDB)` (already registered via `DuckDBTestConnectionIntegration`), the `meta::relational::tests::semistructured` corpus in `REL-CORE-PURE`.
- Produces: a green (with documented expected failures) DuckDB semi-structured CI suite; the list of expected failures informs Task 9's whitelist.

- [ ] **Step 1: Add the missing test-scoped pom dependencies**

The H2 PCT pom (`legend-engine-xt-relationalStore-h2-PCT/pom.xml` lines 328–342) has three test deps the DuckDB PCT pom lacks; the semi-structured tests load `.legend` models that need them. Add to `DUCKDB-PCT/pom.xml`:

```xml
        <dependency>
            <groupId>org.finos.legend.engine</groupId>
            <artifactId>legend-engine-external-format-grammar</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.finos.legend.engine</groupId>
            <artifactId>legend-engine-external-format-compiler</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.finos.legend.engine</groupId>
            <artifactId>legend-engine-xt-json-model</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Write the Pure test collection**

Create `core_relational_duckdb_pct/testSemistructured.pure` (copy the Apache header from the Databricks equivalent, 2026 copyright). The package MUST be `meta::relational::tests::pct::duckDB::...` — the repo's `core_relational_duckdb_pct.definition.json` pattern is `(meta::relational::tests::pct::duckDB)(::.*)?`:

```pure
import meta::relational::runtime::*;

function <<meta::pure::profiles::test.TestCollection>> meta::relational::tests::pct::duckDB::semistructured::testCollection(): meta::pure::test::PureTestCollection[1]
{
  meta::relational::tests::semistructured->meta::pure::test::collectParameterizedTests(
    'duckdb',
    meta::pure::testConnection::getTestConnection(DatabaseType.DuckDB),
    [],
    []
  );
}
```

- [ ] **Step 3: Write the Java runner**

Create `Test_Relational_DuckDB_Semistructured.java` by copying `Test_Relational_H2_Semistructured.java` (path in H2-PCT: `src/test/java/org/finos/legend/engine/plan/execution/stores/relational/test/h2/semistructured/`) and adapting: package `...test.duckdb.semistructured`, `testPackage = "meta::relational::tests::pct::duckDB::semistructured"`, server resource selected with `c.getDatabaseType() == DatabaseType.DuckDB`, and start with `SKIPPED_TESTS = Sets.mutable.<String>empty()`.

- [ ] **Step 4: Run the suite and expect failures**

```bash
export JAVA_HOME=/home/aziem/jdk11/jdk-11.0.29+7
mvn clean install -DskipTests -pl <DUCKDB-PCT> -am -T 4
mvn test -pl <DUCKDB-PCT> -Dtest=Test_Relational_DuckDB_Semistructured
```
Expected: suite runs; some tests fail (unproven dialect surface).

- [ ] **Step 5: Triage every failure**

For each failing test, decide: (a) genuine DuckDB dialect bug worth a small fix in `duckdbExtension.pure` (fix it only if <10 lines and obvious, e.g. the known missing-space bug in `processExtractFromSemiStructuredParamsFoDuckDb`'s INTEGER branch, `duckdbExtension.pure:360`); or (b) legitimately unsupported → add to the runner using the Snowflake expected-failure-map style (`Test_Relational_Snowflake_Semistructured.java` uses `Maps.mutable.with(fqn, expectedError)` and asserts the message) rather than bare skips, so failures stay informative. Re-run until green.

- [ ] **Step 6: Commit**

```bash
git add <DUCKDB-PCT>
git commit -m "Add DuckDB semi-structured parameterized test collection"
```

---

### Task 2: DuckDB JSON-construction dynaFns (`jsonObject`, `jsonArray`)

The projection SQL (Task 11) needs dialect functions to build JSON documents. DuckDB today has `toVariant`→`to_json` and `toVariantObject`→`MAP {}` but nothing that builds a JSON object with literal keys.

**Files:**
- Modify: `DUCKDB-PURE/src/main/resources/core_relational_duckdb/relational/sqlQueryToString/duckdbExtension.pure` (dynaFn registry starts line 172; JSON block lines 287–295)
- Modify (only if the run in Step 3 demands it): the registered-dynaFn-name list near `REL-CORE-PURE/src/main/resources/core_relational/relational/sqlQueryToString/dbExtension.pure:1162`
- Test: `DUCKDB-PURE/src/main/resources/core_relational_duckdb/relational/sqlQueryToString/tests/testDuckDBSQLGeneration.pure` (existing file — append)

**Interfaces:**
- Produces: DynaFunction names `jsonObject` (params: alternating key-literal, value-expression) and `jsonArray` (params: element expressions), rendering to DuckDB `json_object(...)` / `json_array(...)`. Task 11 constructs `^DynaFunction(name='jsonObject', parameters=[...])` trees against these names.

- [ ] **Step 1: Write the failing tests**

Read the top of `testDuckDBSQLGeneration.pure` to get its package and test-function style; append two tests in the same package:

```pure
function <<test.Test>> <pkg>::testJsonObjectDynaFn():Boolean[1]
{
  let alias = ^TableAlias(name = 'root', relationalElement = ^Table(name = 'm2m_src_0', schema = ^Schema(name = 'default', database = ^Database(name = 'm2m_db'))));
  let col = ^TableAliasColumn(alias = $alias, column = ^Column(name = 'data', type = ^meta::relational::metamodel::datatype::SemiStructured()));
  let f = ^DynaFunction(name = 'jsonObject', parameters = [^Literal(value = 'name'), $col]);
  let sql = $f->meta::relational::functions::sqlQueryToString::processOperation(DatabaseType.DuckDB, meta::relational::extension::relationalExtensions());
  assertEquals('json_object(\'name\', "root".data)', $sql);
}

function <<test.Test>> <pkg>::testJsonArrayDynaFn():Boolean[1]
{
  let f = ^DynaFunction(name = 'jsonArray', parameters = [^Literal(value = 1), ^Literal(value = 2)]);
  let sql = $f->meta::relational::functions::sqlQueryToString::processOperation(DatabaseType.DuckDB, meta::relational::extension::relationalExtensions());
  assertEquals('json_array(1, 2)', $sql);
}
```

(`processOperation(RelationalOperationElement[1], DatabaseType[1], Extension[*]): String[1]` is at `dbExtension.pure:433`.)

- [ ] **Step 2: Run to verify failure**

```bash
mvn clean test -pl <DUCKDB-PURE> -Dtest=Test_Pure_Relational_DuckDB
```
Expected: FAIL — no dynaFn processor for `jsonObject`. If instead an assertion about an *unregistered dynaFunction name* fires, note the file/line it points at — that is the registry to extend in Step 3.

- [ ] **Step 3: Implement the dynaFns**

In `duckdbExtension.pure`, inside `getDynaFunctionToSqlForDuckDB()` next to the JSON block (after line 295):

```pure
    dynaFnToSql('jsonObject',             $allStates,            ^ToSql(format='json_object(%s)', transform={p:String[*] | $p->joinStrings(', ')})),
    dynaFnToSql('jsonArray',              $allStates,            ^ToSql(format='json_array(%s)', transform={p:String[*] | $p->joinStrings(', ')})),
```

If Step 2 revealed a name registry, add both names there too. Adjust the expected strings in the tests to the actual literal-processor output if the failure diff shows different string quoting.

- [ ] **Step 4: Run to verify pass, then commit**

```bash
mvn clean test -pl <DUCKDB-PURE> -Dtest=Test_Pure_Relational_DuckDB
git add <DUCKDB-PURE> <REL-CORE-PURE>
git commit -m "Add jsonObject/jsonArray dynaFns for DuckDB"
```

---

### Task 3: `SqlModelConnection` — Pure metamodel and Pure protocol

The Pure class must exist before the Java compiler visitor (Task 4), because `ConnectionFirstPassBuilder` references the generated `Root_meta_external_store_model_SqlModelConnection_Impl`.

**Files:**
- Modify: `CORE-PURE/src/main/resources/core/pure/mapping/modelToModel.pure` (class at ~line 62, after `JsonModelConnection`; match branches at lines 182–188, 280–290, 388–398)
- Modify: `CORE-PURE/src/main/resources/core/pure/runtime/runtimeExtension.pure:105-114` (`isPureM2MRuntime`)
- Modify: `CORE-PURE/src/main/resources/core/pure/protocol/vX_X_X/models/dsl/mapping.pure` (~line 265) and `.../vX_X_X/transfers/store.pure` (~lines 43, 63)
- Modify: `CORE-PURE/src/main/resources/core/pure/protocol/v1_33_0/models/metamodel.pure` (~line 804) and `.../v1_33_0/transfers/store.pure` (~lines 44, 64)

**Interfaces:**
- Produces: `Class meta::external::store::model::SqlModelConnection extends PureModelConnection { class: Class<Any>[1]; url: String[1]; }`. Generated Java impl class name: `Root_meta_external_store_model_SqlModelConnection_Impl` (consumed by Task 4). Grammar/protocol `_type` name: `SqlModelConnection` (consumed by Tasks 4–5).

- [ ] **Step 1: Add the Pure class**

In `modelToModel.pure` directly after `JsonModelConnection` (line 62):

```pure
// SQL-engine-backed model connection: source documents are loaded into an embedded
// relational engine and the M2M transform executes as SQL when translatable
// (falls back to the standard Java path otherwise)
Class meta::external::store::model::SqlModelConnection extends PureModelConnection
{
   class: Class<Any>[1];
   url  : String[1];
}
```

- [ ] **Step 2: Add the dispatch branches**

Three `match` lists in `modelToModel.pure` must gain a branch (Pure `match` fails hard on unmatched types):

1. `modelToModelConnectionToString` (lines 182–188): add
   `smc:SqlModelConnection[1] | 'url = ' + $smc.url + ', class = ' + $smc.class->elementToPath(),`
2. Connection selection in `planRootGraphFetchExecutionInMemory` (lines 280–290): add
   `s : SqlModelConnection[1]  | $s.class == $sourceClass,`
3. The twin selection in `planCrossStoreGraphFetchExecutionInMemory` (lines 388–398): same branch.

In `runtimeExtension.pure` `isPureM2MRuntime`, add `|| $c->instanceOf(meta::external::store::model::SqlModelConnection)` alongside the JsonModelConnection line.

- [ ] **Step 3: Add the Pure protocol metamodel + transforms (both packages)**

In `vX_X_X/models/dsl/mapping.pure` after `JsonModelConnection` (line 265):

```pure
Class meta::protocols::pure::vX_X_X::metamodel::mapping::modelToModel::SqlModelConnection extends meta::protocols::pure::vX_X_X::metamodel::runtime::Connection
{
   class : String[1];
   url   : String[1];
}
```

In `vX_X_X/transfers/store.pure`: add to the `transformConnection` match list (line 43 area):
`s:meta::external::store::model::SqlModelConnection[1] | $s->meta::protocols::pure::vX_X_X::transformation::fromPureGraph::connection::transformSqlModelConnection(),` and add the transform function next to `transformJsonModelConnection` (line 63):

```pure
function meta::protocols::pure::vX_X_X::transformation::fromPureGraph::connection::transformSqlModelConnection(sqlModelConnection: meta::external::store::model::SqlModelConnection[1]):meta::protocols::pure::vX_X_X::metamodel::mapping::modelToModel::SqlModelConnection[1]
{
   ^meta::protocols::pure::vX_X_X::metamodel::mapping::modelToModel::SqlModelConnection(
      element = 'ModelStore',
      _type   = 'SqlModelConnection',
      class   = $sqlModelConnection.class->elementToPath(),
      url     = $sqlModelConnection.url
   );
}
```

Repeat both edits identically in the `v1_33_0` package (metamodel at `models/metamodel.pure:804` area; transfers at `transfers/store.pure:44/64` area), with `v1_33_0` in place of `vX_X_X`.

- [ ] **Step 4: Build and commit**

```bash
mvn clean install -DskipTests -pl <CORE-PURE> -am -T 4
```
Expected: succeeds and `target/generated-sources` contains `Root_meta_external_store_model_SqlModelConnection_Impl`. Verify:
```bash
grep -rl "SqlModelConnection_Impl" <CORE-PURE>/target/generated-sources | head -1
git add <CORE-PURE>
git commit -m "Add SqlModelConnection Pure metamodel and protocol"
```

---

### Task 4: `SqlModelConnection` — Java protocol, visitors, compiler

**Files:**
- Create: `PROTOCOL/src/main/java/org/finos/legend/engine/protocol/pure/v1/model/packageableElement/store/modelToModel/connection/SqlModelConnection.java`
- Modify: `PROTOCOL/.../packageableElement/connection/Connection.java` (`@JsonSubTypes`, line 33 area)
- Modify: `PROTOCOL/.../packageableElement/connection/ConnectionVisitor.java` (line 34 area)
- Modify (add `visit` overrides): `COMPILER/.../toPureGraph/ConnectionFirstPassBuilder.java:67`, `COMPILER/.../toPureGraph/ConnectionSecondPassBuilder.java`, `legend-engine-core/legend-engine-core-testable/legend-engine-testable/src/main/java/org/finos/legend/engine/testable/connection/TestConnectionBuilder.java:96`, `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-execution/legend-engine-xt-relationalStore-executionPlan/src/main/java/org/finos/legend/engine/plan/execution/stores/relational/result/DatabaseIdentifiersCaseSensitiveVisitor.java:73`
- Test: `COMPILER/src/test/java/org/finos/legend/engine/language/pure/compiler/test/fromGrammar/TestConnectionCompilationFromGrammar.java` (grammar-based test lands in Task 5; here add a protocol-JSON compile test OR defer the test to Task 5 — see Step 4)

**Interfaces:**
- Consumes: `Root_meta_external_store_model_SqlModelConnection_Impl` (Task 3).
- Produces: `org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.modelToModel.connection.SqlModelConnection` with fields `_class` (JSON name `class`), `url`, `classSourceInformation`; Jackson `_type` = `"SqlModelConnection"`; `ConnectionVisitor.visit(SqlModelConnection)`.

- [ ] **Step 1: Create the protocol POJO** — mirror `JsonModelConnection.java` exactly:

```java
package org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.modelToModel.connection;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.finos.legend.engine.protocol.pure.m3.SourceInformation;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.connection.ConnectionVisitor;

public class SqlModelConnection extends PureModelConnection
{
    @JsonProperty("class")
    public String _class;
    public String url;
    public SourceInformation classSourceInformation;

    @Override
    public <T> T accept(ConnectionVisitor<T> connectionVisitor)
    {
        return connectionVisitor.visit(this);
    }
}
```

- [ ] **Step 2: Register subtype and visitor**

In `Connection.java` add after the `JsonModelConnection` entry:
`@JsonSubTypes.Type(value = SqlModelConnection.class, name = "SqlModelConnection"),`
In `ConnectionVisitor.java` add `T visit(SqlModelConnection sqlModelConnection);` after the JsonModelConnection line (32).

- [ ] **Step 3: Fix all visitor implementors**

Build to find them all: `mvn clean install -DskipTests -pl <PROTOCOL> -am`, then compile the four known implementors' modules. Implementations:
- `ConnectionFirstPassBuilder` (mirror the JsonModelConnection visit at lines 67–74):

```java
    @Override
    public Root_meta_core_runtime_Connection visit(SqlModelConnection sqlModelConnection)
    {
        HelperConnectionBuilder.verifyModelConnectionStore(sqlModelConnection.element, sqlModelConnection.elementSourceInformation);
        return new Root_meta_external_store_model_SqlModelConnection_Impl("", SourceInformationHelper.toM3SourceInformation(sqlModelConnection.sourceInformation), context.pureModel.getClass("meta::external::store::model::SqlModelConnection"))
                ._class(this.context.resolveClass(sqlModelConnection._class, sqlModelConnection.classSourceInformation))
                ._url(sqlModelConnection.url);
    }
```
- `ConnectionSecondPassBuilder`: return unchanged (mirror its JsonModelConnection no-op).
- `TestConnectionBuilder` and `DatabaseIdentifiersCaseSensitiveVisitor`: delegate exactly as their `visit(JsonModelConnection)` does (`this.visit((Connection) sqlModelConnection)` / the equivalent).
- Any additional implementors surfaced by compile errors: delegate to their generic `visit(Connection)` the same way.

- [ ] **Step 4: Build the compiler module and commit**

```bash
mvn clean install -DskipTests -pl <COMPILER> -am -T 4
git add -A
git commit -m "Add SqlModelConnection Java protocol, visitors, and compiler pass"
```
(The compile-error test is added with grammar in Task 5, matching how `TestConnectionCompilationFromGrammar` works.)

---

### Task 5: `SqlModelConnection` — grammar, composer, round-trip tests

Grammar is identical in shape to `JsonModelConnection` (`class:` + `url:`), so reuse the shared `ModelConnection` grammar files unchanged.

**Files:**
- Modify: `GRAMMAR/src/main/java/org/finos/legend/engine/language/pure/grammar/from/CorePureGrammarParser.java` (constant near line 87; registration lines 130–137; parse method near line 178)
- Modify: `GRAMMAR/.../from/connection/ModelConnectionParseTreeWalker.java` (new visit method after line 49)
- Modify: `GRAMMAR/.../to/HelperConnectionGrammarComposer.java:33-44` and `GRAMMAR/.../to/DEPRECATED_PureGrammarComposerCore.java:747-755`
- Test: `GRAMMAR/src/test/java/org/finos/legend/engine/language/pure/grammar/test/roundtrip/TestConnectionGrammarRoundtrip.java`, `.../parser/TestConnectionGrammarParser.java`, `COMPILER/.../fromGrammar/TestConnectionCompilationFromGrammar.java`

**Interfaces:**
- Produces: grammar form

```
SqlModelConnection meta::myConn
{
  class: model::Person;
  url: 'file:/tmp/people.json';
}
```

- [ ] **Step 1: Write the failing round-trip test** — append to `TestConnectionGrammarRoundtrip.testConnection` (or add a new `@Test testSqlModelConnection`):

```java
    @Test
    public void testSqlModelConnection()
    {
        test("###Connection\n" +
                "SqlModelConnection meta::mySqlModelConnection\n" +
                "{\n" +
                "  class: model::firm::Person;\n" +
                "  url: 'my_url';\n" +
                "}\n");
    }
```

And parser-error tests in `TestConnectionGrammarParser` mirroring `testJsonModelConnection` (lines 54–81): missing `class` → `"Field 'class' is required"`, missing `url` → `"Field 'url' is required"`.

- [ ] **Step 2: Run to verify failure**

```bash
mvn clean test -pl <GRAMMAR> -Dtest=TestConnectionGrammarRoundtrip#testSqlModelConnection
```
Expected: FAIL — no parser for connection type `SqlModelConnection`.

- [ ] **Step 3: Implement parser + walker + composer**

`CorePureGrammarParser.java`:
- `public static final String SQL_MODEL_CONNECTION_TYPE = "SqlModelConnection";` next to line 87.
- Add `ConnectionValueParser.newParser(SQL_MODEL_CONNECTION_TYPE, CorePureGrammarParser::parseSqlModelConnection)` to `getExtraConnectionParsers()`.
- `parseSqlModelConnection` mirrors `parseJsonModelConnection` (lines 170–178) with `SqlModelConnection`/`visitSqlModelConnectionValue`.

`ModelConnectionParseTreeWalker.java`: `visitSqlModelConnectionValue` — copy `visitJsonModelConnectionValue` (lines 36–49) changing only the parameter type.

`HelperConnectionGrammarComposer.java`: add the `instanceof SqlModelConnection → "SqlModelConnection"` branch. `DEPRECATED_PureGrammarComposerCore.java`: add `visit(SqlModelConnection)` mirroring the Json one (lines 747–755).

- [ ] **Step 4: Run grammar tests to green**

```bash
mvn clean test -pl <GRAMMAR> -Dtest='TestConnectionGrammar*'
```

- [ ] **Step 5: Add the compiler test** — in `TestConnectionCompilationFromGrammar`, mirror `testJsonModelConnection` (lines 42–52):

```java
    @Test
    public void testSqlModelConnection()
    {
        test("###Connection\n" +
                "SqlModelConnection test::connection\n" +
                "{\n" +
                "  class : test::someClass;" +
                "  url : 'asd';\n" +
                "}\n", "COMPILATION error at [4:11-25]: Can't find class 'test::someClass'"
        );
    }
```

Run: `mvn clean test -pl <COMPILER> -Dtest=TestConnectionCompilationFromGrammar` → PASS.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Add SqlModelConnection grammar, composer, and round-trip tests"
```

---

### Task 6: Java-path fallback — `SqlModelConnection` executes exactly like `JsonModelConnection`

This is the safety net: before ANY SQL planning exists, a query on a `SqlModelConnection` must run correctly through the existing Java path. Fallback later means "leave this plan alone".

**Files:**
- Modify: `JAVA-BINDING-PURE/src/main/resources/core_java_platform_binding/legendJavaPlatformBinding/store/m2m/m2mLegendJavaPlatformBindingExtension.pure` (lines 140–178)
- Modify: `JAVA-BINDING-PURE/.../executionPlanNodes/graphFetch/inMemory/graphFetchStoreStreamReading.pure` (matches at lines 56–61, 86–91, 100–105)
- Test: `legend-engine-core/legend-engine-core-query-pure-http-api/src/test/java/org/finos/legend/engine/query/pure/api/test/inMemory/TestM2MGrammarCompileAndExecute.java` (locate with `grep -rl TestM2MGrammarCompileAndExecute legend-engine-core` if the module path differs)

**Interfaces:**
- Consumes: `SqlModelConnection` Pure class (Task 3), grammar (Task 5), the match-branch additions in `modelToModel.pure` (Task 3 Step 2).
- Produces: end-to-end execution of `graphFetch→serialize` over a `SqlModelConnection` through `StoreStreamReadingExecutionNode` + generated Java, byte-identical to `JsonModelConnection`.

- [ ] **Step 1: Write the failing test**

In `TestM2MGrammarCompileAndExecute`, find an existing simple test that builds grammar with a `JsonModelConnection` inside a `###Connection`/Runtime block and executes a graphFetch+serialize query. Copy it as `testSimpleM2MWithSqlModelConnection`, changing only `JsonModelConnection` → `SqlModelConnection` in the grammar text. Assert the same expected JSON (`JsonUnit.assertJsonEquals` if the template uses it, else the template's own assertion).

- [ ] **Step 2: Run to verify failure**

```bash
mvn clean test -pl <that module> -Dtest=TestM2MGrammarCompileAndExecute#testSimpleM2MWithSqlModelConnection
```
Expected: FAIL in Pure plan codegen — the platform-binding `match`es don't know `SqlModelConnection`.

- [ ] **Step 3: Implement platform-binding branches**

In `m2mLegendJavaPlatformBindingExtension.pure`, add `SqlModelConnection` alongside each `JsonModelConnection` entry, treating it identically:
- `dataRecordClassFromConnectionExtractors`: `s : SqlModelConnection[1] | meta::pure::mapping::modelToModel::JsonDataRecord,`
- `dataRecordTreeFromConnectionExtractors`: `s : SqlModelConnection[1] | #{meta::pure::mapping::modelToModel::JsonDataRecord {number, record}}#,`
- `streamReaderMethodCodeGenerators`: a branch identical to the `json: JsonModelConnection[1]` one (lines 155–160) using `$s.url`.

In `graphFetchStoreStreamReading.pure`, extend the three `match`es (lines 56–61 `addForClassesWithAllProperties`, 86–91 `genReader`, 100–105 `storeReaderProject`) with a `SqlModelConnection` branch mirroring the `JsonModelConnection` one (`createJsonReading`).

- [ ] **Step 4: Run to verify pass, then commit**

```bash
mvn clean install -DskipTests -pl <JAVA-BINDING-PURE> -am -T 4
mvn clean test -pl <that module> -Dtest=TestM2MGrammarCompileAndExecute
git add -A && git commit -m "SqlModelConnection executes via existing Java M2M path (fallback baseline)"
```

---### Task 7: Parity harness in DuckDB-PCT

A JUnit 5 harness that runs the same M2M model+query through (a) `JsonModelConnection` and (b) `SqlModelConnection` and asserts identical JSON output — plus, once Task 13 lands, plan-shape assertions. Lives in `DUCKDB-PCT` because that module already has the full execution stack plus DuckDB on the classpath.

**Files:**
- Create: `DUCKDB-PCT/src/test/java/org/finos/legend/engine/plan/execution/stores/relational/test/duckdb/m2msql/TestM2MSqlParity.java`
- Create: `DUCKDB-PCT/src/test/resources/m2msql/expected-fallbacks.json`
- Modify: `DUCKDB-PCT/pom.xml` (test-scoped deps as needed — expect at least `legend-engine-language-pure-grammar`, `legend-engine-language-pure-compiler`, `legend-engine-executionPlan-generation`, `legend-engine-executionPlan-execution-store-inMemory`, the json/xml java-binding runtime jars; add exactly what compilation/execution errors demand, mirroring the poms of the module hosting `TestM2MGrammarCompileAndExecute`)

**Interfaces:**
- Consumes: Tasks 3–6. Template: `TestM2MGrammarCompileAndExecute` (grammar→compile→plan→execute helper flow).
- Produces: `TestM2MSqlParity` with helper `assertParity(String grammarWithConnectionPlaceholder, String queryFn, String expectedJson)`; a corpus of 8 cases; `expected-fallbacks.json` manifest of cases that must NOT be SQL-planned yet, `{"case": "...", "reason": "..."}` entries.

- [ ] **Step 1: Write the harness with the first case**

Structure (JUnit 5). The grammar text uses a `${CONNECTION_TYPE}` placeholder substituted with `JsonModelConnection` / `SqlModelConnection`; mirror the parse/compile/plan/execute mechanics from `TestM2MGrammarCompileAndExecute` (read that file first and reuse its helper calls — same PureGrammarParser → PureModelContextData → compile → `PlanGenerator` → `PlanExecutor` chain, including its extension list, extended in Task 13):

```java
class TestM2MSqlParity
{
    private String execute(String grammar, String queryFn) { /* mirrored from TestM2MGrammarCompileAndExecute */ }

    private void assertParity(String grammarTemplate, String queryFn, String expectedJson)
    {
        String javaPath = execute(grammarTemplate.replace("${CONNECTION_TYPE}", "JsonModelConnection"), queryFn);
        String sqlPath = execute(grammarTemplate.replace("${CONNECTION_TYPE}", "SqlModelConnection"), queryFn);
        JsonAssert.assertJsonEquals(expectedJson, javaPath);
        JsonAssert.assertJsonEquals(javaPath, sqlPath);
    }

    @Test
    void simpleRename() { assertParity(SIMPLE_RENAME_GRAMMAR, SIMPLE_RENAME_QUERY, SIMPLE_RENAME_EXPECTED); }
}
```

Corpus cases (each an inline grammar constant; source data supplied via a `data:` or `file:` URL the same way the template harness does):
1. `simpleRename` — one class, property lambdas `fullName: $src.firstName + ' ' + $src.lastName`.
2. `computedPrimitive` — arithmetic transform (`$src.a * $src.b`).
3. `nestedComplexToOne` — target property of class type, `[0..1]`, mapped via embedded/complex transform.
4. `autoMapped` — property with no explicit mapping, same name/type in source.
5. `primitiveCollection` — `String[*]` pass-through property.
6. `mappingFilter` — mapping with `~filter` (slice-1 expected fallback).
7. `enumTransform` — property with an `EnumerationMapping` transformer (expected fallback).
8. `explodeMapping` — explode property mapping (expected fallback).

- [ ] **Step 2: Run — all 8 must pass on parity now** (both connections currently take the Java path):

```bash
mvn clean test -pl <DUCKDB-PCT> -Dtest=TestM2MSqlParity
```
Iterate on missing pom deps until green.

- [ ] **Step 3: Create `expected-fallbacks.json`** listing cases 6–8 with reasons (`"mapping filter not yet translated"`, `"enumeration transformer not yet translated"`, `"explode mapping not yet translated"`). The harness reads it in Task 13 to decide which cases assert plan-collapse vs plan-unchanged. For now just check the file in.

- [ ] **Step 4: Commit**

```bash
git add <DUCKDB-PCT> && git commit -m "Add M2M SQL parity harness (Java path both sides for now)"
```

---

### Task 8: Core plan post-processing hook

**Files:**
- Modify: `CORE-PURE/src/main/resources/core/pure/extensions/extension.pure` (class `meta::pure::extension::Extension`, ~line 60 area — next to the other ad-hoc optional function fields like `graphExtension_calculateSourceTree`)
- Modify: `CORE-PURE/src/main/resources/core/pure/executionPlan/executionPlan_generation.pure` (terminal `executionPlan(clusters, ...)` at lines 176–221)
- Test: `CORE-PURE/src/main/resources/core/pure/executionPlan/tests/testPlanPostProcess.pure` (new)

**Interfaces:**
- Produces: `Extension.postProcessExecutionPlan : Function<{ExecutionPlan[1], meta::pure::extension::Extension[*], DebugContext[1] -> ExecutionPlan[1]}>[0..1]`, applied in registration order to the fully assembled plan BEFORE `generatePlatformCode` runs. Task 13 registers the M2M collapse here.

- [ ] **Step 1: Verify the ordering assumption**

The rewrite must run before platform binding (or Java is still generated). Confirm the Java driver calls `executionPlan(...)` and `generatePlatformCode(...)` as two separate steps in that order:

```bash
grep -rn "generatePlatformCode\|executionPlan_FunctionDefinition" legend-engine-core/legend-engine-core-base/legend-engine-core-executionPlan-generation/src/main/java/org/finos/legend/engine/plan/generation/PlanGenerator.java | head -20
```
Expected: plan generation call followed by a separate platform-binding call. If instead a single Pure entry point does both, place the hook application inside that entry point *between* the two stages rather than at the end of `executionPlan(clusters, ...)` — same field, different call site. Record which variant applied.

- [ ] **Step 2: Write the failing Pure test**

`testPlanPostProcess.pure`, package `meta::pure::executionPlan::tests::postprocess` (collected by `Test_Pure_Core`):

```pure
function <<test.Test>> meta::pure::executionPlan::tests::postprocess::testPostProcessHookIsApplied():Boolean[1]
{
  let ext = ^meta::pure::extension::Extension(
              type = 'testPostProcess',
              postProcessExecutionPlan = {plan:meta::pure::executionPlan::ExecutionPlan[1], extensions:meta::pure::extension::Extension[*], debug:DebugContext[1] |
                 ^$plan(processingTemplateFunctions = $plan.processingTemplateFunctions->concatenate('POST_PROCESSED'))
              });
  let plan = meta::pure::executionPlan::executionPlan(|1+1, $ext);
  assert($plan.processingTemplateFunctions->contains('POST_PROCESSED'));
}
```

- [ ] **Step 3: Run to verify failure**

```bash
mvn clean test -pl <CORE-PURE> -Dtest=Test_Pure_Core -Dsurefire.failIfNoSpecifiedTests=false
```
(If `Test_Pure_Core` is too slow to iterate on, use the repo's targeted Pure-test mechanism if one exists; otherwise accept the cycle time.) Expected: compile failure — `postProcessExecutionPlan` is not a property of `Extension`.

- [ ] **Step 4: Implement**

In `extension.pure`, inside `Class meta::pure::extension::Extension`, after the `graphExtension_calculateSourceTree` block:

```pure
  // Plan post-processing: applied to the fully assembled ExecutionPlan before platform binding ----
  postProcessExecutionPlan : Function<{meta::pure::executionPlan::ExecutionPlan[1], meta::pure::extension::Extension[*], DebugContext[1] -> meta::pure::executionPlan::ExecutionPlan[1]}>[0..1];
  //-----------------------------------------------------------------------------------------------
```

In `executionPlan_generation.pure`, replace the terminal `^ExecutionPlan(...)` expression of `executionPlan(clusters, ...)` (lines 210–220) with:

```pure
   let assembledPlan = ^ExecutionPlan
   (
      func = $f,
      mapping = $m,
      runtime = $runtime,
      rootExecutionNode = $updatedNode,
      authDependent = false || $updatedNode->allNodes($extensions).authDependent->contains(true),
      kerberos =  $updatedNode->allNodes($extensions).kerberos->first(),
      processingTemplateFunctions = $updatedNode->allNodes($extensions).supportFunctions->removeDuplicates()
   );
   $extensions.postProcessExecutionPlan->fold({pp, p | $pp->eval($p, $extensions, $debugContext)}, $assembledPlan);
```

- [ ] **Step 5: Run to verify pass, then commit**

```bash
mvn clean test -pl <CORE-PURE> -Dtest=Test_Pure_Core
git add <CORE-PURE> && git commit -m "Add Extension.postProcessExecutionPlan hook applied before platform binding"
```

---

### Task 9: Translatability whitelist

**Files:**
- Create: `REL-CORE-PURE/src/main/resources/core_relational/relational/modelToModelToRelational/m2mSql/translatability.pure`
- Test: `REL-CORE-PURE/src/main/resources/core_relational/relational/modelToModelToRelational/m2mSql/tests/testTranslatability.pure`

**Interfaces:**
- Produces:

```pure
Class meta::relational::m2mSql::M2MSqlTranslatability
{
  translatable : Boolean[1];
  reason       : String[0..1];
}

function meta::relational::m2mSql::canPlanM2MSql(setImpl:PureInstanceSetImplementation[1], targetTree:RootGraphFetchTree<Any>[1], mapping:Mapping[1]):meta::relational::m2mSql::M2MSqlTranslatability[1]
```
Consumed by the collapse post-processor (Task 13).

- [ ] **Step 1: Write failing tests, one per rejection reason** (`testTranslatability.pure`, package `meta::relational::m2mSql::tests`; build small inline models with `meta::legend::compile` if that's what neighboring core_relational Pure tests use — check `m2m2rShowcase.pure`'s model-definition style and reuse it):
  - simple rename mapping → `translatable == true`
  - mapping with `~filter` → `false`, reason contains `'filter'`
  - property mapping with enum `transformer` → `false`, reason contains `'enumeration'`
  - explode property mapping → `false`, reason contains `'explode'`
  - local mapping property (`+prop`) → `false`, reason contains `'local'`
  - transform calling a non-whitelisted function (e.g. `->trim()`) → `false`, reason names the function
  - target tree containing a subtype tree → `false`, reason contains `'subType'`

- [ ] **Step 2: Run to verify failure** — the module's Pure test runner (find it: `ls <REL-CORE-PURE>/src/test/java/org/finos/legend/pure/code/core/`); expected: unresolved function.

- [ ] **Step 3: Implement**

Checks in order (return the FIRST failure as the reason):
1. `$setImpl.filter->isNotEmpty()` → `'mapping filter not yet translated'`
2. Any `PurePropertyMapping` used by the tree with `.transformer->isNotEmpty()` → enumeration reason
3. Any used mapping with `.explodeProperty == true` → explode reason
4. `$setImpl.mappingClass->isNotEmpty()` or local mapping properties present → local-property reason
5. Target tree walk: every `PropertyGraphFetchTree` must have a `subType->isEmpty()`; leaves must resolve to a `PurePropertyMapping` on `$setImpl` (or auto-map per `contract::isPropertyAutoMapped`)
6. Each used transform lambda passes `isSqlTranslatableExpression`, a recursive walk over `expressionSequence` allowing exactly: `VariableExpression` (`$src`), `InstanceValue` of primitives, property accesses (`$fe.func->instanceOf(Property)`) chained on translatable operands, and `SimpleFunctionExpression`s whose `func` is in the whitelist `[plus_String_MANY__String_1_, plus_Integer_MANY__Integer_1_, plus_Float_MANY__Float_1_, plus_Number_MANY__Number_1_, minus_Number_MANY__Number_1_, times_Number_MANY__Number_1_, divide_Number_1__Number_1__Float_1_, toOne_T_MANY__T_1_]` (extend only when a parity case demands it — conservative by design; consult `mapping/modelToModel.pure` and PCT'd arithmetic signatures for the exact reference forms if a name doesn't resolve).

- [ ] **Step 4: Run to green, commit**

```bash
mvn clean test -pl <REL-CORE-PURE> -Dtest=<that runner>
git add <REL-CORE-PURE> && git commit -m "Add M2M SQL translatability whitelist"
```

---

### Task 10: Synthetic source Database + semi-structured mapping

**Files:**
- Create: `REL-CORE-PURE/src/main/resources/core_relational/relational/modelToModelToRelational/m2mSql/sourceSynthesis.pure`
- Test: `DUCKDB-PURE/src/main/resources/core_relational_duckdb/relational/m2mSql/tests/testSourceSynthesis.pure` (DuckDB SQL-string assertions must run where the DuckDB `DbExtension` loader is on the classpath; use the same package prefix as `testDuckDBSQLGeneration.pure` — read that file's package first and place these tests beside it)

**Interfaces:**
- Consumes: metamodel constructors proven in `pct_relational.pure:830-879` (`classToTable`, `mutateAdd` for the set↔propertyMappings cycle) and `helperFunctions.pure:272-309` (`buildSemiStructuredPropertyMapping` — `SemiStructuredPropertyAccess`, `SemiStructuredEmbeddedRelationalInstanceSetImplementation`, `BindingTransformer`).
- Produces:

```pure
Class meta::relational::m2mSql::M2MSqlSourceStore
{
  database : Database[1];
  table    : Table[1];
  jsonColumn : Column[1];
  mapping  : Mapping[1];      // contains the synthetic root set for srcClass
  rootSet  : RootRelationalInstanceSetImplementation[1];
}

function meta::relational::m2mSql::synthesizeSourceStore(srcClass:Class<Any>[1], sourceTree:RootGraphFetchTree<Any>[1]):meta::relational::m2mSql::M2MSqlSourceStore[1]
```

- [ ] **Step 1: Write the failing SQL-string test**

In `testSourceSynthesis.pure` (with a small test model class defined inline in the same file):

```pure
function <<test.Test>> <pkg>::testSimpleSourceSynthesisSql():Boolean[1]
{
  let src = meta::relational::m2mSql::synthesizeSourceStore(<pkg>::model::SrcPerson, #{<pkg>::model::SrcPerson {firstName, age}}#);
  let result = meta::pure::executionPlan::executionPlan(
                 |<pkg>::model::SrcPerson.all()->project([s|$s.firstName, s|$s.age], ['firstName', 'age']),
                 $src.mapping,
                 ^meta::core::runtime::Runtime(connectionStores = ^meta::core::runtime::ConnectionStore(
                     element = $src.database,
                     connection = ^meta::external::store::relational::runtime::RelationalDatabaseConnection(
                                     type = DatabaseType.DuckDB,
                                     datasourceSpecification = ^meta::pure::alloy::connections::alloy::specification::DuckDBDatasourceSpecification(path = ''),
                                     authenticationStrategy = ^meta::pure::alloy::connections::alloy::authentication::TestDatabaseAuthenticationStrategy()))),
                 meta::relational::extension::relationalExtensions())
               ->meta::pure::executionPlan::toString::planToString(meta::relational::extension::relationalExtensions());
  assert($result->contains('json_extract_string("root".data, \'$.firstName\')'), | 'expected semi-structured navigation, got: ' + $result);
  assert($result->contains('m2m_src_0'), | $result);
}
```

(Exact Pure paths for the DuckDB datasource/auth spec classes: verify by grepping `DUCKDB-PURE` resources for `DuckDBDatasourceSpecification`; adjust imports to match. If the plan string uses `->` navigation instead of `json_extract_string`, update the assertion to the actual dialect output — either proves the pipe works.)

- [ ] **Step 2: Run to verify failure** (`mvn clean test -pl <DUCKDB-PURE> -Dtest=Test_Pure_Relational_DuckDB`) — unresolved `synthesizeSourceStore`.

- [ ] **Step 3: Implement `sourceSynthesis.pure`**

```pure
function meta::relational::m2mSql::synthesizeSourceStore(srcClass:Class<Any>[1], sourceTree:RootGraphFetchTree<Any>[1]):M2MSqlSourceStore[1]
{
  let db     = ^Database(name = 'm2m_sql_db');
  let schema = ^Schema(name = 'default', database = $db);
  let jsonCol = ^Column(name = 'data', type = ^meta::relational::metamodel::datatype::SemiStructured());
  let table  = ^Table(name = 'm2m_src_0', schema = $schema, columns = $jsonCol, primaryKey = $jsonCol);
  let alias  = ^TableAlias(database = $db, name = 'root', relationalElement = $table);
  let mapping = ^Mapping(name = 'm2mSqlSourceMapping', package = $srcClass.package);
  let set = ^RootRelationalInstanceSetImplementation(
              id = $srcClass.name->toOne(), root = true, parent = $mapping,
              class = $srcClass, mainTableAlias = $alias);

  let requiredProps = $sourceTree.subTrees->cast(@PropertyGraphFetchTree).property->cast(@Property<Nil,Any|*>);
  let pmappings = $requiredProps->map(p |
     if ($p->functionReturnType().rawType->toOne()->instanceOf(DataType),
        | ^RelationalPropertyMapping(
             property = $p, sourceSetImplementationId = $set.id, targetSetImplementationId = '', owner = $set,
             relationalOperationElement = ^DynaFunction(name = 'extractFromSemiStructured',
                parameters = [^TableAliasColumn(alias = $alias, column = $jsonCol),
                              ^Literal(value = $p.name->toOne()),
                              ^Literal(value = $p->functionReturnType().rawType->toOne()->pureTypeToSemiStructuredExtractType())])),
        | ^SemiStructuredEmbeddedRelationalInstanceSetImplementation(   // complex property: navigation root; nested
             id = $set.id + '_' + $p.name->toOne(), root = false,       // property mappings are synthesized lazily by
             class = $p->functionReturnType().rawType->toOne()->cast(@Class<Any>),  // findPropertyMapping/buildSemiStructuredPropertyMapping
             parent = $mapping, setMappingOwner = $set,
             sourceSetImplementationId = $set.id, targetSetImplementationId = $set.id + '_' + $p.name->toOne(),
             owner = $set, property = $p,
             relationalOperationElement = ^meta::relational::metamodel::operation::SemiStructuredPropertyAccess(
                operand = ^TableAliasColumn(alias = $alias, column = $jsonCol),
                property = ^Literal(value = $p.name->toOne()),
                returnType = $p->functionReturnType().rawType,
                contentType = 'application/json'),
             transformer = ^meta::external::format::shared::binding::BindingTransformer(binding =
                ^meta::external::format::shared::binding::Binding(name = 'm2mSqlBinding', contentType = 'application/json')))
     ));
  $set->mutateAdd('propertyMappings', $pmappings);
  $mapping->mutateAdd('classMappings', $set);
  ^M2MSqlSourceStore(database = $db, table = $table, jsonColumn = $jsonCol, mapping = $mapping, rootSet = $set);
}

function <<access.private>> meta::relational::m2mSql::pureTypeToSemiStructuredExtractType(t:Type[1]):String[1]
{
  if($t == String, |'VARCHAR',
  if($t == Integer, |'INTEGER',
  if($t == Float, |'FLOAT',
  if($t == Boolean, |'BOOLEAN',
  if($t == StrictDate, |'DATE',
  if($t == Date || $t == DateTime, |'TIMESTAMP',
  |fail('Unsupported source primitive for M2M SQL: ' + $t->elementToPath()); 'VARCHAR';))))));
}
```

Field-name/constructor mismatches WILL surface (e.g. `Binding` may require `package`/`modelUnit`; `BindingTransformer` may require `class`). Fix each from the failing test's error message, consulting the class definitions the errors name (`helperFunctions.pure:272-309` is the reference usage). If planning demands a real primary key, keep `primaryKey = $jsonCol` — slice-1 queries never emit it.

- [ ] **Step 4: Add a nested-navigation test** — a source class with a `[0..1]` complex property; query `->project([s|$s.address.street], ['street'])`; assert the plan SQL contains chained navigation (`->'address'` / nested `json_extract`). Run both tests to green.

- [ ] **Step 5: Commit**

```bash
git add <REL-CORE-PURE> <DUCKDB-PURE>
git commit -m "Synthesize relational source store + semi-structured mapping from M2M source tree"
```

---

### Task 11: JSON projection and SQL assembly

Builds the complete SQL for a translatable case: inner query (source navigation + inlined transforms, via routed `project`) wrapped in an outer SELECT whose single column constructs the JSON document.

**Files:**
- Create: `REL-CORE-PURE/src/main/resources/core_relational/relational/modelToModelToRelational/m2mSql/jsonProjection.pure`
- Test: `DUCKDB-PURE/src/main/resources/core_relational_duckdb/relational/m2mSql/tests/testJsonProjection.pure`

**Interfaces:**
- Consumes: `synthesizeSourceStore` (Task 10), `jsonObject`/`jsonArray` dynaFns (Task 2), `processOperation(RelationalOperationElement,DatabaseType,Extension[*])` (`dbExtension.pure:433`), dynamic-expression precedents `chain.pure:413` (`createEvalFunctionExpression`) and `pct_relational.pure` construction style.
- Produces:

```pure
Class meta::relational::m2mSql::M2MSqlQuery
{
  setupSqls : String[*];   // CREATE OR REPLACE TEMP TABLE + INSERT ... read_json ('{{M2M_INPUT_PATH}}' placeholder)
  sqlQuery  : String[1];   // SELECT <json doc expr> FROM (<inner sql>) as "m2m_sub"
}

function meta::relational::m2mSql::buildM2MSqlQuery(setImpl:PureInstanceSetImplementation[1], targetTree:RootGraphFetchTree<Any>[1], connection:meta::external::store::relational::runtime::DatabaseConnection[1], extensions:meta::pure::extension::Extension[*], debug:DebugContext[1]):meta::relational::m2mSql::M2MSqlQuery[1]
```

- [ ] **Step 1: Write the failing test**

Inline model in the test file: `SrcPerson {firstName:String[1]; lastName:String[1];}`, `Person {fullName:String[1];}`, a Pure mapping `fullName : $src.firstName + ' ' + $src.lastName`. Then:

```pure
function <<test.Test>> <pkg>::testSimpleM2MSqlQuery():Boolean[1]
{
  let setImpl = <pkg>::testMapping()->classMappingById('<id>')->cast(@PureInstanceSetImplementation)->toOne();
  let q = meta::relational::m2mSql::buildM2MSqlQuery($setImpl, #{<pkg>::model::Person {fullName}}#, <duckdb connection as in Task 10>, meta::relational::extension::relationalExtensions(), noDebug());
  assert($q.setupSqls->exists(s | $s->contains('read_json')), | $q.setupSqls->makeString('\n'));
  assert($q.sqlQuery->startsWith('select json_object('), | $q.sqlQuery);
  assert($q.sqlQuery->contains('\'fullName\''), | $q.sqlQuery);
  assert($q.sqlQuery->contains('from (select'), | $q.sqlQuery);
}
```

- [ ] **Step 2: Run to verify failure** (`Test_Pure_Relational_DuckDB`), expected: unresolved function.

- [ ] **Step 3: Implement in three parts**

**(a) Flatten the target tree to columns.** Walk `targetTree.subTrees` recursively; for each primitive leaf produce `^M2MSqlColumn(path=String[*], columnName=String, transform=LambdaFunction)` where `columnName = $path->joinStrings('__')` and `transform` is the resolved `PurePropertyMapping.transform` (or a synthesized identity lambda for auto-mapped properties — build it with `createEvalFunctionExpression`-style construction over the source property, mirroring `chain.pure:413`). For nested `[0..1]` complex targets, recurse with the child set implementation (embedded or target-id-resolved), extending `path`.

**(b) Build and plan the inner query.** Construct the expression `srcClass.all()->project([transforms], [columnNames])` dynamically: a `SimpleFunctionExpression` for `getAll` over an `InstanceValue` holding `$setImpl.srcClass`, wrapped by one for `project_T_MANY__Function_MANY__String_MANY_` whose second parameter is an `InstanceValue` holding the transform lambdas and third the names. Mirror the construction discipline of `chain.pure` (`createEvalFunctionExpression`, explicit `genericType`/`multiplicity` on every node) — expect 2–3 iterations driven by router error messages; that is normal, the SQL-string test is the oracle. Wrap in `^LambdaFunction<{->Any[*]}>(expressionSequence=...)`, then:

```pure
  let src = synthesizeSourceStore($setImpl.srcClass->toOne()->cast(@Class<Any>), $targetTree->calculateSourceTree($setImpl.parent, $extensions));
  let innerPlan = meta::pure::executionPlan::executionPlan($lambda, $src.mapping, ^Runtime(connectionStores = ^ConnectionStore(element = $src.database, connection = $connection)), $extensions, $debug);
  let innerSql = $innerPlan.rootExecutionNode->meta::pure::executionPlan::allNodes($extensions)->filter(n | $n->instanceOf(meta::relational::mapping::SQLExecutionNode))->cast(@meta::relational::mapping::SQLExecutionNode).sqlQuery->toOne();
```

**(c) Outer JSON wrap + setup SQL.** Build the document expression as a `DynaFunction` tree mirroring the target-tree nesting: `jsonObject('k1', <colRef>, 'k2', jsonObject(...))` where each `<colRef>` is `^TableAliasColumn(alias = ^TableAlias(name = 'm2m_sub', relationalElement = ^Table(name = 'm2m_sub', schema = ^Schema(name = 'default', database = ^Database(name = 'm2m_sql_db')))), column = ^Column(name = $col.columnName, type = ^meta::relational::metamodel::datatype::Varchar(size = 8192)))`. Stringify with `processOperation($docExpr, $connection.type, $extensions)` and assemble:

```pure
  let sql = 'select ' + $docSql + ' as "doc" from (' + $innerSql + ') as "m2m_sub"';
  let setup = ['CREATE OR REPLACE TEMP TABLE "default".m2m_src_0 (data JSON);',
               'INSERT INTO "default".m2m_src_0 SELECT to_json(j) FROM read_json(\'{{M2M_INPUT_PATH}}\', format=\'auto\') as j;'];
```

(Table/schema naming must match whatever the inner SQL actually emits for the synthetic table — read the failing diff and align; if the schema prefix differs, change the synthesized `Schema` name in Task 10, not the setup strings.)

- [ ] **Step 4: Add the nested + collection tests** — `nestedComplexToOne` (assert nested `json_object(` inside the outer one) and `primitiveCollection` (assert the collection column is passed through inside `jsonArray(`/`to_json(` per what the dialect emits for an extracted JSON array — align assertion to output). Run all to green.

- [ ] **Step 5: Commit**

```bash
git add <REL-CORE-PURE> <DUCKDB-PURE>
git commit -m "Build M2M SQL query: inlined transforms + JSON-document projection"
```

---

### Task 12: `SQLGraphSerializeExecutionNode` — protocol, Pure node, executor

**Files:**
- Create: `REL-PROTOCOL/src/main/java/org/finos/legend/engine/protocol/pure/v1/model/executionPlan/nodes/SQLGraphSerializeExecutionNode.java`
- Modify: the relational `PureProtocolExtension` registering execution-node subtypes (find it: `grep -rn "SQLExecutionNode.class" <REL-PROTOCOL>/src/main/java` — add ours beside it)
- Modify: `REL-CORE-PURE/.../core_relational/relational/executionPlan/executionPlan.pure` (Pure node class, next to `SQLExecutionNode` at line 63)
- Modify: the Pure→protocol node transform (find it: `grep -rn "SQLExecutionNode" <REL-CORE-PURE>/src/main/resources/core_relational/relational/protocols/` — add a case for our node in BOTH `vX_X_X` and `v1_33_0` transform files, mirroring SQLExecutionNode's)
- Create: `REL-EXEC/src/main/java/org/finos/legend/engine/plan/execution/stores/relational/plugin/M2MSqlGraphSerializeHelper.java`
- Create: `REL-EXEC/src/main/java/org/finos/legend/engine/plan/execution/stores/relational/plugin/M2MSqlLoggingEventType.java`
- Modify: `REL-EXEC/.../plugin/RelationalExecutionNodeExecutor.java` (instanceof chain, beside the `SQLExecutionNode` branch at line 429)
- Test: `REL-EXEC/src/test/java/org/finos/legend/engine/plan/execution/stores/relational/plugin/TestSQLGraphSerializeExecutionNode.java` (JUnit 5; module needs a test-scoped dep on the duckdb execution module — add `legend-engine-xt-relationalStore-duckdb-execution` test-scoped; if that creates a reactor cycle, put the test in `DUCKDB-PCT` instead under `.../duckdb/m2msql/`)

**Interfaces:**
- Produces — protocol node (`_type`: `"sqlGraphSerialize"`):

```java
public class SQLGraphSerializeExecutionNode extends ExecutionNode
{
    public DatabaseConnection connection;
    public List<String> setupSqls = Collections.emptyList();
    public String sqlQuery;
    public String url;
    // accept(...) mirrors SQLExecutionNode's accept exactly
}
```
Pure twin `meta::relational::m2mSql::SQLGraphSerializeExecutionNode extends ExecutionNode { connection: DatabaseConnection[1]; setupSqls: String[*]; sqlQuery: String[1]; url: String[1]; }`. Executor contract: materializes `url` to a local temp file, replaces the literal token `{{M2M_INPUT_PATH}}` in each setup SQL, runs setup + query on ONE JDBC connection, returns `JsonStreamingResult` whose stream is the `doc` column values as raw JSON inside a JSON array (or the bare object when the plan's result multiplicity is 1 — match the Java path's envelope, see Step 4).

- [ ] **Step 1: Write the failing executor test** — hand-build a node (no plan generation involved):

```java
@Test
void executesSetupAndStreamsJson() throws Exception
{
    SQLGraphSerializeExecutionNode node = new SQLGraphSerializeExecutionNode();
    node.connection = duckDbConnection();          // mirror DuckDBTestConnectionIntegration.getConnection():
                                                   // DuckDBDatasourceSpecification path="", TestDatabaseAuthenticationStrategy,
                                                   // new RelationalDatabaseConnection(spec, auth, DatabaseType.DuckDB); conn.type = DatabaseType.DuckDB (MANDATORY — ctor does not set the legacy field)
    node.url = tempJsonFile("[{\"firstName\":\"John\",\"lastName\":\"Doe\"}]").toUri().toString();
    node.setupSqls = Arrays.asList(
        "CREATE OR REPLACE TEMP TABLE m2m_src_0 (data JSON);",
        "INSERT INTO m2m_src_0 SELECT to_json(j) FROM read_json('{{M2M_INPUT_PATH}}', format='auto') as j;");
    node.sqlQuery = "select json_object('fullName', json_extract_string(data, '$.firstName') || ' ' || json_extract_string(data, '$.lastName')) as \"doc\" from m2m_src_0";

    Result result = planExecutor().execute(singleNodePlan(node));   // build via PlanExecutor with relational store executor, as sibling tests in this module do
    String json = drainToString((JsonStreamingResult) result);
    JsonAssert.assertJsonEquals("[{\"fullName\":\"John Doe\"}]", json);
}
```

(Read a sibling test in the module for the `PlanExecutor`/`SingleExecutionPlan` bootstrap incantation and reuse it; the node goes in as `rootExecutionNode` of a minimal `SingleExecutionPlan`.)

- [ ] **Step 2: Run to verify failure** — no such node type / Jackson unknown `_type`.

- [ ] **Step 3: Implement**

1. Protocol POJO + subtype registration (mirror how `SQLExecutionNode` is registered in the relational protocol extension).
2. Pure node class + Pure→protocol transform cases (both protocol versions).
3. `M2MSqlLoggingEventType` enum: `M2M_SQL_GRAPH_SERIALIZE_START`, `M2M_SQL_GRAPH_SERIALIZE_STOP`, `M2M_SQL_GRAPH_SERIALIZE_ERROR` — mirror `ElasticsearchExecutionLoggingEventType` (module-local enum implementing `ILoggingEventType`).
4. `M2MSqlGraphSerializeHelper.execute(node, identity, executionState)`:
   - Acquire the JDBC connection exactly as `RelationalExecutor.getConnection` does (via `((RelationalStoreExecutionState) executionState.getStoreExecutionState(StoreType.Relational)).getRelationalExecutor().getConnectionManager().getDatabaseConnection(...)`).
   - Copy `UrlFactory.create(node.url).openStream()` to a temp file in `Files.createTempFile`; substitute the absolute path (single-quote-escaped) for `{{M2M_INPUT_PATH}}` in each setup SQL.
   - Execute each setup SQL, then `executeQuery(node.sqlQuery)`.
   - Return a `JsonStreamingResult` whose `JsonStreamHandler.writeTo(JsonGenerator g)` writes `g.writeStartArray()`, then `g.writeRawValue(rs.getString(1))` per row, then `g.writeEndArray()`; `toStream()` maps rows through Jackson `readTree`. Close ResultSet/Statement/Connection and delete the temp file in the result's close cascade (pass a child `Result` whose `close()` does the cleanup).
5. Executor branch in `RelationalExecutionNodeExecutor` beside the `SQLExecutionNode` branch (line 429), wrapped in the same tracing span pattern, logging START/STOP with `LogInfo` + the new event types.

- [ ] **Step 4: Run to verify pass** — iterate on the exact JSON envelope: run any existing Java-path M2M serialize test, capture its output shape (`{"builder":...,"values":...}` vs bare array) and make `drainToString`'s expected value match the DEFAULT serializer's behavior. Record the finding in the test as a comment — Task 13's parity assertions depend on envelope equality.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add SQLGraphSerializeExecutionNode: protocol, Pure node, DuckDB-backed executor"
```

---

### Task 13: The collapse post-processor + registration + parity flip

**Files:**
- Create: `REL-CORE-PURE/src/main/resources/core_relational/relational/modelToModelToRelational/m2mSql/collapse.pure`
- Create: `DUCKDB-PURE/src/main/resources/core_relational_duckdb/relational/m2mSql/m2mSqlDuckDBExtension.pure`
- Modify: the DuckDB `LegendPureCoreExtension` SPI class (find: `grep -rln "PureCoreExtension" <DUCKDB-PURE>/src/main/java`) so the server/harness extension assembly picks the new extension up
- Modify: `DUCKDB-PCT/.../m2msql/TestM2MSqlParity.java` (extension list + plan-shape assertions)
- Test: `DUCKDB-PURE/.../relational/m2mSql/tests/testCollapse.pure` (plan-shape Pure test) + the parity harness

**Interfaces:**
- Consumes: `postProcessExecutionPlan` hook (Task 8), `canPlanM2MSql` (Task 9), `buildM2MSqlQuery` (Task 11), `SQLGraphSerializeExecutionNode` (Task 12).
- Produces: `meta::relational::m2mSql::duckDB::duckDBM2MSqlExtension():Extension[1]` — appending this to `relationalExtensions()` activates SQL-for-M2M end to end.

- [ ] **Step 1: Write the failing plan-shape Pure test** (`testCollapse.pure`): full inline model (source class, target class, Pure mapping, Runtime with `^SqlModelConnection(class=Src, url='file:/tmp/unused.json')` bound to `^ModelStore()`), then:

```pure
function <<test.Test>> <pkg>::testSerializeGraphFetchCollapses():Boolean[1]
{
  let extensions = meta::relational::extension::relationalExtensions()->concatenate(meta::relational::m2mSql::duckDB::duckDBM2MSqlExtension());
  let plan = meta::pure::executionPlan::executionPlan(
               |<pkg>::model::Person.all()->graphFetch(#{<pkg>::model::Person {fullName}}#)->serialize(#{<pkg>::model::Person {fullName}}#),
               <pkg>::testMapping(), <pkg>::testRuntime(), $extensions);
  assert($plan.rootExecutionNode->meta::pure::executionPlan::allNodes($extensions)->exists(n | $n->instanceOf(meta::relational::m2mSql::SQLGraphSerializeExecutionNode)), | 'expected collapsed SQL node');
  assert(!$plan.rootExecutionNode->meta::pure::executionPlan::allNodes($extensions)->exists(n | $n->instanceOf(meta::pure::mapping::modelToModel::graphFetch::executionPlan::InMemoryRootGraphFetchExecutionNode)), | 'InMemory nodes must be gone');
}
```

Plus `testUntranslatableStaysOnJavaPath` (mapping with `~filter` → InMemory nodes still present, no SQL node).

- [ ] **Step 2: Run to verify failure** (`Test_Pure_Relational_DuckDB`) — unresolved extension function.

- [ ] **Step 3: Implement the collapse (`collapse.pure`)**

```pure
function meta::relational::m2mSql::m2mSqlPlanPostProcessor(defaultConnection:meta::external::store::relational::runtime::DatabaseConnection[1]):Function<{ExecutionPlan[1], meta::pure::extension::Extension[*], DebugContext[1] -> ExecutionPlan[1]}>[1]
{
  {plan:ExecutionPlan[1], extensions:meta::pure::extension::Extension[*], debug:DebugContext[1] |
     ^$plan(rootExecutionNode = $plan.rootExecutionNode->meta::relational::m2mSql::collapseNode($plan, $defaultConnection, $extensions, $debug))
  }
}
```

`collapseNode` recursion: on `SequenceExecutionNode`, rebuild with collapsed children; on the serialize platform node, attempt the match. Before coding the match, print one real plan to learn exact node classes and fields:

```pure
// temporary probe inside the test, then delete:
println($plan->meta::pure::executionPlan::toString::planToString(true, $extensions));
```

The expected chain (verify field names against `core/pure/executionPlan/executionPlan.pure` for the platform node and `core/pure/graphFetch/graphFetchExecutionPlan.pure:` for `StoreMappingGlobalGraphFetchExecutionNode` — read both):
platform node for `serialize` → child `StoreMappingGlobalGraphFetchExecutionNode` (carries the TARGET tree and no cross-store children) → its local node `InMemoryRootGraphFetchExecutionNode` (no `filter`, single `StoreStreamReadingExecutionNode` child whose `connection` is `SqlModelConnection`). Guard conditions, all required:
1. the platform node's expression is a `serialize` FunctionExpression whose tree equals the global node's target tree (compare via `->asString()` of both trees or structural walk);
2. global node has no cross-store/children beyond the local node;
3. `InMemoryRootGraphFetchExecutionNode.filter->isEmpty()` and `checked == false`;
4. the root set implementation (resolve from `$plan.mapping` + tree class) is a `PureInstanceSetImplementation` and `canPlanM2MSql(...)` returns `translatable=true`.

On success:

```pure
     let q = meta::relational::m2mSql::buildM2MSqlQuery($setImpl, $targetTree, $defaultConnection, $extensions, $debug);
     ^meta::relational::m2mSql::SQLGraphSerializeExecutionNode(
        resultType = ^ResultType(genericType = ^GenericType(rawType = String)),
        connection = $defaultConnection,
        setupSqls = $q.setupSqls,
        sqlQuery = $q.sqlQuery,
        url = $sqlModelConnection.url);
```

On any guard failure: `print(if($debug.debug, |'M2M SQL fallback: ' + $reason + '\n', |''))` and return the node unchanged.

**`m2mSqlDuckDBExtension.pure`** (in DUCKDB-PURE, so the Pure-side `DuckDBDatasourceSpecification` is visible):

```pure
function meta::relational::m2mSql::duckDB::duckDBM2MSqlExtension():meta::pure::extension::Extension[1]
{
  ^meta::pure::extension::Extension(
     type = 'm2mSqlDuckDB',
     postProcessExecutionPlan = meta::relational::m2mSql::m2mSqlPlanPostProcessor(
        ^meta::external::store::relational::runtime::RelationalDatabaseConnection(
           type = DatabaseType.DuckDB,
           datasourceSpecification = ^meta::pure::alloy::connections::alloy::specification::DuckDBDatasourceSpecification(path = ''),
           authenticationStrategy = ^meta::pure::alloy::connections::alloy::authentication::TestDatabaseAuthenticationStrategy())));
}
```

Register it in the DuckDB module's `PureCoreExtension` SPI class beside whatever it already exposes.

- [ ] **Step 4: Run the Pure tests to green**, then flip the parity harness:
  - Add the new extension into the harness's plan-generation extension list (mirroring how the SPI exposes it to Java — if the SPI registration alone is picked up by the harness's extension loading, nothing to add; verify by asserting plan shape).
  - For corpus cases 1–5: add assertions that the SQL-path plan (i) contains a node with `_type == "sqlGraphSerialize"` and (ii) contains NO `JavaClass`/platform implementation (serialize plan JSON, assert `!json.contains("\"implementation\"")` or walk the node tree — pick whichever sibling tests do).
  - For cases 6–8: assert the plan still contains the InMemory node `_type`s and the case is listed in `expected-fallbacks.json`.

```bash
mvn clean test -pl <DUCKDB-PCT> -Dtest=TestM2MSqlParity
```
Iterate until all 8 pass — parity failures here are the real integration debugging of this whole plan; use `planToString` diffs and the DuckDB CLI (`read_json` semantics) to localize.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Collapse serialize/graphFetch over SqlModelConnection into single DuckDB SQL node"
```

---

### Task 14: Verification sweep, docs, cleanup

**Files:**
- Create: `docs/engineering/architecture/m2m-sql-execution.md`
- Modify: `docs/engineering/README.md` (index entry)
- Modify: `docs/superpowers/specs/2026-08-17-m2m-sql-execution-design.md` (status note: Phase 0–1 implemented; seam moved to plan post-processor)

- [ ] **Step 1: Full affected-module test run**

```bash
export JAVA_HOME=/home/aziem/jdk11/jdk-11.0.29+7
mvn clean install -T 4 -pl <CORE-PURE>,<PROTOCOL>,<GRAMMAR>,<COMPILER>,<REL-CORE-PURE>,<REL-PROTOCOL>,<REL-EXEC>,<JAVA-BINDING-PURE>,<DUCKDB-PURE>,<DUCKDB-PCT> -am
```
Also run the pre-existing M2M regression surface (proves no fallback-path regressions):
```bash
mvn clean test -pl <module of TestM2MGrammarCompileAndExecute> -Dtest=TestM2MGrammarCompileAndExecute
```
And Checkstyle: `mvn checkstyle:check -pl <each new/modified module>`. Fix everything.

- [ ] **Step 2: Write `docs/engineering/architecture/m2m-sql-execution.md`** covering: motivation (link spec), the `SqlModelConnection` element, the post-processor collapse (where the seam is and why — `contract::supports` excludes serialize), the guard conditions, translatability whitelist and how to extend it, `SQLGraphSerializeExecutionNode` runtime contract, the parity harness + `expected-fallbacks.json` workflow for growing coverage, and the phase-2 roadmap (filters, enums, explosion, union; metamodel-level outer query instead of string wrap; warehouse dialects). Add the index entry in `docs/engineering/README.md`.

- [ ] **Step 3: Sweep for leftovers** — probe `println`s deleted, no `System.out`, no TODO markers, headers present on every new file. `git status` clean of strays.

- [ ] **Step 4: Final commit**

```bash
git add -A && git commit -m "M2M SQL execution phase 1: docs and verification"
```

Then follow superpowers:finishing-a-development-branch for integration.

---

## Deferred to the Phase-2 plan (explicitly NOT here)

Filter push-down (`~filter`, root `filter()`), enumeration mappings (`CASE`), explosion (`unnest`), unions, merge, TDS-over-M2M, chains, checked/constraints, cross-store, warehouse engines, metamodel-level outer-query construction, plan-time fallback telemetry beyond debug output, server-config-driven extension wiring. Each currently falls back safely to the Java path via the Task 9 whitelist.
