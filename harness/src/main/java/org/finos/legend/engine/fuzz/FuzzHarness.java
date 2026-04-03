package org.finos.legend.engine.fuzz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.finos.legend.engine.language.pure.compiler.Compiler;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.shared.core.deployment.DeploymentMode;
import org.finos.legend.engine.shared.core.identity.Identity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.concurrent.*;

public class FuzzHarness
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_MS = 10000;
    private static final PureGrammarParser PARSER = PureGrammarParser.newInstance();

    public static void main(String[] args) throws Exception
    {
        long timeoutMs = DEFAULT_TIMEOUT_MS;
        for (int i = 0; i < args.length; i++)
        {
            if ("--timeout".equals(args[i]) && i + 1 < args.length)
            {
                timeoutMs = Long.parseLong(args[i + 1]);
            }
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter writer = new PrintWriter(System.out, true);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        String line;
        while ((line = reader.readLine()) != null)
        {
            ObjectNode result = processInput(line, executor, timeoutMs);
            writer.println(MAPPER.writeValueAsString(result));
        }

        executor.shutdownNow();
    }

    private static ObjectNode processInput(String jsonLine, ExecutorService executor, long timeoutMs)
    {
        ObjectNode result = MAPPER.createObjectNode();
        try
        {
            ObjectNode input = (ObjectNode) MAPPER.readTree(jsonLine);
            String id = input.get("id").asText();
            String code = input.get("code").asText();
            result.put("id", id);

            long start = System.currentTimeMillis();
            Future<ObjectNode> future = executor.submit(() -> classify(code));

            try
            {
                ObjectNode classification = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                long elapsed = System.currentTimeMillis() - start;
                result.setAll(classification);
                result.put("time_ms", elapsed);
            }
            catch (TimeoutException e)
            {
                future.cancel(true);
                result.put("result", "TIMEOUT");
                result.put("time_ms", timeoutMs);
            }
        }
        catch (Exception e)
        {
            result.put("result", "HARNESS_ERROR");
            result.put("exception", e.getClass().getName());
            result.put("message", e.getMessage());
        }
        return result;
    }

    private static ObjectNode classify(String code)
    {
        ObjectNode result = MAPPER.createObjectNode();

        // Phase 1: Parse
        PureModelContextData pmcd;
        try
        {
            pmcd = PARSER.parseModel(code);
        }
        catch (Exception e)
        {
            if (isExpectedParseError(e))
            {
                result.put("result", "PARSE_ERROR");
                result.put("message", e.getMessage());
            }
            else
            {
                result.put("result", "PARSE_CRASH");
                result.put("exception", e.getClass().getName());
                result.put("message", e.getMessage());
                result.put("stacktrace", getStackTrace(e));
            }
            return result;
        }
        result.put("parse", "OK");

        // Phase 2: Compile
        try
        {
            PureModel pureModel = Compiler.compile(pmcd, DeploymentMode.TEST, Identity.getAnonymousIdentity().getName());
            result.put("result", "COMPILE_OK");
        }
        catch (Exception e)
        {
            if (isExpectedCompileError(e))
            {
                result.put("result", "COMPILE_ERROR");
                result.put("message", e.getMessage());
            }
            else
            {
                result.put("result", "COMPILE_CRASH");
                result.put("exception", e.getClass().getName());
                result.put("message", e.getMessage());
                result.put("stacktrace", getStackTrace(e));
            }
        }
        return result;
    }

    private static boolean isExpectedParseError(Exception e)
    {
        String name = e.getClass().getName();
        return name.contains("EngineException") || name.contains("ParseCancellationException");
    }

    private static boolean isExpectedCompileError(Exception e)
    {
        String name = e.getClass().getName();
        return name.contains("EngineException");
    }

    private static String getStackTrace(Exception e)
    {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        String trace = sw.toString();
        return trace.length() > 2000 ? trace.substring(0, 2000) : trace;
    }
}
