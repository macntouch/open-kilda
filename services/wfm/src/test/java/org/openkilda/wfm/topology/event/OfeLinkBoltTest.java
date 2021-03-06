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

package org.openkilda.wfm.topology.event;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.openkilda.messaging.Utils.DEFAULT_CORRELATION_ID;
import static org.openkilda.wfm.topology.event.OfeLinkBolt.NETWORK_TOPOLOGY_CHANGE_STREAM;
import static org.openkilda.wfm.topology.event.OfeLinkBolt.STATE_ID_DISCOVERY;

import org.openkilda.messaging.Destination;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.discovery.NetworkDumpSwitchData;
import org.openkilda.messaging.info.event.IslChangeType;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.event.PathNode;
import org.openkilda.messaging.info.event.PortChangeType;
import org.openkilda.messaging.info.event.PortInfoData;
import org.openkilda.messaging.info.event.SwitchChangeType;
import org.openkilda.messaging.info.event.SwitchInfoData;
import org.openkilda.messaging.model.DiscoveryLink;
import org.openkilda.messaging.model.DiscoveryLink.LinkState;
import org.openkilda.messaging.model.Switch;
import org.openkilda.messaging.model.SwitchPort;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.AbstractStormTest;
import org.openkilda.wfm.error.ConfigurationException;
import org.openkilda.wfm.isl.DiscoveryManager;
import org.openkilda.wfm.protocol.KafkaMessage;
import org.openkilda.wfm.topology.OutputCollectorMock;
import org.openkilda.wfm.topology.event.OfeLinkBolt.State;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.storm.state.InMemoryKeyValueState;
import org.apache.storm.state.KeyValueState;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.TupleImpl;
import org.apache.storm.tuple.Values;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OfeLinkBoltTest extends AbstractStormTest {

    private static final Integer TASK_ID_BOLT = 0;
    private static final int BFD_OFFSET = 200;
    private static final String STREAM_ID_INPUT = "input";

    private ObjectMapper objectMapper = new ObjectMapper();

    private TopologyContext context;
    private OfeLinkBolt bolt;
    private OutputCollectorMock outputDelegate;
    private OFEventWfmTopologyConfig config;

    @BeforeClass
    public static void setupOnce() throws Exception {
        AbstractStormTest.startZooKafkaAndStorm();
    }

    @Before
    public void before() throws CmdLineException, ConfigurationException {
        OfEventWfmTopology manager = new OfEventWfmTopology(
                AbstractStormTest.makeLaunchEnvironment());
        config = manager.getConfig();
        bolt = new OfeLinkBolt(config);

        context = Mockito.mock(TopologyContext.class);

        Mockito.when(context.getComponentId(TASK_ID_BOLT))
                .thenReturn(OfEventWfmTopology.DISCO_SPOUT_ID);
        Mockito.when(context.getComponentOutputFields(OfEventWfmTopology.DISCO_SPOUT_ID, STREAM_ID_INPUT))
                .thenReturn(KafkaMessage.FORMAT);

        outputDelegate = Mockito.spy(new OutputCollectorMock());
        OutputCollector output = new OutputCollector(outputDelegate);

        bolt.prepare(stormConfig(), context, output);
        bolt.initState(new InMemoryKeyValueState<>());
    }

    @AfterClass
    public static void teardownOnce() throws Exception {
        AbstractStormTest.stopZooKafkaAndStorm();
    }

    @Test
    public void invalidJsonForDiscoveryFilter() {
        Tuple tuple = new TupleImpl(context, new Values("{\"corrupted-json"), TASK_ID_BOLT,
                STREAM_ID_INPUT);
        bolt.doWork(tuple);

        Mockito.verify(outputDelegate).ack(tuple);
    }

    @Test
    public void shouldNotResetDiscoveryStatusOnSync() throws JsonProcessingException {
        // given
        DiscoveryLink testLink = new DiscoveryLink(new SwitchId("ff:01"), 2, new SwitchId("ff:02"), 2, 0, -1, true);

        KeyValueState<String, Object> boltState = new InMemoryKeyValueState<>();
        Map<SwitchId, List<DiscoveryLink>> links =
                Collections.singletonMap(testLink.getSource().getDatapath(), Collections.singletonList(testLink));
        boltState.put(STATE_ID_DISCOVERY, links);
        bolt.initState(boltState);

        // set the state to WAIT_SYNC
        bolt.state = State.SYNC_IN_PROGRESS;

        // when
        PortInfoData dumpPortData = new PortInfoData(new SwitchId("ff:01"), 2, PortChangeType.UP);
        InfoMessage dumpBeginMessage = new InfoMessage(dumpPortData, 0, DEFAULT_CORRELATION_ID, Destination.WFM);
        Tuple tuple = new TupleImpl(context, new Values(objectMapper.writeValueAsString(dumpBeginMessage)),
                TASK_ID_BOLT, STREAM_ID_INPUT);
        bolt.doWork(tuple);

        // then
        @SuppressWarnings("unchecked")
        Map<String, List<DiscoveryLink>> stateAfterSync =
                (Map<String, List<DiscoveryLink>>) boltState.get(STATE_ID_DISCOVERY);

        List<DiscoveryLink> linksAfterSync = stateAfterSync.values()
                .stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());

        assertThat(linksAfterSync, contains(
                allOf(hasProperty("source", hasProperty("datapath", is(new SwitchId("ff:01")))),
                        hasProperty("destination", hasProperty("datapath", is(new SwitchId("ff:02")))),
                        hasProperty("state", is(LinkState.ACTIVE)))));
    }

    @Test
    public void shouldNotProcessLoopedIsl() throws JsonProcessingException {
        final SwitchId switchId = new SwitchId("00:01");
        final int port = 1;
        DiscoveryLink discoveryLink = new DiscoveryLink(switchId, port, switchId, port, 0, -1, false);
        KeyValueState<String, Object> boltState = new InMemoryKeyValueState<>();
        Map<SwitchId, List<DiscoveryLink>> links =
                Collections.singletonMap(
                        discoveryLink.getSource().getDatapath(), Collections.singletonList(discoveryLink));
        boltState.put(STATE_ID_DISCOVERY, links);
        bolt.initState(boltState);
        bolt.state = State.MAIN;

        PathNode source = new PathNode(switchId, port, 0);
        PathNode destination = new PathNode(switchId, port, 1);
        IslInfoData isl = new IslInfoData(source, destination, IslChangeType.DISCOVERED, false);
        InfoMessage inputMessage = new InfoMessage(isl, 0, DEFAULT_CORRELATION_ID, Destination.WFM);
        Tuple tuple = new TupleImpl(context, new Values(objectMapper.writeValueAsString(inputMessage)),
                TASK_ID_BOLT, STREAM_ID_INPUT);
        bolt.doWork(tuple);

        assertFalse(discoveryLink.getState().isActive());
    }

    @Test
    public void checkBfdOffset() throws JsonProcessingException {
        final SwitchId switchId = new SwitchId("00:01");
        final int port = 205;
        KeyValueState<String, Object> boltState = new InMemoryKeyValueState<>();
        boltState.put(STATE_ID_DISCOVERY, Collections.emptyMap());
        bolt.state = State.MAIN;

        PortInfoData portInfoData = new PortInfoData(switchId, port, PortChangeType.DOWN);
        InfoMessage inputMessage = new InfoMessage(portInfoData, 0, DEFAULT_CORRELATION_ID, Destination.WFM);
        Tuple tuple = new TupleImpl(context, new Values(objectMapper.writeValueAsString(inputMessage)),
                TASK_ID_BOLT, STREAM_ID_INPUT);
        bolt.doWork(tuple);


        PortInfoData updatedData = new PortInfoData(switchId, port - config.getBfdPortOffset(), PortChangeType.DOWN);
        InfoMessage outputMessage = new InfoMessage(updatedData, 0, DEFAULT_CORRELATION_ID, Destination.WFM);

        Mockito.verify(outputDelegate).ack(tuple);
        Mockito.verify(outputDelegate).emit(eq(NETWORK_TOPOLOGY_CHANGE_STREAM),
                anyCollection(), eq(new Values(outputMessage)));
    }

    @Test
    public void testDispatchSyncInProgressMaskLogicalPorts() throws JsonProcessingException, UnknownHostException {
        final SwitchId switchId = new SwitchId("00:01");
        final InetAddress ipAddress = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        KeyValueState<String, Object> boltState = new InMemoryKeyValueState<>();
        boltState.put(STATE_ID_DISCOVERY, Collections.emptyMap());
        bolt.state = State.SYNC_IN_PROGRESS;
        List<SwitchPort> switchPorts = new ArrayList<>();
        switchPorts.add(new SwitchPort(1, SwitchPort.State.UP));
        switchPorts.add(new SwitchPort(BFD_OFFSET + 1, SwitchPort.State.UP));
        switchPorts.add(new SwitchPort(3, SwitchPort.State.UP));
        switchPorts.add(new SwitchPort(BFD_OFFSET + 3, SwitchPort.State.UP));
        Switch switchRecord = new Switch(switchId, ipAddress, new HashSet<>(), switchPorts);
        NetworkDumpSwitchData data = new NetworkDumpSwitchData(switchRecord);
        InfoMessage inputMessage = new InfoMessage(data, 0, DEFAULT_CORRELATION_ID, Destination.WFM);
        Tuple tuple = new TupleImpl(context, new Values(objectMapper.writeValueAsString(inputMessage)),
                TASK_ID_BOLT, STREAM_ID_INPUT);
        bolt.discovery = Mockito.mock(DiscoveryManager.class);
        ArgumentCaptor<Switch> switchCaptor = ArgumentCaptor.forClass(Switch.class);
        bolt.dispatchSyncInProgress(tuple, inputMessage);
        Mockito.verify(bolt.discovery, Mockito.times(1)).registerSwitch(switchCaptor.capture());

        assertEquals(2, switchCaptor.getValue().getPorts().size());
        assertEquals(0, switchCaptor.getValue().getPorts().stream()
                .filter(switchPort -> (switchPort.getNumber() > BFD_OFFSET)).count());
    }

    @Test
    public void testDispatchMainMaskLogicalPortsSwitchActivated() throws JsonProcessingException, UnknownHostException {
        final SwitchId switchId = new SwitchId("00:01");
        final InetAddress ipAddress = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        KeyValueState<String, Object> boltState = new InMemoryKeyValueState<>();
        boltState.put(STATE_ID_DISCOVERY, Collections.emptyMap());
        bolt.state = State.SYNC_IN_PROGRESS;
        List<SwitchPort> switchPorts = new ArrayList<>();
        switchPorts.add(new SwitchPort(1, SwitchPort.State.UP));
        switchPorts.add(new SwitchPort(BFD_OFFSET + 1, SwitchPort.State.UP));
        switchPorts.add(new SwitchPort(3, SwitchPort.State.UP));
        switchPorts.add(new SwitchPort(BFD_OFFSET + 3, SwitchPort.State.UP));
        Switch switchRecord = new Switch(switchId, ipAddress, new HashSet<>(), switchPorts);
        SwitchInfoData data = new SwitchInfoData(switchId, SwitchChangeType.ACTIVATED, null, null,
                null, null, false, switchRecord);
        InfoMessage inputMessage = new InfoMessage(data, 0, DEFAULT_CORRELATION_ID, Destination.WFM);
        Tuple tuple = new TupleImpl(context, new Values(objectMapper.writeValueAsString(inputMessage)),
                TASK_ID_BOLT, STREAM_ID_INPUT);
        bolt.discovery = Mockito.mock(DiscoveryManager.class);
        ArgumentCaptor<Switch> switchCaptor = ArgumentCaptor.forClass(Switch.class);
        bolt.dispatchMain(tuple, inputMessage);
        Mockito.verify(bolt.discovery, Mockito.times(1)).registerSwitch(switchCaptor.capture());

        assertEquals(2, switchCaptor.getValue().getPorts().size());
        assertEquals(0, switchCaptor.getValue().getPorts().stream()
                .filter(switchPort -> (switchPort.getNumber() > BFD_OFFSET)).count());
    }
}
