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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.jsonunit.JsonAssert;
import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.engine.language.pure.compiler.Compiler;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.HelperValueSpecificationBuilder;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.plan.execution.PlanExecutor;
import org.finos.legend.engine.plan.execution.result.StreamingResult;
import org.finos.legend.engine.plan.execution.result.serialization.SerializationFormat;
import org.finos.legend.engine.plan.execution.stores.inMemory.plugin.InMemory;
import org.finos.legend.engine.plan.execution.stores.relational.plugin.Relational;
import org.finos.legend.engine.plan.generation.PlanGenerator;
import org.finos.legend.engine.plan.generation.transformers.LegendPlanTransformers;
import org.finos.legend.engine.plan.platform.PlanPlatform;
import org.finos.legend.engine.protocol.pure.m3.function.LambdaFunction;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.SingleExecutionPlan;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.finos.legend.engine.shared.core.deployment.DeploymentMode;
import org.finos.legend.engine.shared.core.identity.Identity;
import org.finos.legend.engine.shared.core.url.EngineUrlStreamHandlerFactory;
import org.finos.legend.pure.generated.Root_meta_pure_extension_Extension;
import org.finos.legend.pure.generated.core_relational_duckdb_relational_m2mSql_m2mSqlDuckDBExtension;
import org.finos.legend.pure.generated.core_relational_java_platform_binding_legendJavaPlatformBinding_relationalLegendJavaPlatformBindingExtension;
import org.finos.legend.pure.m3.coreinstance.meta.pure.mapping.Mapping;
import org.finos.legend.pure.generated.Root_meta_core_runtime_Runtime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity harness for M2M SQL execution phase 1.
 *
 * Runs the same M2M model + mapping + query through a {@code JsonModelConnection} and a
 * {@code SqlModelConnection} and asserts identical serialized JSON output. The
 * {@code SqlModelConnection} side additionally asserts plan shape: for a translatable case the
 * graph-fetch/serialize sub-plan must have collapsed into a single {@code sqlGraphSerialize} node
 * with no Java platform implementation left on it, and for a case on the fallback list
 * ({@code m2msql/expected-fallbacks.json}) the Java in-memory graph-fetch nodes must still be there.
 */
public class TestM2MSqlParity
{
    private static final String MAPPING_PATH = "test::mapping";
    private static final String RUNTIME_PATH = "test::runtime";

    @BeforeAll
    static void setUpUrls()
    {
        EngineUrlStreamHandlerFactory.initialize();
    }

