/* Copyright 2018 Telstra Open Source
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

package org.openkilda.wfm.topology.floodlightrouter.bolts;

import static org.openkilda.messaging.Utils.MAPPER;

import org.openkilda.messaging.Message;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.floodlightrouter.ComponentType;
import org.openkilda.wfm.topology.floodlightrouter.Stream;
import org.openkilda.wfm.topology.floodlightrouter.service.DiscoRouterService;
import org.openkilda.wfm.topology.floodlightrouter.service.FloodlightTracker;
import org.openkilda.wfm.topology.floodlightrouter.service.MessageSender;
import org.openkilda.wfm.topology.floodlightrouter.service.RequestTracker;
import org.openkilda.wfm.topology.utils.AbstractTickStatefulBolt;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.storm.state.InMemoryKeyValueState;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

public class DiscoRouterBolt extends AbstractTickStatefulBolt<InMemoryKeyValueState<String, Object>>
        implements MessageSender {

    private static final Logger logger = LoggerFactory.getLogger(RouterBolt.class);
    private static final String ROUTER_SERVICE = "ROUTER_SERVICE";


    private final Set<String> floodlights;
    private final long floodlightAliveTimeout;
    private final long floodlightRequestTimeout;
    private final long messageBlacklistTimeout;
    private final long floodlightAliveInterval;

    private transient DiscoRouterService routerService;

    protected OutputCollector outputCollector;
    private Tuple currentTuple;

    public DiscoRouterBolt(Set<String> floodlights, long floodlightAliveTimeout, long floodlightAliveInterval,
                      long floodlightRequestTimeout, long messageBlacklistTimeout) {
        this.floodlights = floodlights;
        this.floodlightAliveTimeout = floodlightAliveTimeout;
        this.floodlightRequestTimeout = floodlightRequestTimeout;
        this.floodlightAliveInterval = floodlightAliveInterval;
        this.messageBlacklistTimeout = messageBlacklistTimeout;
    }

    @Override
    protected void doTick(Tuple tuple) {
        currentTuple = tuple;
        routerService.doPeriodicProcessing(this);
    }

    @Override
    protected void doWork(Tuple input) {
        String sourceComponent = input.getSourceComponent();
        currentTuple = input;
        try {
            String json = input.getValueByField(AbstractTopology.MESSAGE_FIELD).toString();
            Message message = MAPPER.readValue(json, Message.class);
            switch (sourceComponent) {
                case ComponentType.ROUTER_TOPO_DISCO_SPOUT:
                    routerService.processSpeakerDiscoResponse(this, message);
                    break;
                case ComponentType.SPEAKER_DISCO_KAFKA_SPOUT:
                    routerService.processDiscoSpeakerRequest(this, message);
                    break;
                default:
                    logger.error("Unknown input stream handled: {}", sourceComponent);
                    break;
            }
        } catch (Exception e) {
            logger.error("failed to process message");
        } finally {
            outputCollector.ack(input);
        }
    }

    @Override
    public void initState(InMemoryKeyValueState<String, Object> entries) {
        routerService = (DiscoRouterService) entries.get(ROUTER_SERVICE);
        if (routerService == null) {
            RequestTracker requestTracker = new RequestTracker(floodlightRequestTimeout, messageBlacklistTimeout);
            FloodlightTracker floodlightTracker = new FloodlightTracker(floodlights, floodlightAliveTimeout,
                    floodlightAliveInterval);
            routerService = new DiscoRouterService(floodlightTracker, requestTracker);
            entries.put(ROUTER_SERVICE, routerService);
        }
    }

    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        this.outputCollector = outputCollector;
        super.prepare(map, topologyContext, outputCollector);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        for (String region : floodlights) {
            outputFieldsDeclarer.declareStream(Stream.formatWithRegion(Stream.SPEAKER_DISCO, region),
                    new Fields(AbstractTopology.MESSAGE_FIELD));
        }
        outputFieldsDeclarer.declareStream(Stream.TOPO_DISCO, new Fields(AbstractTopology.MESSAGE_FIELD));
        outputFieldsDeclarer.declareStream(Stream.REGION_NOTIFICATION, new Fields(AbstractTopology.MESSAGE_FIELD));
    }


    /**
     * Send message object via target stream.
     * @param message object to be sent
     * @param outputStream target stream
     */
    public void send(Message message, String outputStream) {
        try {
            String json = MAPPER.writeValueAsString(message);
            Values values = new Values(json);
            outputCollector.emit(outputStream, currentTuple, values);
        } catch (JsonProcessingException e) {
            logger.error("failed to serialize message {}", message);
        }
    }

    public  void send(Object payload, String outputStream) {
        Values values = new Values(payload);
        outputCollector.emit(outputStream, currentTuple, values);
    }
}
