package org.bitbucket.openkilda.topology.service.impl;

import static org.bitbucket.openkilda.messaging.Utils.DEFAULT_CORRELATION_ID;
import static org.bitbucket.openkilda.topology.TestUtils.DIRECT_INCOMING_PORT;
import static org.bitbucket.openkilda.topology.TestUtils.DIRECT_OUTGOING_PORT;
import static org.bitbucket.openkilda.topology.TestUtils.INPUT_VLAN_ID;
import static org.bitbucket.openkilda.topology.TestUtils.OUTPUT_VLAN_ID;
import static org.bitbucket.openkilda.topology.TestUtils.dstSwitchId;
import static org.bitbucket.openkilda.topology.TestUtils.flowId;
import static org.bitbucket.openkilda.topology.TestUtils.srcSwitchId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.bitbucket.openkilda.messaging.payload.flow.FlowEndpointPayload;
import org.bitbucket.openkilda.messaging.payload.flow.FlowIdStatusPayload;
import org.bitbucket.openkilda.messaging.payload.flow.FlowPayload;
import org.bitbucket.openkilda.messaging.payload.flow.OutputVlanType;
import org.bitbucket.openkilda.topology.TestConfig;
import org.bitbucket.openkilda.topology.TestUtils;
import org.bitbucket.openkilda.topology.domain.Flow;
import org.bitbucket.openkilda.topology.domain.repository.FlowRepository;
import org.bitbucket.openkilda.topology.domain.repository.IslRepository;
import org.bitbucket.openkilda.topology.domain.repository.SwitchRepository;
import org.bitbucket.openkilda.topology.service.FlowService;
import org.bitbucket.openkilda.topology.service.IslService;
import org.bitbucket.openkilda.topology.service.SwitchService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.Set;

@RunWith(SpringJUnit4ClassRunner.class)
@ActiveProfiles("test")
@ContextConfiguration(classes = TestConfig.class)
public class FlowServiceImplTest {

    @Autowired
    SwitchRepository switchRepository;
    @Autowired
    IslRepository islRepository;
    @Autowired
    FlowRepository flowRepository;
    @Autowired
    SwitchService switchService;
    @Autowired
    IslService islService;
    @Autowired
    FlowService flowService;


    @Before
    public void setUp() throws Exception {
        TestUtils.createTopology(switchService, islService);
    }

    @After
    public void tearDown() throws Exception {
        flowRepository.deleteAll();
        islRepository.deleteAll();
        switchRepository.deleteAll();
    }

    @Test
    public void createFlow() throws Exception {
        FlowEndpointPayload firstEndpoint = new FlowEndpointPayload(srcSwitchId, DIRECT_INCOMING_PORT, INPUT_VLAN_ID);
        FlowEndpointPayload secondEndpoint = new FlowEndpointPayload(dstSwitchId, DIRECT_OUTGOING_PORT, OUTPUT_VLAN_ID);
        FlowPayload flowPayload = new FlowPayload(flowId, 0L, firstEndpoint, secondEndpoint, 10000L,
                "", "", OutputVlanType.NONE);

        flowService.createFlow(flowPayload, DEFAULT_CORRELATION_ID);

        Set<Flow> flows = flowRepository.findByFlowId(flowId);

        assertNotNull(flows);
        assertFalse(flows.isEmpty());
        assertEquals(2, flows.size());
    }

    @Test
    public void deleteFlow() throws Exception {
        FlowEndpointPayload firstEndpoint = new FlowEndpointPayload(srcSwitchId, 10, 100);
        FlowEndpointPayload secondEndpoint = new FlowEndpointPayload(dstSwitchId, 20, 100);
        FlowPayload flowPayload = new FlowPayload(flowId, 0L, secondEndpoint, firstEndpoint, 10000L,
                "", "", OutputVlanType.NONE);
        FlowIdStatusPayload flowIdStatusPayload = new FlowIdStatusPayload(flowId);
        flowService.createFlow(flowPayload, DEFAULT_CORRELATION_ID);

        flowService.deleteFlow(flowIdStatusPayload, DEFAULT_CORRELATION_ID);

        Set<Flow> flows = flowRepository.findByFlowId(flowId);

        assertNotNull(flows);
        assertTrue(flows.isEmpty());
    }

