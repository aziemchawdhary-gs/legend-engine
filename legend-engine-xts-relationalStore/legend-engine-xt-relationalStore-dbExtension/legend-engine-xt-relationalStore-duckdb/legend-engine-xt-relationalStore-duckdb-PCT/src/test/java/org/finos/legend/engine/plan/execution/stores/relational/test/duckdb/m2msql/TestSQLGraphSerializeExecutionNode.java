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

package org.finos.legend.engine.plan.execution.stores.relational.test.duckdb.m2msql;

import net.javacrumbs.jsonunit.JsonAssert;
import org.finos.legend.engine.plan.execution.PlanExecutor;
import org.finos.legend.engine.plan.execution.result.Result;
import org.finos.legend.engine.plan.execution.result.json.JsonStreamingResult;
import org.finos.legend.engine.plan.execution.result.serialization.SerializationFormat;
import org.finos.legend.engine.plan.execution.stores.relational.plugin.Relational;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.SingleExecutionPlan;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.SQLGraphSerializeExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.DatabaseType;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.RelationalDatabaseConnection;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.authentication.TestDatabaseAuthenticationStrategy;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.specification.DuckDBDatasourceSpecification;
import org.finos.legend.engine.shared.core.url.EngineUrlStreamHandlerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TDD coverage for {@link SQLGraphSerializeExecutionNode} execution: hand-builds the node (no plan
 * generation involved — that lands in a later task) and runs it against embedded DuckDB.
 *
 * <p><b>Envelope finding (Task 12, Step 4):</b> the real Java M2M graph-fetch serialize path
 * ({@code ExecutionNodeSerializerHelper.Serializer.writeTo}, used by the M2M {@code serialize()}
 * function) writes a <i>bare</i> JSON value when the result stream has exactly one element, and a
 * JSON array (empty or multi-element) otherwise — confirmed both by reading that class directly and
 * by the real HTTP-layer/parity runs recorded in {@code task-6-report.md} / {@code task-7-report.md}
 * and reproduced live by {@link TestM2MSqlParity} (e.g. its {@code explodeMapping} case: 3 rows in
 * source, {@code "values":[{"name":"a"},{"name":"b"},{"name":"c"}]}, a 3-element array; every other
 * case there is a single mapped row and emits a bare object, e.g.
 * {@code "values":{"fullName":"Jane Doe"}}). {@link
 * org.finos.legend.engine.plan.execution.stores.relational.plugin.M2MSqlGraphSerializeHelper}'s
 * {@code JsonStreamHandler.writeTo} mirrors this exactly (bare value for 1 row, array for 0 or 2+
 * rows) so that a plan rewritten to use this SQL node produces byte-identical envelopes to the Java
 * path at the {@code DEFAULT} serializer boundary — which is what {@code TestM2MSqlParity}-style
 * parity assertions (Task 13) compare.
 */
class TestSQLGraphSerializeExecutionNode
{
    @BeforeAll
    static void setUpUrls()
    {
        EngineUrlStreamHandlerFactory.initialize();
    }

    @Test
    void executesSetupAndStreamsJson() throws Exception
    {
        SQLGraphSerializeExecutionNode node = new SQLGraphSerializeExecutionNode();
        node.connection = duckDbConnection();
        node.url = tempJsonFile("[{\"firstName\":\"John\",\"lastName\":\"Doe\"}]").toUri().toString();
        node.setupSqls = Arrays.asList(
                "CREATE OR REPLACE TEMP TABLE m2m_src_0 (data JSON);",
                "INSERT INTO m2m_src_0 SELECT to_json(j) FROM read_json('{{M2M_INPUT_PATH}}', format='auto') as j;");
        node.sqlQuery = "select json_object('fullName', json_extract_string(data, '$.firstName') || ' ' || json_extract_string(data, '$.lastName')) as \"doc\" from m2m_src_0";

        long tempFilesBefore = countM2mTempFiles();
        Result result = planExecutor().execute(singleNodePlan(node));
        String json = drainToString((JsonStreamingResult) result);

        // Single-row result -> bare object "values" (see class-level envelope finding).
        JsonAssert.assertJsonEquals("{\"builder\":{\"_type\":\"json\"},\"values\":{\"fullName\":\"John Doe\"}}", json);
        // drainToString (DEFAULT serializer) closes the result, which must delete the helper's own
        // copied input temp file — not node.url's source file, which the test owns.
        assertEquals(tempFilesBefore, countM2mTempFiles());
    }

