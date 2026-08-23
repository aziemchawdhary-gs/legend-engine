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
import org.eclipse.collections.api.RichIterable;
import org.finos.legend.engine.language.pure.compiler.Compiler;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.HelperValueSpecificationBuilder;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.plan.execution.PlanExecutor;
import org.finos.legend.engine.plan.execution.result.StreamingResult;
import org.finos.legend.engine.plan.execution.result.serialization.SerializationFormat;
import org.finos.legend.engine.plan.execution.stores.inMemory.plugin.InMemory;
import org.finos.legend.engine.plan.generation.PlanGenerator;
import org.finos.legend.engine.plan.generation.transformers.LegendPlanTransformers;
import org.finos.legend.engine.plan.platform.PlanPlatform;
import org.finos.legend.engine.protocol.pure.m3.function.LambdaFunction;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.SingleExecutionPlan;
import org.finos.legend.engine.shared.core.deployment.DeploymentMode;
import org.finos.legend.engine.shared.core.identity.Identity;
import org.finos.legend.engine.shared.core.url.EngineUrlStreamHandlerFactory;
import org.finos.legend.pure.generated.Root_meta_pure_extension_Extension;
import org.finos.legend.pure.generated.core_java_platform_binding_legendJavaPlatformBinding_store_m2m_m2mLegendJavaPlatformBindingExtension;
import org.finos.legend.pure.m3.coreinstance.meta.pure.mapping.Mapping;
import org.finos.legend.pure.generated.Root_meta_core_runtime_Runtime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Parity harness for M2M SQL execution phase 1.
 *
 * Runs the same M2M model + mapping + query through a {@code JsonModelConnection} and a
 * {@code SqlModelConnection} and asserts identical serialized JSON output. As of this task,
 * {@code SqlModelConnection} has no SQL plan rewrite yet (that lands in a later task), so both
 * connection types take the same Java M2M execution path and every case must pass on parity.
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

    private String execute(String grammar, String queryFn)
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

            RichIterable<? extends Root_meta_pure_extension_Extension> extensions =
                    core_java_platform_binding_legendJavaPlatformBinding_store_m2m_m2mLegendJavaPlatformBindingExtension
                            .Root_meta_pure_mapping_modelToModel_executionPlan_platformBinding_legendJava_inMemoryExtensionsWithLegendJavaPlatformBinding__Extension_MANY_(pureModel.getExecutionSupport());

            SingleExecutionPlan plan = PlanGenerator.generateExecutionPlan(lambda, mapping, runtime, null, pureModel, "vX_X_X", PlanPlatform.JAVA, "test", extensions, LegendPlanTransformers.transformers);

            PlanExecutor executor = PlanExecutor.newPlanExecutor(InMemory.build());
            StreamingResult streamingResult = (StreamingResult) executor.execute(plan);
            return streamingResult.flush(streamingResult.getSerializer(SerializationFormat.DEFAULT));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private void assertParity(String grammarTemplate, String queryFn, String expectedJson)
    {
        String javaPath = execute(grammarTemplate.replace("${CONNECTION_TYPE}", "JsonModelConnection"), queryFn);
        String sqlPath = execute(grammarTemplate.replace("${CONNECTION_TYPE}", "SqlModelConnection"), queryFn);
        JsonAssert.assertJsonEquals(expectedJson, javaPath);
        JsonAssert.assertJsonEquals(javaPath, sqlPath);
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
        assertParity(SIMPLE_RENAME_GRAMMAR, SIMPLE_RENAME_QUERY, SIMPLE_RENAME_EXPECTED);
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
        assertParity(COMPUTED_PRIMITIVE_GRAMMAR, COMPUTED_PRIMITIVE_QUERY, COMPUTED_PRIMITIVE_EXPECTED);
    }

    private static final String NESTED_COMPLEX_TO_ONE_GRAMMAR =
            "Class m2msql::nestedComplexToOne::Address\n" +
            "{\n" +
            "   city: String[1];\n" +
            "}\n" +
            "Class m2msql::nestedComplexToOne::Person\n" +
            "{\n" +
            "   name: String[1];\n" +
            "   address: m2msql::nestedComplexToOne::Address[0..1];\n" +
            "}\n" +
            "Class m2msql::nestedComplexToOne::S_Address\n" +
            "{\n" +
            "   city: String[1];\n" +
            "}\n" +
            "Class m2msql::nestedComplexToOne::S_Person\n" +
            "{\n" +
            "   name: String[1];\n" +
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
            "  url: 'data:application/json,{\"name\":\"Jane\",\"address\":{\"city\":\"NYC\"}}';\n" +
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
            "|m2msql::nestedComplexToOne::Person.all()->graphFetch(#{m2msql::nestedComplexToOne::Person{name,address{city}}}#)->serialize(#{m2msql::nestedComplexToOne::Person{name,address{city}}}#)";

    private static final String NESTED_COMPLEX_TO_ONE_EXPECTED = "{\"builder\":{\"_type\":\"json\"},\"values\":{\"name\":\"Jane\",\"address\":{\"city\":\"NYC\"}}}";

    @Test
    void nestedComplexToOne()
    {
        assertParity(NESTED_COMPLEX_TO_ONE_GRAMMAR, NESTED_COMPLEX_TO_ONE_QUERY, NESTED_COMPLEX_TO_ONE_EXPECTED);
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
        assertParity(AUTO_MAPPED_GRAMMAR, AUTO_MAPPED_QUERY, AUTO_MAPPED_EXPECTED);
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
        assertParity(PRIMITIVE_COLLECTION_GRAMMAR, PRIMITIVE_COLLECTION_QUERY, PRIMITIVE_COLLECTION_EXPECTED);
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
        assertParity(MAPPING_FILTER_GRAMMAR, MAPPING_FILTER_QUERY, MAPPING_FILTER_EXPECTED);
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
        assertParity(ENUM_TRANSFORM_GRAMMAR, ENUM_TRANSFORM_QUERY, ENUM_TRANSFORM_EXPECTED);
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
        assertParity(EXPLODE_MAPPING_GRAMMAR, EXPLODE_MAPPING_QUERY, EXPLODE_MAPPING_EXPECTED);
    }
}
