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

package org.openkilda.wfm.topology.discovery.service;

import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.discovery.controller.AntiFlapFsm;
import org.openkilda.wfm.topology.discovery.controller.AntiFlapFsm.Context;
import org.openkilda.wfm.topology.discovery.controller.AntiFlapFsm.Event;
import org.openkilda.wfm.topology.discovery.controller.AntiFlapFsm.State;
import org.openkilda.wfm.topology.discovery.model.Endpoint;
import org.openkilda.wfm.topology.discovery.model.facts.PortFacts;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class DiscoveryAntiFlapService {
    private final Map<Endpoint, AntiFlapFsm> controller = new HashMap<>();
    private final FsmExecutor<AntiFlapFsm, State, Event, Context> controllerExecutor
            = AntiFlapFsm.makeExecutor();

    private final IAntiFlapCarrier carrier;
    private final AntiFlapFsm.Config config;

    public DiscoveryAntiFlapService(IAntiFlapCarrier carrier, AntiFlapFsm.Config config) {
        this.carrier = carrier;
        this.config = config;
    }


    public void filterLinkStatus(Endpoint endpoint, PortFacts.LinkStatus status) {
        filterLinkStatus(endpoint, status, now());
    }

    @VisibleForTesting
    void filterLinkStatus(Endpoint endpoint, PortFacts.LinkStatus status, long timeMs) {
        AntiFlapFsm fsm = locateController(endpoint);
        AntiFlapFsm.Event event;
        switch (status) {
            case UP:
                event = AntiFlapFsm.Event.PORT_UP;
                break;
            case DOWN:
                event = AntiFlapFsm.Event.PORT_DOWN;
                break;
            default:
                throw new IllegalArgumentException(
                        String.format("Unsupported %s value %s", PortFacts.LinkStatus.class.getName(), status));
        }
        log.debug("Physical port {} become {}", endpoint, event);
        controllerExecutor.fire(fsm, event, new AntiFlapFsm.Context(carrier, timeMs));
    }

    public void tick() {
        tick(now());
    }

    @VisibleForTesting
    void tick(long timeMs) {
        controller.values().forEach(fsm ->
                controllerExecutor.fire(fsm, AntiFlapFsm.Event.TICK, new AntiFlapFsm.Context(carrier, timeMs)));
    }

    // -- private --

    private AntiFlapFsm locateController(Endpoint endpoint) {
        AntiFlapFsm fsm = controller.get(endpoint);
        if (fsm == null) {
            fsm = AntiFlapFsm.create(config.toBuilder().endpoint(endpoint).build());
            controller.put(endpoint, fsm);
        }
        return fsm;
    }

    private long now() {
        return System.currentTimeMillis();
    }
}
