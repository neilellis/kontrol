/*
 * Copyright 2014 Cazcade Limited (http://cazcade.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kontrol.server

/**
 * @todo document.
 * @author <a href="http://uk.linkedin.com/in/neilellis">Neil Ellis</a>
 */

/*
 * Copyright 2014 Cazcade Limited (http://cazcade.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import kontrol.common.DefaultController
import kontrol.api.Infrastructure
import kontrol.status.StatusServer
import kontrol.common.group.ext.allowDefaultTranstitions
import kontrol.common.group.ext.applyDefaultPolicies
import kontrol.hazelcast.HazelcastBus
import kontrol.hibernate.HibernatePostmortemStore
import kontrol.api.Controller

/**
 * @author <a href="http://uk.linkedin.com/in/neilellis">Neil Ellis</a>
 */


public class Server(var infraFactory: (Controller) -> Infrastructure) {

    var infra: Infrastructure
    var statusServer: StatusServer
    val controller: Controller
    {
        val dbUrl = "jdbc:mysql://localhost:3306/kontrol?user=root"
        val eventLog = HibernateEventLog(dbUrl)
        val bus = HazelcastBus()
        controller = DefaultController(bus, eventLog)
        val postmortems = HibernatePostmortemStore(dbUrl)
        postmortems.last(1)
        infra = infraFactory(controller)
        statusServer = StatusServer(infra, bus, postmortems, eventLog)
        infra.topology().each { group -> group.allowDefaultTranstitions(); group.applyDefaultPolicies(controller, postmortems) }

    }


    fun start() {
        controller.start()
        infra.start()
        statusServer.start()
    }


    fun stop() {
        statusServer.stop()
        infra.stop()
        controller.stop()
    }

    fun reload() {
        infra.stop()
        infra = infraFactory(controller)
        infra.start()
    }
}


