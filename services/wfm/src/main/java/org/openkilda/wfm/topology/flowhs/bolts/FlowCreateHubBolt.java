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

package org.openkilda.wfm.topology.flowhs.bolts;

import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_NB_RESPONSE_SENDER;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_SPEAKER_WORKER;
import static org.openkilda.wfm.topology.utils.KafkaRecordTranslator.FIELD_ID_PAYLOAD;

import org.openkilda.floodlight.flow.request.FlowRequest;
import org.openkilda.messaging.model.FlowDto;
import org.openkilda.pce.PathComputerConfig;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.hubandspoke.HubBolt;
import org.openkilda.wfm.topology.flowhs.model.FlowCommands;
import org.openkilda.wfm.topology.flowhs.service.FlowCreateService;
import org.openkilda.wfm.topology.utils.MessageTranslator;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.util.List;

public class FlowCreateHubBolt extends HubBolt {

    private transient FlowCreateService service;

    private PersistenceManager persistenceManager;
    private PathComputerConfig pathComputerConfig;
    private FlowResourcesConfig flowResourcesConfig;

    public FlowCreateHubBolt(String requestSenderComponent, int timeoutMs, boolean autoAck,
                             PersistenceManager persistenceManager, PathComputerConfig pathComputerConfig,
                             FlowResourcesConfig flowResourcesConfig) {
        super(requestSenderComponent, timeoutMs, autoAck);

        this.persistenceManager = persistenceManager;
        this.pathComputerConfig = pathComputerConfig;
        this.flowResourcesConfig = flowResourcesConfig;
        this.service = new FlowCreateService(persistenceManager, pathComputerConfig, flowResourcesConfig);
    }

    @Override
    protected void init() {
        service = new FlowCreateService(persistenceManager, pathComputerConfig, flowResourcesConfig);
    }

    @Override
    protected void onRequest(Tuple input) throws PipelineException {
        String key = input.getStringByField(MessageTranslator.KEY_FIELD);
        FlowDto payload = (FlowDto) input.getValueByField(FIELD_ID_PAYLOAD);
        service.handleRequest(key, pullContext(input), payload, new FlowCreateHubCarrierImpl(input));
    }

    @Override
    protected void onWorkerResponse(Tuple input) {
        String key = input.getStringByField(MessageTranslator.KEY_FIELD);
        service.handleAsyncResponse(key);
    }

    @Override
    public void onTimeout(String key, Tuple tuple) throws PipelineException {

    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        super.declareOutputFields(declarer);

        declarer.declareStream(HUB_TO_SPEAKER_WORKER.name(), MessageTranslator.STREAM_FIELDS);
        declarer.declareStream(HUB_TO_NB_RESPONSE_SENDER.name(), MessageTranslator.STREAM_FIELDS);
    }

    private class FlowCreateHubCarrierImpl implements FlowCreateHubCarrier {
        private Tuple tuple;

        public FlowCreateHubCarrierImpl(Tuple tuple) {
            this.tuple = tuple;
        }

        @Override
        public void sendInstallCommands(List<? extends FlowRequest> commands) {
            try {
                String key = tuple.getStringByField(MessageTranslator.KEY_FIELD);
                Values values = new Values(key, new FlowCommands(commands));
                emit(HUB_TO_SPEAKER_WORKER.name(), tuple, values);
            } catch (PipelineException e) {
                log.error("Failed to send install commands", e);
            }
        }

        @Override
        public void sendClearUpCommands() {

        }

        @Override
        public void dumpRules() {

        }

        @Override
        public void sendNorthboundResponse() {

        }
    }
}