    @Test
    void wrapsMultipleRowsInAnArray() throws Exception
    {
        SQLGraphSerializeExecutionNode node = new SQLGraphSerializeExecutionNode();
        node.connection = duckDbConnection();
        node.url = tempJsonFile("[{\"firstName\":\"John\",\"lastName\":\"Doe\"},{\"firstName\":\"Jane\",\"lastName\":\"Doe\"}]").toUri().toString();
        node.setupSqls = Arrays.asList(
                "CREATE OR REPLACE TEMP TABLE m2m_src_0 (data JSON);",
                "INSERT INTO m2m_src_0 SELECT to_json(j) FROM read_json('{{M2M_INPUT_PATH}}', format='auto') as j;");
        node.sqlQuery = "select json_object('fullName', json_extract_string(data, '$.firstName') || ' ' || json_extract_string(data, '$.lastName')) as \"doc\" from m2m_src_0 order by \"doc\"";

        long tempFilesBefore = countM2mTempFiles();
        Result result = planExecutor().execute(singleNodePlan(node));
        String json = drainToString((JsonStreamingResult) result);

        // Multi-row result -> array "values" (see class-level envelope finding).
        JsonAssert.assertJsonEquals(
                "{\"builder\":{\"_type\":\"json\"},\"values\":[{\"fullName\":\"Jane Doe\"},{\"fullName\":\"John Doe\"}]}", json);
        assertEquals(tempFilesBefore, countM2mTempFiles());
    }

    @Test
    void cleansUpTempFileEvenWhenSetupSqlThrows() throws Exception
    {
        SQLGraphSerializeExecutionNode node = new SQLGraphSerializeExecutionNode();
        node.connection = duckDbConnection();
        node.url = tempJsonFile("[{\"firstName\":\"John\",\"lastName\":\"Doe\"}]").toUri().toString();
        node.setupSqls = Arrays.asList("THIS IS NOT VALID SQL AT ALL;");
        node.sqlQuery = "select 1 as \"doc\"";

        long tempFilesBefore = countM2mTempFiles();
        SingleExecutionPlan plan = singleNodePlan(node);
        assertThrows(RuntimeException.class, () -> planExecutor().execute(plan));
        // The failure-path cleanup in M2MSqlGraphSerializeHelper.execute's finally block must still
        // delete the helper's copied input temp file even though the setup SQL never let a
        // JsonStreamingResult (and its close-cascade Result) get constructed.
        assertEquals(tempFilesBefore, countM2mTempFiles());
    }

    private static RelationalDatabaseConnection duckDbConnection()
    {
        DuckDBDatasourceSpecification spec = new DuckDBDatasourceSpecification();
        spec.path = "";
        TestDatabaseAuthenticationStrategy auth = new TestDatabaseAuthenticationStrategy();
        RelationalDatabaseConnection conn = new RelationalDatabaseConnection(spec, auth, DatabaseType.DuckDB);
        conn.type = DatabaseType.DuckDB;
        return conn;
    }

    private static Path tempJsonFile(String content) throws IOException
    {
        Path path = Files.createTempFile("m2m-sql-graph-serialize-test-", ".json");
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static PlanExecutor planExecutor()
    {
        return PlanExecutor.newPlanExecutor(Relational.build());
    }

    private static SingleExecutionPlan singleNodePlan(SQLGraphSerializeExecutionNode node)
    {
        SingleExecutionPlan plan = new SingleExecutionPlan();
        plan.rootExecutionNode = node;
        plan.templateFunctions = Collections.emptyList();
        return plan;
    }

    private static String drainToString(JsonStreamingResult result)
    {
        return result.flush(result.getSerializer(SerializationFormat.DEFAULT));
    }

    /**
     * Counts files matching the temp-file naming pattern
     * {@code M2MSqlGraphSerializeHelper.execute} uses ({@code Files.createTempFile("m2m-sql-input-", ".json")})
     * in the system temp directory — used to assert that the helper's own copied input file (not
     * the source file {@link #tempJsonFile} creates) gets deleted by its close cascade.
     */
    private static long countM2mTempFiles() throws IOException
    {
        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> stream = Files.list(tmpDir))
        {
            return stream
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("m2m-sql-input-") && name.endsWith(".json"))
                    .count();
        }
    }
}
