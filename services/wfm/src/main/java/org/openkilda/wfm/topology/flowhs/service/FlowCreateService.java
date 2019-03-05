/* Copyright 2019 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.wfm.topology.flowhs.service;

import org.openkilda.messaging.model.FlowDto;
import org.openkilda.model.Flow;
import org.openkilda.pce.AvailableNetworkFactory;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathComputerConfig;
import org.openkilda.pce.PathComputerFactory;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.mappers.FlowMapper;
import org.openkilda.wfm.topology.flowhs.bolts.FlowCreateHubCarrier;
import org.openkilda.wfm.topology.flowhs.fsm.FlowCreateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.FlowCreateFsm.Event;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class FlowCreateService {

    private transient Map<String, FlowCreateFsm> fsms = new HashMap<>();

    private final PersistenceManager persistenceManager;
    private final FlowResourcesConfig flowResourcesConfig;
    private final PathComputer pathComputer;

    public FlowCreateService(PersistenceManager persistenceManager, PathComputerConfig pathComputerConfig,
                             FlowResourcesConfig flowResourcesConfig) {
        this.persistenceManager = persistenceManager;
        this.flowResourcesConfig = flowResourcesConfig;

        AvailableNetworkFactory availableNetworkFactory =
                new AvailableNetworkFactory(pathComputerConfig, persistenceManager.getRepositoryFactory());
        this.pathComputer = new PathComputerFactory(pathComputerConfig, availableNetworkFactory).getPathComputer();
    }

    /**
     * Handles request for flow creation.
     * @param key command identifier.
     * @param dto request data.
     */
    public void handleRequest(String key, CommandContext commandContext, FlowDto dto, FlowCreateHubCarrier carrier) {
        log.debug("Handling flow create request with key {}", key);
        Flow request = FlowMapper.INSTANCE.toFlow((dto));
        FlowCreateFsm fsm = FlowCreateFsm.builder()
                .withCorrelationId(commandContext.getCorrelationId())
                .withFlow(request)
                .withCarrier(carrier)
                .build(persistenceManager, flowResourcesConfig, pathComputer);
        fsms.put(key, fsm);
        fsm.fire(Event.Next);
    }

    /**
     * Handles async response from worker.
     * @param key command identifier.
     */
    public void handleAsyncResponse(String key) {
        fsms.get(key).fire(Event.Next);
    }
}
