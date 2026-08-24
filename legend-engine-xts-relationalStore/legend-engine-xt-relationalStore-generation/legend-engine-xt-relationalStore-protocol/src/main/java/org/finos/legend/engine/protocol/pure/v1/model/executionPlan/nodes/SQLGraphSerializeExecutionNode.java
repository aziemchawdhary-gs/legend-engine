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

package org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes;

import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.DatabaseConnection;

import java.util.Collections;
import java.util.List;

/**
 * Execution node for the M2M-on-SQL phase 1 path: runs a sequence of setup SQLs (populating a
 * temp table from {@link #url}) followed by {@link #sqlQuery} on a single JDBC connection, and
 * streams the query's {@code "doc"} column as JSON.
 */
public class SQLGraphSerializeExecutionNode extends ExecutionNode
{
    public DatabaseConnection connection;
    public List<String> setupSqls = Collections.emptyList();
    public String sqlQuery;
    public String url;

    @Override
    public <T> T accept(ExecutionNodeVisitor<T> executionNodeVisitor)
    {
        return executionNodeVisitor.visit(this);
    }
}
