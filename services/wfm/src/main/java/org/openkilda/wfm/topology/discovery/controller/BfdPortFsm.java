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

package org.openkilda.wfm.topology.discovery.controller;

import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.messaging.model.SwitchReference;
import org.openkilda.model.BfdPort;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ConstraintViolationException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.BfdPortRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.discovery.error.SwitchReferenceLookupException;
import org.openkilda.wfm.topology.discovery.model.Endpoint;
import org.openkilda.wfm.topology.discovery.model.IslReference;
import org.openkilda.wfm.topology.discovery.model.LinkStatus;
import org.openkilda.wfm.topology.discovery.model.facts.BfdPortFacts;
import org.openkilda.wfm.topology.discovery.service.IBfdPortCarrier;

import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;
import org.squirrelframework.foundation.fsm.impl.AbstractStateMachine;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.Random;

@Slf4j
public final class BfdPortFsm extends
        AbstractStateMachine<BfdPortFsm, BfdPortFsm.BfdPortFsmState, BfdPortFsm.BfdPortFsmEvent,
                BfdPortFsm.BfdPortFsmContext> {
    private static final int BFD_UDP_PRT = 3784;
    private static int bfdPollInterval = 350;  // TODO: use config option
    private static short bfdFailCycleLimit = 3;  // TODO: use config option

    private final SwitchRepository switchRepository;
    private final BfdPortRepository bfdPortRepository;

    @Getter
    private final Endpoint physicalEndpoint;
    @Getter
    private final Endpoint logicalEndpoint;

    private LinkStatus linkStatus;

    private Integer discriminator = null;
    private boolean upStatus = false;

    private static final StateMachineBuilder<BfdPortFsm, BfdPortFsmState, BfdPortFsmEvent, BfdPortFsmContext> builder;

    static {
        builder = StateMachineBuilderFactory.create(
                BfdPortFsm.class, BfdPortFsmState.class, BfdPortFsmEvent.class, BfdPortFsmContext.class,
                // extra parameters
                PersistenceManager.class, BfdPortFacts.class);

        // INIT
        builder.transition()
                .from(BfdPortFsmState.INIT).to(BfdPortFsmState.INIT_CHOICE).on(BfdPortFsmEvent.HISTORY)
                .callMethod("consumeHistory");

        // INIT_CHOICE
        builder.transition()
                .from(BfdPortFsmState.INIT_CHOICE).to(BfdPortFsmState.IDLE).on(BfdPortFsmEvent._INIT_CHOICE_CLEAN);
        builder.transition()
                .from(BfdPortFsmState.INIT_CHOICE).to(BfdPortFsmState.CLEANING).on(BfdPortFsmEvent._INIT_CHOICE_DIRTY);
        builder.onEntry(BfdPortFsmState.INIT_CHOICE)
                .callMethod("handleInitChoice");

        // IDLE
        builder.transition()
                .from(BfdPortFsmState.INIT).to(BfdPortFsmState.INSTALLING).on(BfdPortFsmEvent.ENABLE);
        builder.transition()
                .from(BfdPortFsmState.INIT).to(BfdPortFsmState.FAIL).on(BfdPortFsmEvent.PORT_UP);

        // INSTALLING
        builder.transition()
                .from(BfdPortFsmState.INSTALLING).to(BfdPortFsmState.UP).on(BfdPortFsmEvent.PORT_UP);
        builder.transition()
                .from(BfdPortFsmState.INSTALLING).to(BfdPortFsmState.CLEANING).on(BfdPortFsmEvent.SPEAKER_FAIL);
        builder.transition()
                .from(BfdPortFsmState.INSTALLING).to(BfdPortFsmState.CLEANING).on(BfdPortFsmEvent.SPEAKER_TIMEOUT);
        builder.onEntry(BfdPortFsmState.INSTALLING)
                .callMethod("installingEnter");

        // CLEANING
        builder.transition()
                .from(BfdPortFsmState.CLEANING).to(BfdPortFsmState.CLEANING_CHOICE).on(BfdPortFsmEvent.SPEAKER_SUCCESS)
                .callMethod("releaseResources");
        builder.transition()
                .from(BfdPortFsmState.CLEANING).to(BfdPortFsmState.FAIL).on(BfdPortFsmEvent.SPEAKER_FAIL);
        builder.transition()
                .from(BfdPortFsmState.CLEANING).to(BfdPortFsmState.FAIL).on(BfdPortFsmEvent.SPEAKER_TIMEOUT);
        builder.internalTransition().within(BfdPortFsmState.CLEANING).on(BfdPortFsmEvent.PORT_UP)
                .callMethod("cleaningUpdateUpStatus");
        builder.internalTransition().within(BfdPortFsmState.CLEANING).on(BfdPortFsmEvent.PORT_DOWN)
                .callMethod("cleaningUpdateUpStatus");
        builder.onEntry(BfdPortFsmState.CLEANING)
                .callMethod("handleCleaning");

        // CLEANING_CHOICE
        builder.transition()
                .from(BfdPortFsmState.CLEANING_CHOICE).to(BfdPortFsmState.IDLE)
                .on(BfdPortFsmEvent._CLEANING_CHOICE_READY);
        builder.transition()
                .from(BfdPortFsmState.CLEANING_CHOICE).to(BfdPortFsmState.WAIT_RELEASE)
                .on(BfdPortFsmEvent._CLEANING_CHOICE_HOLD);
        builder.onEntry(BfdPortFsmState.CLEANING_CHOICE)
                .callMethod("handleCleaningChoice");

        // WAIT_RELEASE
        builder.transition()
                .from(BfdPortFsmState.WAIT_RELEASE).to(BfdPortFsmState.IDLE).on(BfdPortFsmEvent.PORT_DOWN);
        builder.onExit(BfdPortFsmState.WAIT_RELEASE)
                .callMethod("waitReleaseExit");

        // UP
        builder.transition()
                .from(BfdPortFsmState.UP).to(BfdPortFsmState.DOWN).on(BfdPortFsmEvent.PORT_DOWN);
        builder.transition()
                .from(BfdPortFsmState.UP).to(BfdPortFsmState.CLEANING).on(BfdPortFsmEvent.BI_ISL_MOVE);
        builder.transition()
                .from(BfdPortFsmState.UP).to(BfdPortFsmState.STOP).on(BfdPortFsmEvent.DISABLE);
        builder.onEntry(BfdPortFsmState.UP)
                .callMethod("upEnter");

        // DOWN
        builder.transition()
                .from(BfdPortFsmState.DOWN).to(BfdPortFsmState.UP).on(BfdPortFsmEvent.PORT_UP);
        builder.transition()
                .from(BfdPortFsmState.DOWN).to(BfdPortFsmState.CLEANING).on(BfdPortFsmEvent.BI_ISL_MOVE);
        builder.onEntry(BfdPortFsmState.DOWN)
                .callMethod("downEnter");

        // FAIL
        builder.transition()
                .from(BfdPortFsmState.FAIL).to(BfdPortFsmState.IDLE).on(BfdPortFsmEvent.PORT_DOWN);

        // STOP
        builder.onEntry(BfdPortFsmState.STOP)
                .callMethod("stopEnter");

        builder.defineFinalState(BfdPortFsmState.STOP);
    }

    public static FsmExecutor<BfdPortFsm, BfdPortFsmState, BfdPortFsmEvent, BfdPortFsmContext> makeExecutor() {
        return new FsmExecutor<>(BfdPortFsmEvent.NEXT);
    }

    public static BfdPortFsm create(PersistenceManager persistenceManager, BfdPortFacts portFacts) {
        return builder.newStateMachine(BfdPortFsmState.INIT, persistenceManager, portFacts);
    }

    public BfdPortFsm(PersistenceManager persistenceManager, BfdPortFacts portFacts) {
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        this.switchRepository = repositoryFactory.createSwitchRepository();
        this.bfdPortRepository = repositoryFactory.createBfdPortRepository();

        this.logicalEndpoint = portFacts.getEndpoint();
        this.physicalEndpoint = Endpoint.of(logicalEndpoint.getDatapath(), portFacts.getPhysicalPortNumber());
        this.linkStatus = portFacts.getLinkStatus();
    }

    // -- FSM actions --

    protected void consumeHistory(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                  BfdPortFsmContext context) {
        Optional<BfdPort> port = loadBfdPort();
        port.ifPresent(bfdPort -> discriminator = bfdPort.getDiscriminator());
    }

    protected void handleInitChoice(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                  BfdPortFsmContext context) {
        if (discriminator == null) {
            fire(BfdPortFsmEvent._INIT_CHOICE_CLEAN, context);
        } else {
            fire(BfdPortFsmEvent._INIT_CHOICE_DIRTY, context);
        }
    }

    protected void installingEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                 BfdPortFsmContext context) {
        allocateDiscriminator();

        Endpoint remoteEndpoint = context.getIslReference().getOpposite(getPhysicalEndpoint());
        try {
            SwitchReference localSwitchReference = makeSwitchReference(physicalEndpoint.getDatapath());
            SwitchReference remoteSwitchReference = makeSwitchReference(remoteEndpoint.getDatapath());

            NoviBfdSession bfdSession = NoviBfdSession.builder()
                    .target(localSwitchReference)
                    .remote(remoteSwitchReference)
                    .physicalPortNumber(physicalEndpoint.getPortNumber())
                    .logicalPortNumber(logicalEndpoint.getPortNumber())
                    .udpPortNumber(BFD_UDP_PRT)
                    .discriminator(discriminator)
                    .intervalMs(bfdPollInterval)
                    .multiplier(bfdFailCycleLimit)
                    .keepOverDisconnect(true)
                    .build();
            context.getOutput().setupBfdSession(bfdSession);
        } catch (SwitchReferenceLookupException e) {
            log.error("{}", e.getMessage());
            fire(BfdPortFsmEvent.FAIL, context);
        }
    }

    protected void releaseResources(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                  BfdPortFsmContext context) {
        bfdPortRepository.findBySwitchIdAndPort(logicalEndpoint.getDatapath(), logicalEndpoint.getPortNumber())
                .ifPresent(bfdPortRepository::delete);
        discriminator = null;
    }

    protected void handleCleaning(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                BfdPortFsmContext context) {
        // TODO emit FL-BFD-producer remove
    }

    protected void cleaningUpdateUpStatus(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                        BfdPortFsmContext context) {
        switch (event) {
            case PORT_UP:
                upStatus = true;
                break;
            case PORT_DOWN:
                upStatus = false;
                break;
            default:
                throw new IllegalStateException(String.format("Unable to handle event %s into %s",
                                                              event, getCurrentState()));
        }
    }

    protected void handleCleaningChoice(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                      BfdPortFsmContext context) {
        if (upStatus) {
            fire(BfdPortFsmEvent._CLEANING_CHOICE_HOLD, context);
        } else {
            fire(BfdPortFsmEvent._CLEANING_CHOICE_READY, context);
        }
    }

    protected void waitReleaseExit(BfdPortFsmState from,  BfdPortFsmState to, BfdPortFsmEvent event,
                                 BfdPortFsmContext context) {
        upStatus = false;
    }

    protected void upEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        // TODO emit bfd-up
        upStatus = true;
    }

    protected void downEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                             BfdPortFsmContext context) {
        // TODO emit bfd-down
        upStatus = false;
    }

    protected void stopEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                             BfdPortFsmContext context) {
        // TODO
    }

    // -- private/service methods --

    private void allocateDiscriminator() {
        Optional<BfdPort> foundPort = loadBfdPort();

        if (foundPort.isPresent()) {
            this.discriminator = foundPort.get().getDiscriminator();
        } else {
            Random random = new Random();
            BfdPort bfdPort = new BfdPort();
            bfdPort.setSwitchId(logicalEndpoint.getDatapath());
            bfdPort.setPort(logicalEndpoint.getPortNumber());
            boolean success = false;
            while (!success) {
                try {
                    bfdPort.setDiscriminator(random.nextInt());
                    bfdPortRepository.createOrUpdate(bfdPort);
                    success = true;
                } catch (ConstraintViolationException ex) {
                    log.warn("ConstraintViolationException on allocate bfd discriminator");
                }
            }
            this.discriminator = bfdPort.getDiscriminator();
        }
    }

    private Optional<BfdPort> loadBfdPort() {
        return bfdPortRepository.findBySwitchIdAndPort(logicalEndpoint.getDatapath(), logicalEndpoint.getPortNumber());
    }

    private SwitchReference makeSwitchReference(SwitchId datapath) throws SwitchReferenceLookupException {
        Optional<Switch> potentialSw = switchRepository.findById(datapath);
        if (! potentialSw.isPresent()) {
            throw new SwitchReferenceLookupException(datapath, "persistent record is missing");
        }
        Switch sw = potentialSw.get();
        InetAddress address;
        try {
            address = InetAddress.getByName(sw.getAddress());
        } catch (UnknownHostException e) {
            throw new SwitchReferenceLookupException(
                    datapath,
                    String.format("unable to parse switch address \"%s\"", sw.getAddress()));
        }

        return new SwitchReference(datapath, address);
    }

    @Value
    @Builder
    public static class BfdPortFsmContext {
        private final IBfdPortCarrier output;

        private IslReference islReference;

        public static BfdPortFsmContextBuilder builder(IBfdPortCarrier outputAdapter) {
            return (new BfdPortFsmContextBuilder()).output(outputAdapter);
        }
    }

    public enum BfdPortFsmEvent {
        NEXT, KILL, FAIL,

        HISTORY,
        ENABLE, DISABLE,
        BI_ISL_MOVE,
        PORT_UP, PORT_DOWN,

        SPEAKER_SUCCESS, SPEAKER_FAIL, SPEAKER_TIMEOUT,

        _INIT_CHOICE_CLEAN, _INIT_CHOICE_DIRTY,
        _CLEANING_CHOICE_READY, _CLEANING_CHOICE_HOLD
    }

    public enum BfdPortFsmState {
        INIT,
        INIT_CHOICE,
        IDLE,

        INSTALLING,
        UP, DOWN,

        CLEANING,
        CLEANING_CHOICE,
        WAIT_RELEASE,

        FAIL,
        STOP
    }
}
