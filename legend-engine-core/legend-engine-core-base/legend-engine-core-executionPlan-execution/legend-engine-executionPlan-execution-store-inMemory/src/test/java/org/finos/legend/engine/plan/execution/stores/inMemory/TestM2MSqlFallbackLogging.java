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

package org.finos.legend.engine.plan.execution.stores.inMemory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.finos.legend.engine.plan.execution.stores.inMemory.plugin.InMemoryExecutionNodeExecutor;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.store.inMemory.StoreStreamReadingExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.modelToModel.connection.JsonModelConnection;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.modelToModel.connection.SqlModelConnection;
import org.finos.legend.engine.shared.core.identity.Identity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestM2MSqlFallbackLogging
{
    private static final String PAYLOAD = "application/json,{}";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    public void setUp()
    {
        this.logger = (Logger) LoggerFactory.getLogger(InMemoryExecutionNodeExecutor.class);
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logger.addAppender(this.appender);
    }

    @AfterEach
    public void tearDown()
    {
        this.logger.detachAppender(this.appender);
    }

    @Test
    public void logsFallbackEventForSqlModelConnection()
    {
        SqlModelConnection connection = new SqlModelConnection();
        connection._class = "test::S";
        connection.url = "data:" + PAYLOAD;

        StoreStreamReadingExecutionNode node = new StoreStreamReadingExecutionNode();
        node.connection = connection;

        InMemoryExecutionNodeExecutor.logM2MSqlFallbackIfApplicable(node, Identity.getAnonymousIdentity());

        List<ILoggingEvent> events = this.appender.list;
        assertEquals(1, events.size());
        ILoggingEvent event = events.get(0);
        assertEquals(Level.INFO, event.getLevel());
        String formattedMessage = event.getFormattedMessage();
        assertTrue(formattedMessage.contains("M2M_SQL_JAVA_FALLBACK_EXECUTED"), formattedMessage);
        assertTrue(formattedMessage.contains("data"), formattedMessage);
        assertFalse(formattedMessage.contains(PAYLOAD), formattedMessage);
    }

    @Test
    public void doesNotLogForNonSqlModelConnection()
    {
        JsonModelConnection connection = new JsonModelConnection();
        connection._class = "test::S";
        connection.url = "data:" + PAYLOAD;

        StoreStreamReadingExecutionNode node = new StoreStreamReadingExecutionNode();
        node.connection = connection;

        InMemoryExecutionNodeExecutor.logM2MSqlFallbackIfApplicable(node, Identity.getAnonymousIdentity());

        assertEquals(0, this.appender.list.size());
    }
}
