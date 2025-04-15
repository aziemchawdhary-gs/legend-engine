//  Copyright 2022 Goldman Sachs
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

package org.finos.legend.engine.ide;

import org.finos.legend.engine.plan.execution.stores.relational.test.H2TestServerResource;
import org.finos.legend.engine.server.test.shared.MetadataTestServerResource;
import org.finos.legend.engine.server.test.shared.PureWithEngineHelper;
import org.finos.legend.engine.server.test.shared.ServerTestServerResource;

public class PureIDELight extends PureIDEServer
{
    public static void main(String[] args) throws Exception
    {
        System.setProperty("legend.test.h2.port", "1976");
        System.setProperty("user.timezone", "GMT");

        // Uncomment to be able to run   AlloyOny test cases
        // withAlloyServerSupport();

        new PureIDELight().run(args.length == 0 ? new String[] {"server", "legend-engine-core/legend-engine-core-pure/legend-engine-pure-ide/legend-engine-pure-ide-light-http-server/src/main/resources/ideLightConfig.json"} : args);
    }

    private static void withAlloyServerSupport() throws Exception
    {
        PureWithEngineHelper.initClientVersionIfNotAlreadySet("vX_X_X");
        new H2TestServerResource().start();
        new MetadataTestServerResource().start();
        new ServerTestServerResource("org/finos/legend/engine/server/test/userTestConfig.json").start();
    }

}
