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

package org.finos.legend.engine.plan.execution.stores.relational.plugin;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.finos.legend.engine.plan.execution.nodes.state.ExecutionState;
import org.finos.legend.engine.plan.execution.result.Result;
import org.finos.legend.engine.plan.execution.result.ResultVisitor;
import org.finos.legend.engine.plan.execution.result.json.JsonStreamingResult;
import org.finos.legend.engine.plan.execution.stores.StoreType;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.SQLGraphSerializeExecutionNode;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.finos.legend.engine.shared.core.identity.Identity;
import org.finos.legend.engine.shared.core.operational.logs.LogInfo;
import org.finos.legend.engine.shared.core.url.UrlFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Executes a {@link SQLGraphSerializeExecutionNode}: materializes {@link SQLGraphSerializeExecutionNode#url}
 * to a local temp file, substitutes the literal {@code {{M2M_INPUT_PATH}}} token in each setup SQL with the
 * temp file's absolute (single-quote-escaped) path, runs the setup SQLs followed by the node's query on a
 * single JDBC connection, and streams the query's {@code "doc"} column as JSON.
 */
public class M2MSqlGraphSerializeHelper
{
    private static final Logger LOGGER = LoggerFactory.getLogger(M2MSqlGraphSerializeHelper.class);
    private static final String INPUT_PATH_TOKEN = "{{M2M_INPUT_PATH}}";

    private M2MSqlGraphSerializeHelper()
    {
    }

    public static Result execute(SQLGraphSerializeExecutionNode node, Identity identity, ExecutionState executionState)
    {
        long start = System.currentTimeMillis();
        LOGGER.info("{}", new LogInfo(M2MSqlLoggingEventType.M2M_SQL_GRAPH_SERIALIZE_START, identity.getName(), node.url));

        RelationalStoreExecutionState relationalStoreExecutionState = (RelationalStoreExecutionState) executionState.getStoreExecutionState(StoreType.Relational);

        Path tempFile = null;
        Connection connection = null;
        Statement statement = null;
        ResultSet resultSet = null;
        boolean success = false;
        try
        {
            tempFile = Files.createTempFile("m2m-sql-input-", ".json");
            try (InputStream inputStream = UrlFactory.create(node.url).openStream())
            {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            String escapedPath = tempFile.toAbsolutePath().toString().replace("'", "''");

            connection = relationalStoreExecutionState.getRelationalExecutor().getConnectionManager().getDatabaseConnection(
                    identity, node.connection, executionState.getResults(), relationalStoreExecutionState.getRuntimeContext());

            statement = connection.createStatement();
            for (String setupSql : node.setupSqls)
            {
                statement.execute(setupSql.replace(INPUT_PATH_TOKEN, escapedPath));
            }
            resultSet = statement.executeQuery(node.sqlQuery);

            Result closeCascade = newCloseCascadeResult(connection, statement, resultSet, tempFile, identity, node, start);
            success = true;
            return new JsonStreamingResult(new SQLGraphSerializeJsonStreamHandler(resultSet), closeCascade);
        }
        catch (Exception e)
        {
            LOGGER.error("{}", new LogInfo(M2MSqlLoggingEventType.M2M_SQL_GRAPH_SERIALIZE_ERROR, identity.getName(), node.url, System.currentTimeMillis() - start));
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        }
        finally
        {
            if (!success)
            {
                closeQuietly(resultSet);
                closeQuietly(statement);
                closeQuietly(connection);
                deleteQuietly(tempFile);
            }
        }
    }

    private static Result newCloseCascadeResult(Connection connection, Statement statement, ResultSet resultSet, Path tempFile, Identity identity, SQLGraphSerializeExecutionNode node, long start)
    {
        return new Result("success")
        {
            @Override
            public <T> T accept(ResultVisitor<T> resultVisitor)
            {
                throw new RuntimeException("Not implemented");
            }

            @Override
            public void close()
            {
                closeQuietly(resultSet);
                closeQuietly(statement);
                closeQuietly(connection);
                deleteQuietly(tempFile);
                LOGGER.info("{}", new LogInfo(M2MSqlLoggingEventType.M2M_SQL_GRAPH_SERIALIZE_STOP, identity.getName(), node.url, System.currentTimeMillis() - start));
            }
        };
    }

    private static void closeQuietly(ResultSet resultSet)
    {
        if (resultSet != null)
        {
            try
            {
                resultSet.close();
            }
            catch (SQLException e)
            {
                LOGGER.warn("Failed to close M2M SQL graph serialize result set", e);
            }
        }
    }

    private static void closeQuietly(Statement statement)
    {
        if (statement != null)
        {
            try
            {
                statement.close();
            }
            catch (SQLException e)
            {
                LOGGER.warn("Failed to close M2M SQL graph serialize statement", e);
            }
        }
    }

    private static void closeQuietly(Connection connection)
    {
        if (connection != null)
        {
            try
            {
                connection.close();
            }
            catch (SQLException e)
            {
                LOGGER.warn("Failed to close M2M SQL graph serialize connection", e);
            }
        }
    }

    private static void deleteQuietly(Path path)
    {
        if (path != null)
        {
            try
            {
                Files.deleteIfExists(path);
            }
            catch (IOException e)
            {
                LOGGER.warn("Failed to delete M2M SQL graph serialize temp input file {}", path, e);
            }
        }
    }

    /**
     * Writes the {@code "doc"} column of each row as raw JSON. Mirrors the envelope shape produced by
     * {@code ExecutionNodeSerializerHelper.Serializer} (the Java M2M graph-fetch serialize path): a bare
     * value when the result has exactly one row, and a JSON array (possibly empty) otherwise.
     */
    private static class SQLGraphSerializeJsonStreamHandler implements JsonStreamingResult.JsonStreamHandler
    {
        private final ResultSet resultSet;

        SQLGraphSerializeJsonStreamHandler(ResultSet resultSet)
        {
            this.resultSet = resultSet;
        }

        @Override
        public void writeTo(JsonGenerator generator)
        {
            try
            {
                if (!this.resultSet.next())
                {
                    generator.writeStartArray();
                    generator.writeEndArray();
                    return;
                }
                String first = this.resultSet.getString(1);
                if (!this.resultSet.next())
                {
                    generator.writeRawValue(first);
                    return;
                }
                generator.writeStartArray();
                generator.writeRawValue(first);
                do
                {
                    generator.writeRawValue(this.resultSet.getString(1));
                }
                while (this.resultSet.next());
                generator.writeEndArray();
            }
            catch (SQLException | IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Stream<ObjectNode> toStream()
        {
            ObjectMapper mapper = ObjectMapperFactory.getNewStandardObjectMapper();
            Iterator<ObjectNode> iterator = new Iterator<ObjectNode>()
            {
                private Boolean hasNextCache;

                @Override
                public boolean hasNext()
                {
                    if (this.hasNextCache == null)
                    {
                        try
                        {
                            this.hasNextCache = SQLGraphSerializeJsonStreamHandler.this.resultSet.next();
                        }
                        catch (SQLException e)
                        {
                            throw new RuntimeException(e);
                        }
                    }
                    return this.hasNextCache;
                }

                @Override
                public ObjectNode next()
                {
                    if (!this.hasNext())
                    {
                        throw new NoSuchElementException();
                    }
                    this.hasNextCache = null;
                    try
                    {
                        return (ObjectNode) mapper.readTree(SQLGraphSerializeJsonStreamHandler.this.resultSet.getString(1));
                    }
                    catch (SQLException | IOException e)
                    {
                        throw new RuntimeException(e);
                    }
                }
            };
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false);
        }
    }
}
