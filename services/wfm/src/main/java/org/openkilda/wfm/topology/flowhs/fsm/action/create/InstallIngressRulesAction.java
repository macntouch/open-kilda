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

package org.openkilda.wfm.topology.flowhs.fsm.action.create;

import org.openkilda.floodlight.flow.request.InstallIngressRule;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.flowhs.fsm.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.FlowCreateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.FlowCreateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.FlowCreateFsm.State;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandFactory;

import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.AnonymousAction;

import java.util.List;

@Slf4j
public class InstallIngressRulesAction extends AnonymousAction<FlowCreateFsm, State, Event, FlowCreateContext> {

    private final FlowCommandFactory flowCommandFactory;

    public InstallIngressRulesAction(PersistenceManager persistenceManager) {
        this.flowCommandFactory =
                new FlowCommandFactory(persistenceManager.getRepositoryFactory().createTransitVlanRepository());
    }

    @Override
    public void execute(State from, State to, Event event, FlowCreateContext context, FlowCreateFsm stateMachine) {
        List<InstallIngressRule> commands = flowCommandFactory.createInstallIngressRules(stateMachine.getFlow());
        stateMachine.getCarrier().sendInstallCommands(commands);

        log.warn("Commands for installing ingress rules have been sent");
    }
}