    @Test
    public void updateFlow() throws Exception {
        long updatedBandwidth = 20000L;
        FlowEndpointPayload firstEndpoint = new FlowEndpointPayload(srcSwitchId, 10, 100);
        FlowEndpointPayload secondEndpoint = new FlowEndpointPayload(dstSwitchId, 20, 100);
        FlowPayload flowPayload = new FlowPayload(flowId, 0L, secondEndpoint, firstEndpoint, 10000L,
                "", "", OutputVlanType.NONE);
        FlowPayload newFlowPayload = new FlowPayload(flowId, 0L, secondEndpoint, firstEndpoint, updatedBandwidth,
                "", "", OutputVlanType.NONE);

        flowService.createFlow(flowPayload, DEFAULT_CORRELATION_ID);

        flowService.updateFlow(newFlowPayload, DEFAULT_CORRELATION_ID);

        Set<Flow> flows = flowRepository.findByFlowId(flowId);
        assertNotNull(flows);
        assertFalse(flows.isEmpty());
        assertEquals(2, flows.size());

        for (Flow flow : flowRepository.findAll()) {
            assertEquals(flowId, flow.getFlowId());
            assertEquals(updatedBandwidth, flow.getBandwidth());
        }
    }

    /* TODO
    @Test
    public void repairFlow() throws Exception {
        switchService.add(new SwitchInfoData(alternativeSwitchId,
                SwitchEventType.ADDED, address, name, "Unknown"));
        switchService.activate(new SwitchInfoData(alternativeSwitchId,
                SwitchEventType.ACTIVATED, address, name, "Unknown"));

        srcNode = new PathNode(srcSwitchId, 3, 0);
        dstNode = new PathNode(alternativeSwitchId, 3, 1);
        list = asList(srcNode, dstNode);
        islService.discoverLink(new IslInfoData(10L, list, portSpeed));
        islService.discoverLink(new IslInfoData(10L, Lists.reverse(list), portSpeed));

        srcNode = new PathNode(alternativeSwitchId, 4, 0);
        dstNode = new PathNode(dstSwitchId, 4, 1);
        list = asList(srcNode, dstNode);
        islService.discoverLink(new IslInfoData(10L, list, portSpeed));
        islService.discoverLink(new IslInfoData(10L, Lists.reverse(list), portSpeed));

        FlowEndpointPayload firstEndpoint = new FlowEndpointPayload(srcSwitchId, 10, 100);
        FlowEndpointPayload secondEndpoint = new FlowEndpointPayload(dstSwitchId, 20, 100);
        FlowPayload flowPayload = new FlowPayload(flowId, 0L, secondEndpoint, firstEndpoint, 10000L,
                "", "", OutputVlanType.NONE);

        flowService.createFlow(flowPayload, DEFAULT_CORRELATION_ID);

        Set<Flow> flows = flowRepository.findByFlowId(flowId);
        assertNotNull(flows);
        assertFalse(flows.isEmpty());
        assertEquals(2, flows.size());
        for (Flow flow : flows) {
            assertEquals(3, flow.getFlowPath().size());
        }

        switchService.deactivate(new SwitchInfoData(alternativeSwitchId,
                SwitchEventType.DEACTIVATED, address, name, "Unknown"));

        flowService.repairFlows(alternativeSwitchId, DEFAULT_CORRELATION_ID);

        flows = flowRepository.findByFlowId(flowId);
        assertNotNull(flows);
        assertFalse(flows.isEmpty());
        for (Flow flow : flows) {
            assertEquals(4, flow.getFlowPath().size());
        }
    }
    */

    @Test
    public void getFlow() throws Exception {
    }

    @Test
    public void getFlows() throws Exception {
    }

    @Test
    public void pathFlow() throws Exception {
    }
}