    /**
     * Parses, compiles and plans the given grammar + query. Split out from {@link #runPlan} so a
     * caller that needs to inspect plan shape (e.g. SQL-collapsed vs Java-fallback, in a later
     * task) can do so without duplicating the parse&rarr;compile&rarr;plan chain.
     */
    private SingleExecutionPlan compileAndPlan(String grammar, String queryFn)
    {
        try
        {
            PureModelContextData contextData = PureGrammarParser.newInstance().parseModel(grammar);
            PureModel pureModel = Compiler.compile(contextData, DeploymentMode.TEST, Identity.getAnonymousIdentity().getName());

            LambdaFunction lambdaProtocol = PureGrammarParser.newInstance().parseLambda(queryFn);
            org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.function.LambdaFunction<?> lambda =
                    HelperValueSpecificationBuilder.buildLambda(lambdaProtocol.body, lambdaProtocol.parameters, pureModel.getContext());

            Mapping mapping = pureModel.getMapping(MAPPING_PATH);
            Root_meta_core_runtime_Runtime runtime = pureModel.getRuntime(RUNTIME_PATH);

            // Relational rather than in-memory-only extensions: the collapse plans a relational query
            // against the synthetic source store, so the relational store contract has to be active on
            // both sides of the comparison. duckDBM2MSqlExtension is what registers the collapse.
            RichIterable<Root_meta_pure_extension_Extension> extensions =
                    Lists.mutable.<Root_meta_pure_extension_Extension>empty()
                            .withAll(core_relational_java_platform_binding_legendJavaPlatformBinding_relationalLegendJavaPlatformBindingExtension
                                    .Root_meta_relational_executionPlan_platformBinding_legendJava_relationalExtensionsWithLegendJavaPlatformBinding__Extension_MANY_(pureModel.getExecutionSupport()))
                            .with(core_relational_duckdb_relational_m2mSql_m2mSqlDuckDBExtension
                                    .Root_meta_relational_m2mSql_duckDB_duckDBM2MSqlExtension__Extension_1_(pureModel.getExecutionSupport()));

            return PlanGenerator.generateExecutionPlan(lambda, mapping, runtime, null, pureModel, "vX_X_X", PlanPlatform.JAVA, "test", extensions, LegendPlanTransformers.transformers);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Executes an already-generated plan and returns its serialized JSON output.
     */
    private String runPlan(SingleExecutionPlan plan)
    {
        try
        {
            // Both store executors: the fallback cases run entirely in memory, the collapsed ones entirely
            // in DuckDB through the relational store executor.
            PlanExecutor executor = PlanExecutor.newPlanExecutor(InMemory.build(), Relational.build());
            StreamingResult streamingResult = (StreamingResult) executor.execute(plan);
            return streamingResult.flush(streamingResult.getSerializer(SerializationFormat.DEFAULT));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private String execute(String grammar, String queryFn)
    {
        return runPlan(compileAndPlan(grammar, queryFn));
    }

    /**
     * Runs one corpus case through both connection types and asserts byte parity, plus the shape of
     * the SQL-side plan. {@code caseName} is looked up in {@code m2msql/expected-fallbacks.json}: a
     * listed case must NOT collapse, an unlisted one must.
     */
    private void assertParity(String caseName, String grammarTemplate, String queryFn, String expectedJson)
    {
        String javaPath = execute(grammarTemplate.replace("${CONNECTION_TYPE}", "JsonModelConnection"), queryFn);

        SingleExecutionPlan sqlPlan = compileAndPlan(grammarTemplate.replace("${CONNECTION_TYPE}", "SqlModelConnection"), queryFn);
        assertPlanShape(caseName, sqlPlan);
        String sqlPath = runPlan(sqlPlan);

        JsonAssert.assertJsonEquals(expectedJson, javaPath);
        JsonAssert.assertJsonEquals(javaPath, sqlPath);
    }

    private void assertPlanShape(String caseName, SingleExecutionPlan plan)
    {
        JsonNode planJson = ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports().valueToTree(plan);
        Set<String> nodeTypes = new HashSet<>();
        boolean[] hasImplementation = {false};
        collectPlanShape(planJson, nodeTypes, hasImplementation);

        if (expectedFallbackCases().contains(caseName))
        {
            assertFalse(nodeTypes.contains("sqlGraphSerialize"), caseName + " is on the fallback list but was collapsed to SQL; node types: " + nodeTypes);
            assertTrue(nodeTypes.contains("inMemoryRootGraphFetch"), caseName + " must keep the Java in-memory graph fetch node; node types: " + nodeTypes);
            assertTrue(nodeTypes.contains("storeStreamReading"), caseName + " must keep the Java store-stream reading node; node types: " + nodeTypes);
        }
        else
        {
            assertTrue(nodeTypes.contains("sqlGraphSerialize"), caseName + " was expected to collapse to SQL; node types: " + nodeTypes);
            assertFalse(nodeTypes.contains("inMemoryRootGraphFetch"), caseName + " still carries an in-memory graph fetch node; node types: " + nodeTypes);
            assertFalse(nodeTypes.contains("storeStreamReading"), caseName + " still carries a store-stream reading node; node types: " + nodeTypes);
            // The whole point of collapsing before platform binding: no Java is generated for the
            // nodes that were removed, and the SQL node needs none of its own.
            assertFalse(hasImplementation[0], caseName + " collapsed but the plan still carries a Java platform implementation");
        }
    }

    private static void collectPlanShape(JsonNode node, Set<String> nodeTypes, boolean[] hasImplementation)
    {
        if (node.isObject())
        {
            JsonNode type = node.get("_type");
            if (type != null && type.isTextual())
            {
                nodeTypes.add(type.asText());
            }
            JsonNode implementation = node.get("implementation");
            if (implementation != null && !implementation.isNull())
            {
                hasImplementation[0] = true;
            }
        }
        for (JsonNode child : node)
        {
            collectPlanShape(child, nodeTypes, hasImplementation);
        }
    }

    private static Set<String> expectedFallbackCases()
    {
        try (InputStream in = Objects.requireNonNull(TestM2MSqlParity.class.getClassLoader().getResourceAsStream("m2msql/expected-fallbacks.json"), "m2msql/expected-fallbacks.json not on the test classpath"))
        {
            JsonNode fallbacks = new ObjectMapper().readTree(in);
            Set<String> names = new HashSet<>();
            fallbacks.forEach(f -> names.add(f.get("case").asText()));
            return names;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private static final String SIMPLE_RENAME_GRAMMAR =
            "Class m2msql::simpleRename::Person\n" +
            "{\n" +
            "   fullName: String[1];\n" +
            "}\n" +
            "Class m2msql::simpleRename::S_Person\n" +
            "{\n" +
            "   firstName: String[1];\n" +
            "   lastName: String[1];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::simpleRename::Person: Pure\n" +
            "   {\n" +
            "      ~src m2msql::simpleRename::S_Person\n" +
            "      fullName: $src.firstName + ' ' + $src.lastName\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::simpleRename::S_Person;\n" +
            "  url: 'data:application/json,{\"firstName\":\"Jane\",\"lastName\":\"Doe\"}';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    private static final String SIMPLE_RENAME_QUERY =
            "|m2msql::simpleRename::Person.all()->graphFetch(#{m2msql::simpleRename::Person{fullName}}#)->serialize(#{m2msql::simpleRename::Person{fullName}}#)";

    private static final String SIMPLE_RENAME_EXPECTED = "{\"builder\":{\"_type\":\"json\"},\"values\":{\"fullName\":\"Jane Doe\"}}";

    @Test
    void simpleRename()
    {
        assertParity("simpleRename", SIMPLE_RENAME_GRAMMAR, SIMPLE_RENAME_QUERY, SIMPLE_RENAME_EXPECTED);
    }

    private static final String COMPUTED_PRIMITIVE_GRAMMAR =
            "Class m2msql::computedPrimitive::Result\n" +
            "{\n" +
            "   product: Integer[1];\n" +
            "}\n" +
            "Class m2msql::computedPrimitive::S_Result\n" +
            "{\n" +
            "   a: Integer[1];\n" +
            "   b: Integer[1];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::computedPrimitive::Result: Pure\n" +
            "   {\n" +
            "      ~src m2msql::computedPrimitive::S_Result\n" +
            "      product: $src.a * $src.b\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::computedPrimitive::S_Result;\n" +
            "  url: 'data:application/json,{\"a\":6,\"b\":7}';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    private static final String COMPUTED_PRIMITIVE_QUERY =
            "|m2msql::computedPrimitive::Result.all()->graphFetch(#{m2msql::computedPrimitive::Result{product}}#)->serialize(#{m2msql::computedPrimitive::Result{product}}#)";

    private static final String COMPUTED_PRIMITIVE_EXPECTED = "{\"builder\":{\"_type\":\"json\"},\"values\":{\"product\":42}}";

    @Test
    void computedPrimitive()
    {
        assertParity("computedPrimitive", COMPUTED_PRIMITIVE_GRAMMAR, COMPUTED_PRIMITIVE_QUERY, COMPUTED_PRIMITIVE_EXPECTED);
    }

    private static final String NESTED_COMPLEX_TO_ONE_GRAMMAR =
            "Class m2msql::nestedComplexToOne::Address\n" +
            "{\n" +
            "   city: String[1];\n" +
            "}\n" +
            "Class m2msql::nestedComplexToOne::Person\n" +
            "{\n" +
            "   name: String[1];\n" +
            "   nickname: String[0..1];\n" +
            "   address: m2msql::nestedComplexToOne::Address[0..1];\n" +
            "}\n" +
            "Class m2msql::nestedComplexToOne::S_Address\n" +
            "{\n" +
            "   city: String[1];\n" +
            "}\n" +
            "Class m2msql::nestedComplexToOne::S_Person\n" +
            "{\n" +
            "   name: String[1];\n" +
            "   nickname: String[0..1];\n" +
            "   address: m2msql::nestedComplexToOne::S_Address[0..1];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::nestedComplexToOne::Person: Pure\n" +
            "   {\n" +
            "      ~src m2msql::nestedComplexToOne::S_Person\n" +
            "      name: $src.name,\n" +
            "      nickname: $src.nickname,\n" +
            "      address: $src.address\n" +
            "   }\n" +
            "   *m2msql::nestedComplexToOne::Address: Pure\n" +
            "   {\n" +
            "      ~src m2msql::nestedComplexToOne::S_Address\n" +
            "      city: $src.city\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::nestedComplexToOne::S_Person;\n" +
            "  url: 'data:application/json,[{\"name\":\"Jane\",\"nickname\":\"Janey\",\"address\":{\"city\":\"NYC\"}},{\"name\":\"John\"}]';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    private static final String NESTED_COMPLEX_TO_ONE_QUERY =
            "|m2msql::nestedComplexToOne::Person.all()->graphFetch(#{m2msql::nestedComplexToOne::Person{name,nickname,address{city}}}#)->serialize(#{m2msql::nestedComplexToOne::Person{name,nickname,address{city}}}#)";

    private static final String NESTED_COMPLEX_TO_ONE_EXPECTED =
            "{\"builder\":{\"_type\":\"json\"},\"values\":["
            + "{\"name\":\"Jane\",\"nickname\":\"Janey\",\"address\":{\"city\":\"NYC\"}},"
            + "{\"name\":\"John\",\"nickname\":null,\"address\":null}"
            + "]}";

    @Test
    void nestedComplexToOne()
    {
        assertParity("nestedComplexToOne", NESTED_COMPLEX_TO_ONE_GRAMMAR, NESTED_COMPLEX_TO_ONE_QUERY, NESTED_COMPLEX_TO_ONE_EXPECTED);
    }

    // The filter navigates through a complex target property, which the root-filter rewrite does not
    // follow, so the whole query stays on the Java path.
    private static final String ROOT_FILTER_DEEP_NAVIGATION_QUERY =
            "|m2msql::nestedComplexToOne::Person.all()->filter(p | $p.address.city == 'NYC')->graphFetch(#{m2msql::nestedComplexToOne::Person{name,nickname,address{city}}}#)->serialize(#{m2msql::nestedComplexToOne::Person{name,nickname,address{city}}}#)";

    private static final String ROOT_FILTER_DEEP_NAVIGATION_EXPECTED =
            "{\"builder\":{\"_type\":\"json\"},\"values\":{\"name\":\"Jane\",\"nickname\":\"Janey\",\"address\":{\"city\":\"NYC\"}}}";

    @Test
    void rootFilterDeepNavigation()
    {
        assertParity("rootFilterDeepNavigation", NESTED_COMPLEX_TO_ONE_GRAMMAR, ROOT_FILTER_DEEP_NAVIGATION_QUERY, ROOT_FILTER_DEEP_NAVIGATION_EXPECTED);
    }

    // The filter is over the target class: fullName is a computed target property, and age is filtered
    // on without being fetched, so its source property is only reachable through the filter.
    private static final String ROOT_FILTER_GRAMMAR =
            "Class m2msql::rootFilter::Person\n" +
            "{\n" +
            "   fullName: String[1];\n" +
            "   age: Integer[1];\n" +
            "}\n" +
            "Class m2msql::rootFilter::S_Person\n" +
            "{\n" +
            "   firstName: String[1];\n" +
            "   lastName: String[1];\n" +
            "   years: Integer[1];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::rootFilter::Person: Pure\n" +
            "   {\n" +
            "      ~src m2msql::rootFilter::S_Person\n" +
            "      fullName: $src.firstName + ' ' + $src.lastName,\n" +
            "      age: $src.years\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::rootFilter::S_Person;\n" +
            "  url: 'data:application/json,[{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"years\":30},{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"years\":10},{\"firstName\":\"John\",\"lastName\":\"Roe\",\"years\":40}]';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    private static final String ROOT_FILTER_QUERY =
            "|m2msql::rootFilter::Person.all()->filter(p | ($p.fullName == 'Jane Doe') && ($p.age >= 18))->graphFetch(#{m2msql::rootFilter::Person{fullName}}#)->serialize(#{m2msql::rootFilter::Person{fullName}}#)";

    private static final String ROOT_FILTER_EXPECTED = "{\"builder\":{\"_type\":\"json\"},\"values\":{\"fullName\":\"Jane Doe\"}}";

    @Test
    void rootFilter()
    {
        assertParity("rootFilter", ROOT_FILTER_GRAMMAR, ROOT_FILTER_QUERY, ROOT_FILTER_EXPECTED);
    }

    private static final String AUTO_MAPPED_GRAMMAR =
            "Class m2msql::autoMapped::Item\n" +
            "{\n" +
            "   name: String[1];\n" +
            "}\n" +
            "Class m2msql::autoMapped::S_Item\n" +
            "{\n" +
            "   name: String[1];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::autoMapped::Item: Pure\n" +
            "   {\n" +
            "      ~src m2msql::autoMapped::S_Item\n" +
            "      name: $src.name\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::autoMapped::S_Item;\n" +
            "  url: 'data:application/json,{\"name\":\"Widget\"}';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    private static final String AUTO_MAPPED_QUERY =
            "|m2msql::autoMapped::Item.all()->graphFetch(#{m2msql::autoMapped::Item{name}}#)->serialize(#{m2msql::autoMapped::Item{name}}#)";

    private static final String AUTO_MAPPED_EXPECTED = "{\"builder\":{\"_type\":\"json\"},\"values\":{\"name\":\"Widget\"}}";

    @Test
    void autoMapped()
    {
        assertParity("autoMapped", AUTO_MAPPED_GRAMMAR, AUTO_MAPPED_QUERY, AUTO_MAPPED_EXPECTED);
    }

    private static final String PRIMITIVE_COLLECTION_GRAMMAR =
            "Class m2msql::primitiveCollection::Item\n" +
            "{\n" +
            "   tags: String[*];\n" +
            "}\n" +
            "Class m2msql::primitiveCollection::S_Item\n" +
            "{\n" +
            "   tags: String[*];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::primitiveCollection::Item: Pure\n" +
            "   {\n" +
            "      ~src m2msql::primitiveCollection::S_Item\n" +
            "      tags: $src.tags\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::primitiveCollection::S_Item;\n" +
            "  url: 'data:application/json,{\"tags\":[\"a\",\"b\",\"c\"]}';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    private static final String PRIMITIVE_COLLECTION_QUERY =
            "|m2msql::primitiveCollection::Item.all()->graphFetch(#{m2msql::primitiveCollection::Item{tags}}#)->serialize(#{m2msql::primitiveCollection::Item{tags}}#)";

    private static final String PRIMITIVE_COLLECTION_EXPECTED = "{\"builder\":{\"_type\":\"json\"},\"values\":{\"tags\":[\"a\",\"b\",\"c\"]}}";

    @Test
    void primitiveCollection()
    {
        assertParity("primitiveCollection", PRIMITIVE_COLLECTION_GRAMMAR, PRIMITIVE_COLLECTION_QUERY, PRIMITIVE_COLLECTION_EXPECTED);
    }

    private static final String MAPPING_FILTER_GRAMMAR =
            "Class m2msql::mappingFilter::Person\n" +
            "{\n" +
            "   name: String[1];\n" +
            "}\n" +
            "Class m2msql::mappingFilter::S_Person\n" +
            "{\n" +
            "   name: String[1];\n" +
            "   active: Boolean[1];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::mappingFilter::Person: Pure\n" +
            "   {\n" +
            "      ~src m2msql::mappingFilter::S_Person\n" +
            "      ~filter $src.active\n" +
            "      name: $src.name\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::mappingFilter::S_Person;\n" +
            "  url: 'data:application/json,[{\"name\":\"Jane\",\"active\":true},{\"name\":\"John\",\"active\":false}]';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    private static final String MAPPING_FILTER_QUERY =
            "|m2msql::mappingFilter::Person.all()->graphFetch(#{m2msql::mappingFilter::Person{name}}#)->serialize(#{m2msql::mappingFilter::Person{name}}#)";

    private static final String MAPPING_FILTER_EXPECTED = "{\"builder\":{\"_type\":\"json\"},\"values\":{\"name\":\"Jane\"}}";

    @Test
    void mappingFilter()
    {
        assertParity("mappingFilter", MAPPING_FILTER_GRAMMAR, MAPPING_FILTER_QUERY, MAPPING_FILTER_EXPECTED);
    }

    // Zed is old enough, so it is the negated equality that has to exclude him; the 17-year-old is
    // excluded by the age comparison.
    private static final String COMPARISON_FILTER_GRAMMAR =
            "Class m2msql::comparisonFilter::Person\n" +
            "{\n" +
            "   name: String[1];\n" +
            "}\n" +
            "Class m2msql::comparisonFilter::S_Person\n" +
            "{\n" +
            "   name: String[1];\n" +
            "   age: Integer[1];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::comparisonFilter::Person: Pure\n" +
            "   {\n" +
            "      ~src m2msql::comparisonFilter::S_Person\n" +
            "      ~filter ($src.age >= 18) && !($src.name == 'Zed')\n" +
            "      name: $src.name\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::comparisonFilter::S_Person;\n" +
            "  url: 'data:application/json,[{\"name\":\"Teen\",\"age\":17},{\"name\":\"Adult\",\"age\":18},{\"name\":\"Zed\",\"age\":40}]';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    private static final String COMPARISON_FILTER_QUERY =
            "|m2msql::comparisonFilter::Person.all()->graphFetch(#{m2msql::comparisonFilter::Person{name}}#)->serialize(#{m2msql::comparisonFilter::Person{name}}#)";

    private static final String COMPARISON_FILTER_EXPECTED = "{\"builder\":{\"_type\":\"json\"},\"values\":{\"name\":\"Adult\"}}";

    @Test
    void comparisonFilter()
    {
        assertParity("comparisonFilter", COMPARISON_FILTER_GRAMMAR, COMPARISON_FILTER_QUERY, COMPARISON_FILTER_EXPECTED);
    }

    // The same enumeration on both sides, no enumeration mapping: the value passes through as its name.
    // The second row has no colour at all.
    private static final String ENUM_IDENTITY_GRAMMAR =
            "Enum m2msql::enumIdentity::Color\n" +
            "{\n" +
            "   RED, GREEN\n" +
            "}\n" +
            "Class m2msql::enumIdentity::Item\n" +
            "{\n" +
            "   color: m2msql::enumIdentity::Color[0..1];\n" +
            "}\n" +
            "Class m2msql::enumIdentity::S_Item\n" +
            "{\n" +
            "   color: m2msql::enumIdentity::Color[0..1];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::enumIdentity::Item: Pure\n" +
            "   {\n" +
            "      ~src m2msql::enumIdentity::S_Item\n" +
            "      color: $src.color\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::enumIdentity::S_Item;\n" +
            "  url: 'data:application/json,[{\"color\":\"RED\"},{}]';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    private static final String ENUM_IDENTITY_QUERY =
            "|m2msql::enumIdentity::Item.all()->graphFetch(#{m2msql::enumIdentity::Item{color}}#)->serialize(#{m2msql::enumIdentity::Item{color}}#)";

    private static final String ENUM_IDENTITY_EXPECTED = "{\"builder\":{\"_type\":\"json\"},\"values\":[{\"color\":\"RED\"},{\"color\":null}]}";

    @Test
    void enumIdentity()
    {
        assertParity("enumIdentity", ENUM_IDENTITY_GRAMMAR, ENUM_IDENTITY_QUERY, ENUM_IDENTITY_EXPECTED);
    }

    private static final String ENUM_TRANSFORM_GRAMMAR =
            "Enum m2msql::enumTransform::SourceColor\n" +
            "{\n" +
            "   ROUGE, CARMIN, VERT, BLEU\n" +
            "}\n" +
            "Enum m2msql::enumTransform::TargetColor\n" +
            "{\n" +
            "   RED, GREEN, BLUE\n" +
            "}\n" +
            "Class m2msql::enumTransform::Item\n" +
            "{\n" +
            "   color: m2msql::enumTransform::TargetColor[0..1];\n" +
            "}\n" +
            "Class m2msql::enumTransform::S_Item\n" +
            "{\n" +
            "   color: m2msql::enumTransform::SourceColor[0..1];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::enumTransform::Item: Pure\n" +
            "   {\n" +
            "      ~src m2msql::enumTransform::S_Item\n" +
            "      color: EnumerationMapping colorMapping : $src.color\n" +
            "   }\n" +
            "   m2msql::enumTransform::TargetColor: EnumerationMapping colorMapping\n" +
            "   {\n" +
            "      RED: [m2msql::enumTransform::SourceColor.ROUGE, m2msql::enumTransform::SourceColor.CARMIN],\n" +
            "      GREEN: [m2msql::enumTransform::SourceColor.VERT],\n" +
            "      BLUE: [m2msql::enumTransform::SourceColor.BLEU]\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::enumTransform::S_Item;\n" +
            "  url: '${URL}';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    private static final String ENUM_TRANSFORM_QUERY =
            "|m2msql::enumTransform::Item.all()->graphFetch(#{m2msql::enumTransform::Item{color}}#)->serialize(#{m2msql::enumTransform::Item{color}}#)";

    // Two source values mapping to one target value, a second target value, and a row with no colour.
    private static final String ENUM_TRANSFORM_URL = "data:application/json,[{\"color\":\"ROUGE\"},{\"color\":\"CARMIN\"},{\"color\":\"VERT\"},{}]";

    private static final String ENUM_TRANSFORM_EXPECTED = "{\"builder\":{\"_type\":\"json\"},\"values\":[{\"color\":\"RED\"},{\"color\":\"RED\"},{\"color\":\"GREEN\"},{\"color\":null}]}";

    @Test
    void enumTransform()
    {
        assertParity("enumTransform", ENUM_TRANSFORM_GRAMMAR.replace("${URL}", ENUM_TRANSFORM_URL), ENUM_TRANSFORM_QUERY, ENUM_TRANSFORM_EXPECTED);
    }

    // A source value the enumeration mapping has no entry for is a hard failure on the Java path, and
    // must not quietly become a null or a pass-through on the SQL path.
    @Test
    void enumTransformUnmatched()
    {
        String grammar = ENUM_TRANSFORM_GRAMMAR.replace("${URL}", "data:application/json,{\"color\":\"NOIR\"}");

        assertThrows(RuntimeException.class, () -> execute(grammar.replace("${CONNECTION_TYPE}", "JsonModelConnection"), ENUM_TRANSFORM_QUERY));

        SingleExecutionPlan sqlPlan = compileAndPlan(grammar.replace("${CONNECTION_TYPE}", "SqlModelConnection"), ENUM_TRANSFORM_QUERY);
        assertPlanShape("enumTransformUnmatched", sqlPlan);
        RuntimeException sqlFailure = assertThrows(RuntimeException.class, () -> runPlan(sqlPlan));
        assertTrue(String.valueOf(rootCause(sqlFailure).getMessage()).contains("Unmatched enum source value for property color"), "unexpected SQL-path failure: " + rootCause(sqlFailure));
    }

    // A primitive collection is only translated for strings, so this one stays on the Java path.
    private static final String INTEGER_COLLECTION_GRAMMAR =
            "Class m2msql::integerCollection::Item\n" +
            "{\n" +
            "   codes: Integer[*];\n" +
            "}\n" +
            "Class m2msql::integerCollection::S_Item\n" +
            "{\n" +
            "   codes: Integer[*];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::integerCollection::Item: Pure\n" +
            "   {\n" +
            "      ~src m2msql::integerCollection::S_Item\n" +
            "      codes: $src.codes\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::integerCollection::S_Item;\n" +
            "  url: 'data:application/json,{\"codes\":[1,2,3]}';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    @Test
    void integerCollection()
    {
        assertParity("integerCollection", INTEGER_COLLECTION_GRAMMAR,
                "|m2msql::integerCollection::Item.all()->graphFetch(#{m2msql::integerCollection::Item{codes}}#)->serialize(#{m2msql::integerCollection::Item{codes}}#)",
                "{\"builder\":{\"_type\":\"json\"},\"values\":{\"codes\":[1,2,3]}}");
    }

    // Nothing but the exploded leaf is selected, and its elements are not strings: the inner query has
    // no other column to project, and the unnest has to be typed by the element.
    private static final String EXPLODE_INTEGER_GRAMMAR =
            "Class m2msql::explodeInteger::Code\n" +
            "{\n" +
            "   code: Integer[1];\n" +
            "}\n" +
            "Class m2msql::explodeInteger::S_Item\n" +
            "{\n" +
            "   codes: Integer[*];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::explodeInteger::Code: Pure\n" +
            "   {\n" +
            "      ~src m2msql::explodeInteger::S_Item\n" +
            "      code* : $src.codes\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::explodeInteger::S_Item;\n" +
            "  url: 'data:application/json,{\"codes\":[1,2,3]}';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    @Test
    void explodeInteger()
    {
        assertParity("explodeInteger", EXPLODE_INTEGER_GRAMMAR,
                "|m2msql::explodeInteger::Code.all()->graphFetch(#{m2msql::explodeInteger::Code{code}}#)->serialize(#{m2msql::explodeInteger::Code{code}}#)",
                "{\"builder\":{\"_type\":\"json\"},\"values\":[{\"code\":1},{\"code\":2},{\"code\":3}]}");
    }

    // `status` is optional and the first row has none. An equality against an absent value is false on
    // the Java path and NULL in SQL: the same thing for a filter on its own, not under a negation.
    private static final String OPTIONAL_FILTER_GRAMMAR =
            "Class m2msql::optionalFilter::Person\n" +
            "{\n" +
            "   name: String[1];\n" +
            "}\n" +
            "Class m2msql::optionalFilter::S_Person\n" +
            "{\n" +
            "   name: String[1];\n" +
            "   status: String[0..1];\n" +
            "   region: String[1];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::optionalFilter::Person: Pure\n" +
            "   {\n" +
            "      ~src m2msql::optionalFilter::S_Person\n" +
            "      ~filter ${FILTER}\n" +
            "      name: $src.name\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::optionalFilter::S_Person;\n" +
            "  url: 'data:application/json,[{\"name\":\"a\",\"region\":\"EU\"},{\"name\":\"b\",\"status\":\"X\",\"region\":\"EU\"}]';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    private static final String OPTIONAL_FILTER_QUERY = "|m2msql::optionalFilter::Person.all()->graphFetch(#{m2msql::optionalFilter::Person{name}}#)->serialize(#{m2msql::optionalFilter::Person{name}}#)";

    @Test
    void optionalEqualityFilter()
    {
        assertParity("optionalEqualityFilter", OPTIONAL_FILTER_GRAMMAR.replace("${FILTER}", "$src.status == 'X'"), OPTIONAL_FILTER_QUERY, "{\"builder\":{\"_type\":\"json\"},\"values\":{\"name\":\"b\"}}");
    }

    @Test
    void negatedCompoundFilter()
    {
        assertParity("negatedCompoundFilter", OPTIONAL_FILTER_GRAMMAR.replace("${FILTER}", "!(($src.status == 'X') && ($src.region == 'EU'))"), OPTIONAL_FILTER_QUERY, "{\"builder\":{\"_type\":\"json\"},\"values\":{\"name\":\"a\"}}");
    }

    // A comparison as a property value: false for an absent title on the Java path, NULL in SQL.
    private static final String BOOLEAN_TRANSFORM_GRAMMAR =
            "Class m2msql::booleanTransform::Person\n" +
            "{\n" +
            "   isBoss: Boolean[1];\n" +
            "}\n" +
            "Class m2msql::booleanTransform::S_Person\n" +
            "{\n" +
            "   title: String[0..1];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::booleanTransform::Person: Pure\n" +
            "   {\n" +
            "      ~src m2msql::booleanTransform::S_Person\n" +
            "      isBoss: $src.title == 'Boss'\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::booleanTransform::S_Person;\n" +
            "  url: 'data:application/json,[{},{\"title\":\"Boss\"}]';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    @Test
    void booleanTransform()
    {
        assertParity("booleanTransform", BOOLEAN_TRANSFORM_GRAMMAR, "|m2msql::booleanTransform::Person.all()->graphFetch(#{m2msql::booleanTransform::Person{isBoss}}#)->serialize(#{m2msql::booleanTransform::Person{isBoss}}#)", "{\"builder\":{\"_type\":\"json\"},\"values\":[{\"isBoss\":false},{\"isBoss\":true}]}");
    }

    // A collection populated from a scalar, and a scalar taken from a collection: the inner query
    // extracts by the source property's own shape, which is not the shape the document needs.
    private static final String MULTIPLICITY_COERCION_GRAMMAR =
            "Class m2msql::multiplicityCoercion::Item\n" +
            "{\n" +
            "   aliases: String[*];\n" +
            "   first: String[1];\n" +
            "}\n" +
            "Class m2msql::multiplicityCoercion::S_Item\n" +
            "{\n" +
            "   nickname: String[0..1];\n" +
            "   names: String[*];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::multiplicityCoercion::Item: Pure\n" +
            "   {\n" +
            "      ~src m2msql::multiplicityCoercion::S_Item\n" +
            "      aliases: $src.nickname,\n" +
            "      first: $src.names->toOne()\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::multiplicityCoercion::S_Item;\n" +
            "  url: 'data:application/json,[{\"nickname\":\"Janey\",\"names\":[\"a\"]},{\"names\":[\"b\"]}]';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    @Test
    void multiplicityCoercion()
    {
        assertParity("multiplicityCoercion", MULTIPLICITY_COERCION_GRAMMAR, "|m2msql::multiplicityCoercion::Item.all()->graphFetch(#{m2msql::multiplicityCoercion::Item{aliases,first}}#)->serialize(#{m2msql::multiplicityCoercion::Item{aliases,first}}#)",
                "{\"builder\":{\"_type\":\"json\"},\"values\":[{\"aliases\":[\"Janey\"],\"first\":\"a\"},{\"aliases\":[],\"first\":\"b\"}]}");
    }

    // `address` has no property mapping. The Java path only auto-maps data-typed properties, so a
    // complex one is left empty even though the source has a same-named object.
    private static final String UNMAPPED_COMPLEX_GRAMMAR =
            "Class m2msql::unmappedComplex::Address\n" +
            "{\n" +
            "   city: String[1];\n" +
            "}\n" +
            "Class m2msql::unmappedComplex::Person\n" +
            "{\n" +
            "   name: String[1];\n" +
            "   address: m2msql::unmappedComplex::Address[0..1];\n" +
            "}\n" +
            "Class m2msql::unmappedComplex::S_Address\n" +
            "{\n" +
            "   city: String[1];\n" +
            "}\n" +
            "Class m2msql::unmappedComplex::S_Person\n" +
            "{\n" +
            "   name: String[1];\n" +
            "   address: m2msql::unmappedComplex::S_Address[0..1];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::unmappedComplex::Person: Pure\n" +
            "   {\n" +
            "      ~src m2msql::unmappedComplex::S_Person\n" +
            "      name: $src.name\n" +
            "   }\n" +
            "   *m2msql::unmappedComplex::Address: Pure\n" +
            "   {\n" +
            "      ~src m2msql::unmappedComplex::S_Address\n" +
            "      city: $src.city\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::unmappedComplex::S_Person;\n" +
            "  url: 'data:application/json,{\"name\":\"J\",\"address\":{\"city\":\"NYC\"}}';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    @Test
    void unmappedComplex()
    {
        assertParity("unmappedComplex", UNMAPPED_COMPLEX_GRAMMAR, "|m2msql::unmappedComplex::Person.all()->graphFetch(#{m2msql::unmappedComplex::Person{name,address{city}}}#)->serialize(#{m2msql::unmappedComplex::Person{name,address{city}}}#)",
                "{\"builder\":{\"_type\":\"json\"},\"values\":{\"name\":\"J\",\"address\":null}}");
    }

    // Explode together with everything else that shapes the rows: a mapping filter (i2 is inactive), a
    // source row with no `tags` key at all (i3), and a nested object that is present on one exploded
    // row group and absent on another.
    private static final String EXPLODE_COMPOSITE_GRAMMAR =
            "Class m2msql::explodeComposite::Owner\n" +
            "{\n" +
            "   name: String[1];\n" +
            "}\n" +
            "Class m2msql::explodeComposite::Tag\n" +
            "{\n" +
            "   item: String[1];\n" +
            "   name: String[1];\n" +
            "   owner: m2msql::explodeComposite::Owner[0..1];\n" +
            "}\n" +
            "Class m2msql::explodeComposite::S_Owner\n" +
            "{\n" +
            "   name: String[1];\n" +
            "}\n" +
            "Class m2msql::explodeComposite::S_Item\n" +
            "{\n" +
            "   id: String[1];\n" +
            "   active: Boolean[1];\n" +
            "   tags: String[*];\n" +
            "   owner: m2msql::explodeComposite::S_Owner[0..1];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::explodeComposite::Tag: Pure\n" +
            "   {\n" +
            "      ~src m2msql::explodeComposite::S_Item\n" +
            "      ~filter $src.active\n" +
            "      item: $src.id,\n" +
            "      name* : $src.tags,\n" +
            "      owner: $src.owner\n" +
            "   }\n" +
            "   *m2msql::explodeComposite::Owner: Pure\n" +
            "   {\n" +
            "      ~src m2msql::explodeComposite::S_Owner\n" +
            "      name: $src.name\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::explodeComposite::S_Item;\n" +
            "  url: 'data:application/json,[{\"id\":\"i1\",\"active\":true,\"tags\":[\"a\",\"b\"],\"owner\":{\"name\":\"O1\"}},{\"id\":\"i2\",\"active\":false,\"tags\":[\"c\"],\"owner\":{\"name\":\"O2\"}},{\"id\":\"i3\",\"active\":true,\"owner\":{\"name\":\"O3\"}},{\"id\":\"i4\",\"active\":true,\"tags\":[\"d\"]}]';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    @Test
    void explodeComposite()
    {
        assertParity("explodeComposite", EXPLODE_COMPOSITE_GRAMMAR, "|m2msql::explodeComposite::Tag.all()->graphFetch(#{m2msql::explodeComposite::Tag{item,name,owner{name}}}#)->serialize(#{m2msql::explodeComposite::Tag{item,name,owner{name}}}#)",
                "{\"builder\":{\"_type\":\"json\"},\"values\":[{\"item\":\"i1\",\"name\":\"a\",\"owner\":{\"name\":\"O1\"}},{\"item\":\"i1\",\"name\":\"b\",\"owner\":{\"name\":\"O1\"}},{\"item\":\"i4\",\"name\":\"d\",\"owner\":null}]}");
    }

    @Test
    void explodeCompositeRootFilter()
    {
        assertParity("explodeCompositeRootFilter", EXPLODE_COMPOSITE_GRAMMAR,
                "|m2msql::explodeComposite::Tag.all()->filter(t | $t.item == 'i1')->graphFetch(#{m2msql::explodeComposite::Tag{item,name,owner{name}}}#)->serialize(#{m2msql::explodeComposite::Tag{item,name,owner{name}}}#)",
                "{\"builder\":{\"_type\":\"json\"},\"values\":[{\"item\":\"i1\",\"name\":\"a\",\"owner\":{\"name\":\"O1\"}},{\"item\":\"i1\",\"name\":\"b\",\"owner\":{\"name\":\"O1\"}}]}");
    }

    // The exploded property is fetched but not serialized. The Java path still yields one instance per
    // element; a document projected from the serialize tree alone would not.
    @Test
    void explodeNarrowSerialize()
    {
        assertParity("explodeNarrowSerialize",
                EXPLODE_MAPPING_GRAMMAR.replace("${URL}", "data:application/json,[{\"id\":\"i1\",\"tags\":[\"a\",\"b\"]},{\"id\":\"i2\",\"tags\":[]}]"),
                "|m2msql::explodeMapping::Tag.all()->graphFetch(#{m2msql::explodeMapping::Tag{item,name}}#)->serialize(#{m2msql::explodeMapping::Tag{item}}#)",
                "{\"builder\":{\"_type\":\"json\"},\"values\":[{\"item\":\"i1\"},{\"item\":\"i1\"}]}");
    }

    private static Throwable rootCause(Throwable t)
    {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause)
        {
            cause = cause.getCause();
        }
        return cause;
    }

    private static final String EXPLODE_MAPPING_GRAMMAR =
            "Class m2msql::explodeMapping::Tag\n" +
            "{\n" +
            "   item: String[1];\n" +
            "   name: String[1];\n" +
            "}\n" +
            "Class m2msql::explodeMapping::S_Item\n" +
            "{\n" +
            "   id: String[1];\n" +
            "   tags: String[*];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::explodeMapping::Tag: Pure\n" +
            "   {\n" +
            "      ~src m2msql::explodeMapping::S_Item\n" +
            "      item: $src.id,\n" +
            "      name* : $src.tags\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::explodeMapping::S_Item;\n" +
            "  url: '${URL}';\n" +
            "}\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime test::runtime\n" +
            "{\n" +
            "  mappings: [test::mapping];\n" +
            "  connections:\n" +
            "  [\n" +
            "    ModelStore:\n" +
            "    [\n" +
            "      id1: test::conn\n" +
            "    ]\n" +
            "  ];\n" +
            "}\n";

    private static final String EXPLODE_MAPPING_QUERY =
            "|m2msql::explodeMapping::Tag.all()->graphFetch(#{m2msql::explodeMapping::Tag{name}}#)->serialize(#{m2msql::explodeMapping::Tag{name}}#)";

    private static final String EXPLODE_MAPPING_EXPECTED = "{\"builder\":{\"_type\":\"json\"},\"values\":[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]}";

    @Test
    void explodeMapping()
    {
        assertParity("explodeMapping", EXPLODE_MAPPING_GRAMMAR.replace("${URL}", "data:application/json,{\"id\":\"i1\",\"tags\":[\"a\",\"b\",\"c\"]}"), EXPLODE_MAPPING_QUERY, EXPLODE_MAPPING_EXPECTED);
    }

    // The only source row has nothing to explode, so there is no target instance at all.
    @Test
    void explodeMappingEmpty()
    {
        assertParity("explodeMappingEmpty", EXPLODE_MAPPING_GRAMMAR.replace("${URL}", "data:application/json,{\"id\":\"i1\",\"tags\":[]}"), EXPLODE_MAPPING_QUERY, "{\"builder\":{\"_type\":\"json\"},\"values\":[]}");
    }

    // A sibling property repeats on every exploded row; a source row with nothing to explode
    // contributes none, in between two that do.
    private static final String EXPLODE_MAPPING_SIBLING_QUERY =
            "|m2msql::explodeMapping::Tag.all()->graphFetch(#{m2msql::explodeMapping::Tag{item,name}}#)->serialize(#{m2msql::explodeMapping::Tag{item,name}}#)";

    @Test
    void explodeMappingSibling()
    {
        assertParity("explodeMappingSibling",
                EXPLODE_MAPPING_GRAMMAR.replace("${URL}", "data:application/json,[{\"id\":\"i1\",\"tags\":[\"a\",\"b\"]},{\"id\":\"i2\",\"tags\":[]},{\"id\":\"i3\",\"tags\":[\"c\"]}]"),
                EXPLODE_MAPPING_SIBLING_QUERY,
                "{\"builder\":{\"_type\":\"json\"},\"values\":[{\"item\":\"i1\",\"name\":\"a\"},{\"item\":\"i1\",\"name\":\"b\"},{\"item\":\"i3\",\"name\":\"c\"}]}");
    }
}
