//  Copyright 2026 Goldman Sachs
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.

package org.finos.legend.engine.server.test.m2msql;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.block.function.Function;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.engine.language.pure.compiler.Compiler;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.HelperValueSpecificationBuilder;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.language.pure.modelManager.ModelManager;
import org.finos.legend.engine.plan.execution.PlanExecutor;
import org.finos.legend.engine.plan.execution.result.serialization.SerializationFormat;
import org.finos.legend.engine.plan.execution.stores.inMemory.plugin.InMemory;
import org.finos.legend.engine.plan.execution.stores.relational.plugin.Relational;
import org.finos.legend.engine.plan.generation.PlanGenerator;
import org.finos.legend.engine.plan.generation.extension.PlanGeneratorExtension;
import org.finos.legend.engine.plan.generation.transformers.PlanTransformer;
import org.finos.legend.engine.plan.platform.PlanPlatform;
import org.finos.legend.engine.protocol.pure.m3.function.LambdaFunction;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.SingleExecutionPlan;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.runtime.RuntimePointer;
import org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.raw.executionContext.BaseExecutionContext;
import org.finos.legend.engine.pure.code.core.PureCoreExtensionLoader;
import org.finos.legend.engine.query.pure.api.Execute;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.finos.legend.engine.shared.core.api.model.ExecuteInput;
import org.finos.legend.engine.shared.core.deployment.DeploymentMode;
import org.finos.legend.engine.shared.core.identity.Identity;
import org.finos.legend.engine.shared.core.url.EngineUrlStreamHandlerFactory;
import org.finos.legend.pure.generated.Root_meta_pure_extension_Extension;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ServiceLoader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The M2M SQL collapse is registered only through SPI (a {@code PureCoreExtension} in the DuckDB
 * module). The parity harness passes its extensions explicitly, so it proves the translation but not
 * that the assembled server actually loads it. This builds the router extensions, plan transformers
 * and store executors exactly as {@code Server} does and checks a {@code SqlModelConnection} query
 * both collapses and executes through the {@code Execute} resource.
 */
public class TestM2MSqlServerWiring
{
    private static final String GRAMMAR =
            "Class test::Person\n" +
            "{\n" +
            "   fullName: String[1];\n" +
            "}\n" +
            "Class test::S_Person\n" +
            "{\n" +
            "   firstName: String[1];\n" +
            "   lastName: String[1];\n" +
            "}\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping test::mapping\n" +
            "(\n" +
            "   *test::Person: Pure\n" +
            "   {\n" +
            "      ~src test::S_Person\n" +
            "      fullName: $src.firstName + ' ' + $src.lastName\n" +
            "   }\n" +
            ")\n" +
            "\n" +
            "###Connection\n" +
            "SqlModelConnection test::conn\n" +
            "{\n" +
            "  class: test::S_Person;\n" +
            "  url: 'data:application/json,[{\"firstName\":\"Jane\",\"lastName\":\"Doe\"},{\"firstName\":\"John\",\"lastName\":\"Roe\"}]';\n" +
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

    private static final String QUERY = "|test::Person.all()->filter(p | $p.fullName == 'Jane Doe')->graphFetch(#{test::Person{fullName}}#)->serialize(#{test::Person{fullName}}#)";

    private static final Function<PureModel, RichIterable<? extends Root_meta_pure_extension_Extension>> SERVER_ROUTER_EXTENSIONS =
            (PureModel pureModel) -> PureCoreExtensionLoader.extensions().flatCollect(e -> e.extraPureCoreExtensions(pureModel.getExecutionSupport()));

    private static final MutableList<PlanTransformer> SERVER_PLAN_TRANSFORMERS =
            Lists.mutable.withAll(ServiceLoader.load(PlanGeneratorExtension.class)).flatCollect(PlanGeneratorExtension::getExtraPlanTransformers);

    @BeforeClass
    public static void setUpUrls()
    {
        EngineUrlStreamHandlerFactory.initialize();
    }

    @Test
    public void serverExtensionsCollapseSqlModelConnectionQuery() throws Exception
    {
        PureModelContextData model = PureGrammarParser.newInstance().parseModel(GRAMMAR);
        PureModel pureModel = Compiler.compile(model, DeploymentMode.TEST, Identity.getAnonymousIdentity().getName());
        LambdaFunction query = PureGrammarParser.newInstance().parseLambda(QUERY);

        SingleExecutionPlan plan = PlanGenerator.generateExecutionPlan(
                HelperValueSpecificationBuilder.buildLambda(query.body, query.parameters, pureModel.getContext()),
                pureModel.getMapping("test::mapping"),
                pureModel.getRuntime("test::runtime"),
                null,
                pureModel,
                "vX_X_X",
                PlanPlatform.JAVA,
                "test",
                SERVER_ROUTER_EXTENSIONS.valueOf(pureModel),
                SERVER_PLAN_TRANSFORMERS);

        JsonNode planJson = ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports().valueToTree(plan);
        assertTrue("expected the server's extensions to collapse the query into one SQL node: " + planJson,
                planJson.findValues("_type").stream().anyMatch(t -> "sqlGraphSerialize".equals(t.asText())));
        assertTrue("expected no Java in-memory graph fetch left in the plan: " + planJson,
                planJson.findValues("_type").stream().noneMatch(t -> "inMemoryRootGraphFetch".equals(t.asText())));
    }

    @Test
    public void executeResourceRunsSqlModelConnectionQuery() throws Exception
    {
        ExecuteInput input = new ExecuteInput();
        input.clientVersion = "vX_X_X";
        input.model = PureGrammarParser.newInstance().parseModel(GRAMMAR);
        input.mapping = "test::mapping";
        input.runtime = runtimePointer("test::runtime");
        input.function = PureGrammarParser.newInstance().parseLambda(QUERY);
        input.context = new BaseExecutionContext();

        PlanExecutor planExecutor = PlanExecutor.newPlanExecutor(Relational.build(), InMemory.build());
        Execute execute = new Execute(new ModelManager(DeploymentMode.TEST), planExecutor, SERVER_ROUTER_EXTENSIONS, SERVER_PLAN_TRANSFORMERS);
        Response response = execute.execute(mockRequest(), input, SerializationFormat.defaultFormat, null, null);

        if (response.getStatus() != 200)
        {
            fail(ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports().writeValueAsString(response.getEntity()));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ((StreamingOutput) response.getEntity()).write(out);
        assertEquals("{\"builder\":{\"_type\":\"json\"},\"values\":{\"fullName\":\"Jane Doe\"}}", out.toString(StandardCharsets.UTF_8.name()));
    }

    private static RuntimePointer runtimePointer(String path)
    {
        RuntimePointer pointer = new RuntimePointer();
        pointer.runtime = path;
        return pointer;
    }

    private static HttpServletRequest mockRequest()
    {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(request.getSession()).thenReturn(session);
        Mockito.when(session.getId()).thenReturn("testSessionID");
        return request;
    }
}
