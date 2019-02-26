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

import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;
import org.squirrelframework.foundation.fsm.impl.AbstractStateMachine;

public class FlowRerouteFsm
        extends AbstractStateMachine<FlowRerouteFsm, FlowRerouteFsm.State, FlowRerouteFsm.Event, Object> {

    static FlowRerouteFsm newInstance() {
        StateMachineBuilder<FlowRerouteFsm, State, Event, Object> builder =
                StateMachineBuilderFactory.create(FlowRerouteFsm.class, State.class, Event.class, Object.class);

        builder.transitions()
                .from(State.Initialized)
                .toAmong(State.FlowValidated, State.FinishedWithError)
                .onEach(Event.Next, Event.Error);

        builder.transitions()
                .from(State.FlowValidated)
                .toAmong(State.ResourcesAllocated, State.FinishedWithError)
                .onEach(Event.Next, Event.Error);

        builder.transitions()
                .from(State.ResourcesAllocated)
                .toAmong(State.PathPersistedInDB, State.ResourcesAllocationReverted)
                .onEach(Event.Next, Event.Error);

        builder.transitions()
                .from(State.PathPersistedInDB)
                .toAmong(State.InstallingNewRules)
                .onEach(Event.Next);

        builder.internalTransition()
                .within(State.InstallingNewRules)
                .on(Event.RuleInstalled);

        builder.transitions()
                .from(State.InstallingNewRules)
                .toAmong(State.AllNewRulesInstalled, State.RollingbackRuleInstallation, State.RollingbackRuleInstallation)
                .onEach(Event.Next, Event.Timeout, Event.Error);

        builder.transition()
                .from(State.AllNewRulesInstalled)
                .to(State.ValidatingRules)
                .on(Event.Next);

        builder.internalTransition()
                .within(State.ValidatingRules)
                .on(Event.RuleValidated);

        builder.transitions()
                .from(State.ValidatingRules)
                .toAmong(State.PathValidated, State.RollingbackRuleInstallation, State.RollingbackRuleInstallation,
                        State.RollingbackRuleInstallation)
                .onEach(Event.Next, Event.MissingRuleFound, Event.Timeout, Event.Error);

        builder.transitions()
                .from(State.PathValidated)
                .toAmong(State.PathsSwappedAndPersistedInDB, State.RollingbackRuleInstallation)
                .onEach(Event.Next, Event.Error);

        builder.internalTransition()
                .within(State.RollingbackRuleInstallation)
                .on(Event.RuleRemoved);

        builder.transition()
                .from(State.PathsSwappedAndPersistedInDB)
                .to(State.RemovingOldRules)
                .on(Event.Next);

        builder.transitions()
                .from(State.RollingbackRuleInstallation)
                .toAmong(State.AllNewRulesRemoved, State.AllNewRulesRemoved)
                .onEach(Event.Next, Event.Timeout);

        builder.transitions()
                .from(State.AllNewRulesRemoved)
                .toAmong(State.ValidatingRemovedNewRules)
                .onEach(Event.Next);

        builder.internalTransition()
                .within(State.ValidatingRemovedNewRules)
                .on(Event.RuleRemoveValidated);

        builder.transitions()
                .from(State.ValidatingRemovedNewRules)
                .toAmong(State.AllNewRulesRemoveValidated, State.AllNewRulesRemoveValidated)
                .onEach(Event.Next, Event.Timeout);

        builder.transitions()
                .from(State.AllNewRulesRemoveValidated)
                .toAmong(State.ResourcesAllocationReverted)
                .onEach(Event.Next);

        builder.transitions()
                .from(State.ResourcesAllocationReverted)
                .toAmong(State.FinishedWithError)
                .onEach(Event.Next);

        builder.internalTransition()
                .within(State.RemovingOldRules)
                .on(Event.RuleRemoved);
        builder.internalTransition()
                .within(State.RemovingOldRules)
                .on(Event.NotRemovedRuleIsStored);

        builder.transitions()
                .from(State.RemovingOldRules)
                .toAmong(State.AllOldRulesRemoved, State.AllOldRulesRemoved)
                .onEach(Event.Next, Event.Timeout);

        builder.transitions()
                .from(State.AllOldRulesRemoved)
                .toAmong(State.ValidatingRemovedOldRules)
                .onEach(Event.Next);

        builder.internalTransition()
                .within(State.ValidatingRemovedOldRules)
                .on(Event.RuleRemoveValidated);
        builder.internalTransition()
                .within(State.ValidatingRemovedOldRules)
                .on(Event.NotRemovedRuleIsStored);

        builder.transitions()
                .from(State.ValidatingRemovedOldRules)
                .toAmong(State.AllOldRulesRemoveValidated, State.AllOldRulesRemoveValidated)
                .onEach(Event.Next, Event.Timeout);

        builder.transitions()
                .from(State.AllOldRulesRemoveValidated)
                .toAmong(State.ResourcesDeallocated, State.NotRemovedRulesAreStored)
                .onEach(Event.Next, Event.NotRemovedRuleIsStored);

        builder.transitions()
                .from(State.ResourcesDeallocated)
                .toAmong(State.Finished)
                .onEach(Event.Next);

        builder.transitions()
                .from(State.NotRemovedRulesAreStored)
                .toAmong(State.Finished)
                .onEach(Event.Next);

        return builder.newStateMachine(State.Initialized);
    }

    enum State {
        Initialized,
        FlowValidated,
        ResourcesAllocated,
        PathPersistedInDB,
        InstallingNewRules,
        AllNewRulesInstalled,
        ValidatingRules,
        PathValidated,
        RollingbackRuleInstallation,
        PathsSwappedAndPersistedInDB,
        AllNewRulesRemoved,
        RemovingOldRules,
        ValidatingRemovedNewRules,
        AllOldRulesRemoved,
        AllNewRulesRemoveValidated,
        ValidatingRemovedOldRules,
        ResourcesAllocationReverted,
        AllOldRulesRemoveValidated,
        ResourcesDeallocated,
        NotRemovedRulesAreStored,
        Finished,
        FinishedWithError
    }

    enum Event {
        Next,

        RuleInstalled,
        RuleValidated,
        MissingRuleFound,

        RuleRemoved,
        RuleRemoveValidated,
        NotRemovedRuleIsStored,

        Timeout,
        Error
    }
}
