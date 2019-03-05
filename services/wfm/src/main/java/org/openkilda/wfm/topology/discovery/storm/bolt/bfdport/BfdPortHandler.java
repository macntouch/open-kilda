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

package org.openkilda.wfm.topology.discovery.storm.bolt.bfdport;

import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.AbstractException;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.hubandspoke.TaskIdBasedKeyFactory;
import org.openkilda.wfm.topology.discovery.model.Endpoint;
import org.openkilda.wfm.topology.discovery.model.IslReference;
import org.openkilda.wfm.topology.discovery.model.LinkStatus;
import org.openkilda.wfm.topology.discovery.model.facts.BfdPortFacts;
import org.openkilda.wfm.topology.discovery.service.DiscoveryBfdPortService;
import org.openkilda.wfm.topology.discovery.service.IBfdPortCarrier;
import org.openkilda.wfm.topology.discovery.storm.ComponentId;
import org.openkilda.wfm.topology.discovery.storm.bolt.bfdport.command.BfdPortCommand;
import org.openkilda.wfm.topology.discovery.storm.bolt.isl.IslHandler;
import org.openkilda.wfm.topology.discovery.storm.bolt.speaker.command.SpeakerBfdSessionSetupCommand;
import org.openkilda.wfm.topology.discovery.storm.bolt.speaker.command.SpeakerWorkerCommand;
import org.openkilda.wfm.topology.discovery.storm.bolt.sw.SwitchHandler;
import org.openkilda.wfm.topology.utils.MessageTranslator;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class BfdPortHandler extends AbstractBolt implements IBfdPortCarrier {
    public static final String BOLT_ID = ComponentId.BFD_PORT_HANDLER.toString();

    public static final String FIELD_ID_COMMAND_KEY = MessageTranslator.KEY_FIELD;
    public static final String FIELD_ID_COMMAND = "command";

    public static final String STREAM_SPEAKER_ID = "speaker";
    public static final Fields STREAM_SPEAKER_FIELDS = new Fields(FIELD_ID_COMMAND_KEY, FIELD_ID_COMMAND,
                                                                  FIELD_ID_CONTEXT);

    private final PersistenceManager persistenceManager;

    private transient DiscoveryBfdPortService service;

    public BfdPortHandler(PersistenceManager persistenceManager) {
        this.persistenceManager = persistenceManager;
    }

    @Override
    protected void handleInput(Tuple input) throws AbstractException {
        String source = input.getSourceComponent();
        if (SwitchHandler.BOLT_ID.equals(source)) {
            handleSwitchCommand(input);
        } else if (IslHandler.BOLT_ID.equals(source)) {
            handleIslCommand(input);
        } else {
            unhandledInput(input);
        }
    }

    private void handleSwitchCommand(Tuple input) throws PipelineException {
        handleCommand(input, SwitchHandler.FIELD_ID_COMMAND);
    }

    private void handleIslCommand(Tuple input) throws PipelineException {
        handleCommand(input, IslHandler.FIELD_ID_COMMAND);
    }

    private void handleCommand(Tuple input, String fieldName) throws PipelineException {
        BfdPortCommand command = pullValue(input, fieldName, BfdPortCommand.class);
        command.apply(this);
    }

    // -- carrier implementation --

    @Override
    public void setupBfdSession(String requestKey, NoviBfdSession bfdSession) {
        emit(STREAM_SPEAKER_ID, getCurrentTuple(), makeSpeakerTuple(new SpeakerBfdSessionSetupCommand(requestKey, bfdSession)));
    }

    // -- commands processing --

    public void processSetup(BfdPortFacts portFacts) {
        service.setup(portFacts);
    }

    public void processRemove(Endpoint endpoint) {
        service.remove(endpoint);
    }

    public void processEnable(Endpoint endpoint, IslReference reference) {
        service.enable(endpoint, reference);
    }

    public void processDisable(Endpoint endpoint, IslReference reference) {
        service.disable(endpoint, reference);
    }

    public void processLinkStatusUpdate(Endpoint endpoint, LinkStatus status) {
        service.updateLinkStatus(endpoint, status);
    }

    public void processOnlineModeUpdate(Endpoint endpoint, boolean mode) {
        service.updateOnlineMode(endpoint, mode);
    }

    public void processSpeakerResponse(Endpoint endpoint, BfdSessionResponse response) {
        service.speakerResponse(endpoint, response);
    }

    public void processSpeakerTimeout(Endpoint endpoint) {
        service.speakerTimeout(endpoint);
    }

    // -- setup --

    @Override
    protected void init() {
        TaskIdBasedKeyFactory keyFactory = new TaskIdBasedKeyFactory(getTaskId());
        service = new DiscoveryBfdPortService(this, persistenceManager, keyFactory);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declareStream(STREAM_SPEAKER_ID, STREAM_SPEAKER_FIELDS);
    }

    // -- private/service methods --

    private Values makeSpeakerTuple(SpeakerWorkerCommand command) {
        return new Values(command.getKey(), command, getCommandContext());
    }
}
