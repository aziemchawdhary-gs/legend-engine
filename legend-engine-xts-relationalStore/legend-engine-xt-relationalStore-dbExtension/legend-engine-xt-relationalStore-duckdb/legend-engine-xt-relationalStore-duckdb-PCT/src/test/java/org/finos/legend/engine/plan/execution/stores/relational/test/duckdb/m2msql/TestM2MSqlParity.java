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

    private static final String ENUM_TRANSFORM_GRAMMAR =
            "Enum m2msql::enumTransform::SourceColor\n" +
            "{\n" +
            "   ROUGE, VERT, BLEU\n" +
            "}\n" +
            "Enum m2msql::enumTransform::TargetColor\n" +
            "{\n" +
            "   RED, GREEN, BLUE\n" +
            "}\n" +
            "Class m2msql::enumTransform::Item\n" +
            "{\n" +
            "   color: m2msql::enumTransform::TargetColor[1];\n" +
            "}\n" +
            "Class m2msql::enumTransform::S_Item\n" +
            "{\n" +
            "   color: m2msql::enumTransform::SourceColor[1];\n" +
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
            "      RED: [m2msql::enumTransform::SourceColor.ROUGE],\n" +
            "      GREEN: [m2msql::enumTransform::SourceColor.VERT],\n" +
            "      BLUE: [m2msql::enumTransform::SourceColor.BLEU]\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::enumTransform::S_Item;\n" +
            "  url: 'data:application/json,{\"color\":\"ROUGE\"}';\n" +
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

    private static final String ENUM_TRANSFORM_EXPECTED = "{\"builder\":{\"_type\":\"json\"},\"values\":{\"color\":\"RED\"}}";

    @Test
    void enumTransform()
    {
        assertParity("enumTransform", ENUM_TRANSFORM_GRAMMAR, ENUM_TRANSFORM_QUERY, ENUM_TRANSFORM_EXPECTED);
    }

    private static final String EXPLODE_MAPPING_GRAMMAR =
            "Class m2msql::explodeMapping::Tag\n" +
            "{\n" +
            "   name: String[1];\n" +
            "}\n" +
            "Class m2msql::explodeMapping::S_Item\n" +
            "{\n" +
            "   tags: String[*];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *m2msql::explodeMapping::Tag: Pure\n" +
            "   {\n" +
            "      ~src m2msql::explodeMapping::S_Item\n" +
            "      name* : $src.tags\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "${CONNECTION_TYPE} test::conn\n" +
            "{\n" +
            "  class: m2msql::explodeMapping::S_Item;\n" +
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

    private static final String EXPLODE_MAPPING_QUERY =
            "|m2msql::explodeMapping::Tag.all()->graphFetch(#{m2msql::explodeMapping::Tag{name}}#)->serialize(#{m2msql::explodeMapping::Tag{name}}#)";

    private static final String EXPLODE_MAPPING_EXPECTED = "{\"builder\":{\"_type\":\"json\"},\"values\":[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]}";

    @Test
    void explodeMapping()
    {
        assertParity("explodeMapping", EXPLODE_MAPPING_GRAMMAR, EXPLODE_MAPPING_QUERY, EXPLODE_MAPPING_EXPECTED);
    }
}
