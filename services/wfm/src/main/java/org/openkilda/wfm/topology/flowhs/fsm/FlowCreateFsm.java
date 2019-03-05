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

package org.openkilda.wfm.topology.flowhs.fsm;

import org.openkilda.model.Flow;
import org.openkilda.pce.PathComputer;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.topology.flowhs.bolts.FlowCreateHubCarrier;
import org.openkilda.wfm.topology.flowhs.fsm.FlowCreateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.FlowCreateFsm.State;
import org.openkilda.wfm.topology.flowhs.fsm.action.create.FlowValidateAction;
import org.openkilda.wfm.topology.flowhs.fsm.action.create.InstallIngressRulesAction;
import org.openkilda.wfm.topology.flowhs.fsm.action.create.InstallNonIngressRulesAction;
import org.openkilda.wfm.topology.flowhs.fsm.action.create.ResourcesAllocateAction;
import org.openkilda.wfm.topology.flowhs.fsm.action.create.ResourcesDeallocateAction;
import org.openkilda.wfm.topology.flowhs.fsm.action.create.ValidateNonIngressRulesAction;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;
import org.squirrelframework.foundation.fsm.impl.AbstractStateMachine;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Slf4j
public final class FlowCreateFsm extends AbstractStateMachine<FlowCreateFsm, State, Event, FlowCreateContext> {

    private String correlationId;
    private Flow flow;
    private FlowCreateHubCarrier carrier;
    private List<String> errors = new ArrayList<>();

    private FlowCreateFsm(String correlationId, Flow flow, FlowCreateHubCarrier carrier) {
        this.correlationId = correlationId;
        this.flow = flow;
        this.carrier = carrier;
    }

    /**
     * Returns builder for flow create fsm.
     */
    private static StateMachineBuilder<FlowCreateFsm, State, Event, FlowCreateContext> builder(
            PersistenceManager persistenceManager, FlowResourcesConfig resourcesConfig, PathComputer pathComputer) {
        StateMachineBuilder<FlowCreateFsm, State, Event, FlowCreateContext> builder =
                StateMachineBuilderFactory.create(FlowCreateFsm.class, State.class, Event.class,
                        FlowCreateContext.class,
                        String.class, Flow.class, FlowCreateHubCarrier.class);

        builder.transitions()
                .from(State.Initialized)
                .toAmong(State.FlowValidated, State.FinishedWithError)
                .onEach(Event.Next, Event.Error)
                .perform(new FlowValidateAction(persistenceManager));

        builder.transition()
                .from(State.FlowValidated)
                .to(State.ResourcesAllocated)
                .on(Event.Next)
                .perform(new ResourcesAllocateAction(persistenceManager, pathComputer, resourcesConfig));

        builder.transitions()
                .from(State.FlowValidated)
                .toAmong(State.ResourcesDeAllocated, State.FinishedWithError)
                .onEach(Event.Timeout, Event.Error)
                .perform(new ResourcesDeallocateAction());

        builder.externalTransition()
                .from(State.ResourcesAllocated)
                .to(State.InstallingNonIngressRules)
                .on(Event.Next)
                .perform(new InstallNonIngressRulesAction(persistenceManager));

        builder.internalTransition()
                .within(State.InstallingNonIngressRules)
                .on(Event.RuleInstalled);
        builder.transitions()
                .from(State.InstallingNonIngressRules)
                .toAmong(State.ValidatingNonIngressRules, State.RemovingRules, State.RemovingRules)
                .onEach(Event.Next, Event.Timeout, Event.Error)
                .perform(new ValidateNonIngressRulesAction());

        builder.internalTransition()
                .within(State.ValidatingNonIngressRules)
                .on(Event.RuleValidated);
        builder.transitions()
                .from(State.ValidatingNonIngressRules)
                .toAmong(State.InstallingIngressRules)
                .onEach(Event.Next)
                .perform(new InstallIngressRulesAction(persistenceManager));
        builder.transitions()
                .from(State.ValidatingNonIngressRules)
                .toAmong(State.RemovingRules, State.InstallingNonIngressRules)
                .onEach(Event.Timeout, Event.Error);

        builder.internalTransition()
                .within(State.InstallingIngressRules)
                .on(Event.RuleInstalled);
        builder.transitions()
                .from(State.InstallingIngressRules)
                .toAmong(State.ValidatingIngressRules, State.RemovingRules, State.RemovingRules)
                .onEach(Event.Next, Event.Timeout, Event.Error);

        builder.internalTransition()
                .within(State.ValidatingIngressRules)
                .on(Event.RuleValidated);
        builder.transitions()
                .from(State.ValidatingIngressRules)
                .toAmong(State.Finished, State.RemovingRules, State.RemovingRules)
                .onEach(Event.Next, Event.Timeout, Event.Error);

        builder.internalTransition()
                .within(State.RemovingRules)
                .on(Event.RuleDeleted);
        builder.transitions()
                .from(State.RemovingRules)
                .toAmong(State.ValidatingRemovedRules, State.NonDeletedRulesStored, State.NonDeletedRulesStored)
                .onEach(Event.Next, Event.Timeout, Event.Error);

        builder.internalTransition()
                .within(State.ValidatingRemovedRules)
                .on(Event.RuleDeletedConfirmation);
        builder.transitions()
                .from(State.ValidatingRemovedRules)
                .toAmong(State.ResourcesDeAllocated, State.NonDeletedRulesStored, State.NonDeletedRulesStored)
                .onEach(Event.Next, Event.Timeout, Event.Error);

        builder.transition()
                .from(State.NonDeletedRulesStored)
                .to(State.ResourcesDeAllocated)
                .on(Event.Next);

        builder.transition()
                .from(State.ResourcesDeAllocated)
                .toFinal(State.FinishedWithError)
                .on(Event.Next);

        return builder;
    }

    public enum State {
        Initialized,
        FlowValidated,
        ResourcesAllocated,
        InstallingNonIngressRules,
        ValidatingNonIngressRules,
        InstallingIngressRules,
        ValidatingIngressRules,
        Finished,

        RemovingRules,
        ValidatingRemovedRules,
        ResourcesDeAllocated,
        NonDeletedRulesStored,
        FinishedWithError,
    }

    public enum Event {
        Next,

        RuleInstalled,
        RuleValidated,
        RuleDeleted,
        RuleDeletedConfirmation,

        Timeout,
        Error
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String correlationId;
        private Flow flow;
        private FlowCreateHubCarrier carrier;

        private Builder() {}

        public Builder withCorrelationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder withFlow(Flow flow) {
            this.flow = flow;
            return this;
        }

        public Builder withCarrier(FlowCreateHubCarrier carrier) {
            this.carrier = carrier;
            return this;
        }

        public FlowCreateFsm build(PersistenceManager persistenceManager, FlowResourcesConfig resourcesConfig,
                                   PathComputer pathComputer) {
            return FlowCreateFsm.builder(persistenceManager, resourcesConfig, pathComputer)
                    .newStateMachine(State.Initialized, correlationId, flow, carrier);
        }

    }
}
