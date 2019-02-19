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

package org.openkilda.wfm.share.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.openkilda.messaging.payload.flow.OverlappingSegmentsStats;
import org.openkilda.model.FlowSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;

import com.google.common.collect.Lists;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class IntersectionComputerTest {
    private static final SwitchId SWITCH_ID_A = new SwitchId("00:00:00:00:00:00:00:0A");
    private static final SwitchId SWITCH_ID_B = new SwitchId("00:00:00:00:00:00:00:0B");
    private static final SwitchId SWITCH_ID_C = new SwitchId("00:00:00:00:00:00:00:0C");
    private static final SwitchId SWITCH_ID_D = new SwitchId("00:00:00:00:00:00:00:0D");
    private static final SwitchId SWITCH_ID_E = new SwitchId("00:00:00:00:00:00:00:0E");

    private static final String FLOW_ID = "flow-id";
    private static final String FLOW_ID2 = "new-flow-id";

    private static final String PATH_ID = "old-path";
    private static final String NEW_PATH_ID = "new-path";

    private static final OverlappingSegmentsStats ZERO_STATS =
            new OverlappingSegmentsStats(0, 0, 0, 0);

    @Test
    public void noGroupIntersections() {
        List<FlowSegment> segments = getFlowSegments(PATH_ID);

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, segments);
        OverlappingSegmentsStats stats = computer.getOverlappingStats();

        assertEquals(ZERO_STATS, stats);
    }

    @Test
    public void noGroupIntersectionsInOneFlow() {
        List<FlowSegment> segments = getFlowSegments(PATH_ID);
        segments.addAll(getFlowSegments(NEW_PATH_ID));

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, segments);
        OverlappingSegmentsStats stats = computer.getOverlappingStats();

        assertEquals(ZERO_STATS, stats);
    }

    @Test
    public void shouldNoIntersections() {
        List<FlowSegment> segments = getFlowSegments(PATH_ID);
        segments.add(buildFlowSegment(SWITCH_ID_D, SWITCH_ID_E, 10, 10, NEW_PATH_ID, FLOW_ID2));
        segments.add(buildFlowSegment(SWITCH_ID_E, SWITCH_ID_D, 10, 10, NEW_PATH_ID, FLOW_ID2));

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, segments);
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID);

        assertEquals(ZERO_STATS, stats);
    }

    @Test
    public void shouldNotIntersectPathInSameFlow() {
        List<FlowSegment> segments = getFlowSegments(PATH_ID);
        segments.add(buildFlowSegment(SWITCH_ID_A, SWITCH_ID_D, 10, 10, NEW_PATH_ID, FLOW_ID));

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, segments);
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID);

        assertEquals(ZERO_STATS, stats);
    }

    @Test
    public void shouldNotFailIfNoSegments() {
        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, Collections.emptyList());
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID);

        assertEquals(ZERO_STATS, stats);
    }

    @Test
    public void shouldNotFailIfNoIntersectionSegments() {
        List<FlowSegment> segments = getFlowSegments(PATH_ID);

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, segments);
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID);

        assertEquals(ZERO_STATS, stats);
    }

    @Test
    public void switchIntersectionByPathId() {
        List<FlowSegment> segments = getFlowSegments(PATH_ID);
        segments.add(buildFlowSegment(SWITCH_ID_A, SWITCH_ID_D, 10, 10, NEW_PATH_ID, FLOW_ID2));

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, segments);
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID);

        assertEquals(new OverlappingSegmentsStats(0, 1, 0, 33), stats);
    }

    @Test
    public void switchIntersection() {
        List<FlowSegment> segments = getFlowSegments(PATH_ID);
        segments.add(buildFlowSegment(SWITCH_ID_A, SWITCH_ID_D, 10, 10, NEW_PATH_ID, FLOW_ID2));

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, segments);
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID);

        assertEquals(new OverlappingSegmentsStats(0, 1, 0, 33), stats);
    }

    @Test
    public void partialIntersection() {
        List<FlowSegment> segments = getFlowSegments(PATH_ID);
        segments.add(buildFlowSegment(SWITCH_ID_A, SWITCH_ID_B, 1, 1, NEW_PATH_ID, FLOW_ID2));

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, segments);
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID);

        assertEquals(new OverlappingSegmentsStats(1, 2, 50, 66), stats);
    }

    @Test
    public void fullIntersection() {
        List<FlowSegment> segments = getFlowSegments(PATH_ID);
        segments.addAll(getFlowSegments(NEW_PATH_ID, FLOW_ID2));

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, segments);
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID);

        assertEquals(new OverlappingSegmentsStats(2, 3, 100, 100), stats);
    }

    @Test
    public void isProtectedPathOverlapsPositive() {
        List<FlowSegment> primarySegments = getFlowSegments(PATH_ID);
        List<FlowSegment> protectedSegmets = Collections.singletonList(
                buildFlowSegment(SWITCH_ID_A, SWITCH_ID_B, 1, 1, PATH_ID));

        assertTrue(IntersectionComputer.isProtectedPathOverlaps(primarySegments, protectedSegmets));
    }

    @Test
    public void isProtectedPathOverlapsNegative() {
        List<FlowSegment> primarySegments = getFlowSegments(PATH_ID);
        List<FlowSegment> protectedSegmets = Collections.singletonList(
                buildFlowSegment(SWITCH_ID_A, SWITCH_ID_C, 3, 3, PATH_ID));

        assertFalse(IntersectionComputer.isProtectedPathOverlaps(primarySegments, protectedSegmets));
    }

    private List<FlowSegment> getFlowSegments(String pathId) {
        return Lists.newArrayList(
                buildFlowSegment(SWITCH_ID_A, SWITCH_ID_B, 1, 1, pathId),
                buildFlowSegment(SWITCH_ID_B, SWITCH_ID_A, 1, 1, pathId),
                buildFlowSegment(SWITCH_ID_B, SWITCH_ID_C, 2, 2, pathId),
                buildFlowSegment(SWITCH_ID_C, SWITCH_ID_B, 2, 2, pathId)
        );
    }

    private List<FlowSegment> getFlowSegments(String pathId, String flowId) {
        return Lists.newArrayList(
                buildFlowSegment(SWITCH_ID_A, SWITCH_ID_B, 1, 1, pathId, flowId),
                buildFlowSegment(SWITCH_ID_B, SWITCH_ID_A, 1, 1, pathId, flowId),
                buildFlowSegment(SWITCH_ID_B, SWITCH_ID_C, 2, 2, pathId, flowId),
                buildFlowSegment(SWITCH_ID_C, SWITCH_ID_B, 2, 2, pathId, flowId)
        );
    }

    private FlowSegment buildFlowSegment(SwitchId srcDpid, SwitchId dstDpid, int srcPort, int dstPort, String pathId) {
        return buildFlowSegment(srcDpid, dstDpid, srcPort, dstPort, pathId, FLOW_ID);
    }

    private FlowSegment buildFlowSegment(
            SwitchId srcDpid, SwitchId dstDpid, int srcPort, int dstPort, String pathId, String flowId) {
        Switch srcSwitch = Switch.builder().switchId(srcDpid).build();
        Switch dstSwitch = Switch.builder().switchId(dstDpid).build();

        return FlowSegment.builder()
                .srcSwitch(srcSwitch)
                .destSwitch(dstSwitch)
                .srcPort(srcPort)
                .destPort(dstPort)
                .flowId(flowId)
                .pathId(pathId)
                .build();
    }
}
