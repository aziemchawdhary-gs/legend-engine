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

package org.finos.legend.engine.plan.execution.authorization;

import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.SingleExecutionPlan;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.SQLGraphSerializeExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.RelationalDatabaseConnection;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.authentication.AuthenticationStrategy;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.authentication.MiddleTierUserNamePasswordAuthenticationStrategy;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.authentication.TestDatabaseAuthenticationStrategy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestM2MSqlMiddleTierPlanSelection
{
    // Selection never consults the credential authorizer, only the plan's nodes.
    private final RelationalMiddleTierPlanExecutionAuthorizer authorizer = new RelationalMiddleTierPlanExecutionAuthorizer(null);

    @Test
    public void sqlGraphSerializeNodeWithMiddleTierConnectionIsSelected()
    {
        MiddleTierUserNamePasswordAuthenticationStrategy middleTier = new MiddleTierUserNamePasswordAuthenticationStrategy();
        middleTier.vaultReference = "reference1";
        SingleExecutionPlan plan = planOver(middleTier);

        assertTrue(authorizer.isMiddleTierPlan(plan));
        MutableList<RelationalDatabaseConnection> selected = authorizer.findNodesWithMiddleTierAuthConnections(plan);
        assertEquals(1, selected.size());
        assertSame(((SQLGraphSerializeExecutionNode) plan.rootExecutionNode).connection, selected.get(0));
    }

    @Test
    public void sqlGraphSerializeNodeWithoutMiddleTierConnectionIsNotSelected()
    {
        SingleExecutionPlan plan = planOver(new TestDatabaseAuthenticationStrategy());

        assertFalse(authorizer.isMiddleTierPlan(plan));
        assertTrue(authorizer.findNodesWithMiddleTierAuthConnections(plan).isEmpty());
    }

    private static SingleExecutionPlan planOver(AuthenticationStrategy authenticationStrategy)
    {
        RelationalDatabaseConnection connection = new RelationalDatabaseConnection();
        connection.authenticationStrategy = authenticationStrategy;

        SQLGraphSerializeExecutionNode node = new SQLGraphSerializeExecutionNode();
        node.connection = connection;
        node.sqlQuery = "select 1";

        SingleExecutionPlan plan = new SingleExecutionPlan();
        plan.rootExecutionNode = node;
        return plan;
    }
}
