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

package org.openkilda.wfm.topology.nbworker.services;

import static java.util.Collections.emptyList;
import static org.apache.commons.collections4.ListUtils.union;

import org.openkilda.messaging.model.FlowPathDto;
import org.openkilda.messaging.model.FlowPathDto.FlowPathDtoBuilder;
import org.openkilda.messaging.model.FlowPathDto.FlowProtectedPathDto;
import org.openkilda.messaging.payload.flow.PathNodePayload;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPair;
import org.openkilda.model.FlowSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.FlowSegmentRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.error.IslNotFoundException;
import org.openkilda.wfm.share.service.IntersectionComputer;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class FlowOperationsService {

    private TransactionManager transactionManager;
    private IslRepository islRepository;
    private FlowRepository flowRepository;
    private FlowSegmentRepository flowSegmentRepository;

    public FlowOperationsService(RepositoryFactory repositoryFactory, TransactionManager transactionManager) {
        this.islRepository = repositoryFactory.createIslRepository();
        this.flowRepository = repositoryFactory.createFlowRepository();
        this.transactionManager = transactionManager;
        this.flowSegmentRepository = repositoryFactory.createFlowSegmentRepository();
    }

    /**
     * Return all flows for a particular link.
     *
     * @param srcSwitchId source switch id.
     * @param srcPort source port.
     * @param dstSwitchId destination switch id.
     * @param dstPort destination port.
     * @return all flows for a particular link.
     * @throws IslNotFoundException if there is no link with these parameters.
     */
    public Collection<FlowPair> getFlowIdsForLink(SwitchId srcSwitchId, Integer srcPort,
                                                SwitchId dstSwitchId, Integer dstPort)
            throws IslNotFoundException {

        if (!islRepository.findByEndpoints(srcSwitchId, srcPort, dstSwitchId, dstPort).isPresent()) {
            throw new IslNotFoundException(srcSwitchId, srcPort, dstSwitchId, dstPort);
        }

        return flowRepository.findAllFlowPairsWithSegment(srcSwitchId, srcPort, dstSwitchId, dstPort);
    }

    /**
     * Return flows for a switch.
     *
     * @param switchId switch id.
     * @return all flows for a switch.
     */
    public Set<String> getFlowIdsForSwitch(SwitchId switchId) {
        return flowRepository.findFlowIdsBySwitch(switchId);
    }

    /**
     * Returns flow path. If flow has group, returns also path for each flow in group.
     *
     * @param flowId the flow to get a path.
     */
    public List<FlowPathDto> getFlowPath(String flowId) throws FlowNotFoundException {
        FlowPair currentFlow = flowRepository.findFlowPairById(flowId)
                .orElseThrow(() -> new FlowNotFoundException(flowId));
        Flow forwardFlow = currentFlow.getForward();

        String groupId = forwardFlow.getGroupId();
        if (groupId == null) {
            Map<Long, List<FlowSegment>> segmentsByCookie = flowSegmentRepository.findByFlowId(flowId).stream()
                    .collect(Collectors.groupingBy(FlowSegment::getCookie, Collectors.toList()));

            return Collections.singletonList(
                    toFlowPathDtoBuilder(currentFlow, segmentsByCookie)
                            .build());
        } else {
            Collection<FlowPair> flowPairsInGroup = flowRepository.findFlowPairsByGroupId(groupId);
            Collection<FlowSegment> flowSegmentsInGroup = flowSegmentRepository.findByFlowGroupId(groupId);

            Map<Long, List<FlowSegment>> segmentsByCookie = flowSegmentsInGroup.stream()
                    .collect(Collectors.groupingBy(FlowSegment::getCookie, Collectors.toList()));

            IntersectionComputer primaryIntersectionComputer = new IntersectionComputer(
                    forwardFlow.getFlowId(), forwardFlow.getPrimaryPathId(), flowSegmentsInGroup);

            // target flow primary path
            FlowPathDtoBuilder targetFlowDtoBuilder = this.toFlowPathDtoBuilder(currentFlow, segmentsByCookie)
                    .segmentsStats(primaryIntersectionComputer.getOverlappingStats());

            // other flows in the the group
            List<FlowPathDto> payloads = flowPairsInGroup.stream()
                    .filter(e -> !e.getForward().getFlowId().equals(flowId))
                    .map(e -> this.mapGroupPathFlowDto(e, true, primaryIntersectionComputer, segmentsByCookie))
                    .collect(Collectors.toList());

            if (forwardFlow.isAllocateProtectedPath()) {
                IntersectionComputer protectedIntersectionComputer = new IntersectionComputer(
                        forwardFlow.getFlowId(), forwardFlow.getProtectedPathId(), flowSegmentsInGroup);

                // target flow protected path
                targetFlowDtoBuilder.protectedPath(FlowProtectedPathDto.builder()
                        .forwardPath(buildFlowPath(
                                currentFlow.getForward(),
                                segmentsByCookie.get(currentFlow.getForward().getProtectedCookie())))
                        .reversePath(buildFlowPath(
                                currentFlow.getReverse(),
                                segmentsByCookie.get(currentFlow.getReverse().getProtectedCookie())))
                        .segmentsStats(
                                protectedIntersectionComputer.getOverlappingStats())
                        .build());

                // other flows in the the group
                List<FlowPathDto> protectedPathPayloads = flowPairsInGroup.stream()
                        .filter(e -> !e.getForward().getFlowId().equals(flowId))
                        .map(e -> this.mapGroupPathFlowDto(e, false, protectedIntersectionComputer, segmentsByCookie))
                        .collect(Collectors.toList());
                payloads = union(payloads, protectedPathPayloads);
            }

            payloads.add(targetFlowDtoBuilder.build());

            return payloads;
        }
    }

    private FlowPathDto mapGroupPathFlowDto(FlowPair e, boolean primaryPathCorrespondStat,
                                            IntersectionComputer intersectionComputer,
                                            Map<Long, List<FlowSegment>> segmentsByCookie) {
        FlowPathDtoBuilder builder = this.toFlowPathDtoBuilder(e, segmentsByCookie)
                .primaryPathCorrespondStat(primaryPathCorrespondStat)
                .segmentsStats(
                        intersectionComputer.getOverlappingStats(e.getForward().getPrimaryPathId()));
        if (e.getForward().isAllocateProtectedPath()) {
            builder.protectedPath(FlowProtectedPathDto.builder()
                    .forwardPath(buildFlowPath(
                            e.getForward(),
                            segmentsByCookie.get(e.getForward().getProtectedCookie())))
                    .reversePath(buildFlowPath(
                            e.getReverse(),
                            segmentsByCookie.get(e.getReverse().getProtectedCookie())))
                    .segmentsStats(
                            intersectionComputer.getOverlappingStats(e.getForward().getProtectedPathId()))
                    .build());
        }
        return builder.build();
    }

    private FlowPathDtoBuilder toFlowPathDtoBuilder(FlowPair flowPair,
                                                                Map<Long, List<FlowSegment>> segmentsByCookie) {
        Flow forward = flowPair.getForward();
        Flow reverse = flowPair.getReverse();
        return FlowPathDto.builder()
                .id(forward.getFlowId())
                .forwardPath(buildFlowPath(forward, segmentsByCookie.get(forward.getCookie())))
                .reversePath(buildFlowPath(reverse, segmentsByCookie.get(reverse.getCookie())));
    }

    private List<PathNodePayload> buildFlowPath(Flow flow, List<FlowSegment> segments) {
        segments = MoreObjects.firstNonNull(segments, emptyList());

        // single switch
        if (segments.isEmpty()) {
            return Collections.singletonList(
                    new PathNodePayload(flow.getSrcSwitch().getSwitchId(), flow.getSrcPort(), flow.getDestPort()));
        }

        if (segments.size() == 1) {
            FlowSegment segment = segments.get(0);
            return Lists.newArrayList(
                    new PathNodePayload(flow.getSrcSwitch().getSwitchId(), flow.getSrcPort(), segment.getSrcPort()),
                    new PathNodePayload(
                            segment.getDestSwitch().getSwitchId(), segment.getDestPort(), flow.getDestPort()));
        }

        List<PathNodePayload> resultList = new ArrayList<>();
        segments.sort(Comparator.comparing(FlowSegment::getSeqId));

        // inbound switch
        resultList.add(new PathNodePayload(
                flow.getSrcSwitch().getSwitchId(), flow.getSrcPort(), segments.get(0).getSrcPort()));

        for (int i = 1; i < segments.size(); i++) {
            FlowSegment previous = segments.get(i - 1);
            FlowSegment next = segments.get(i);

            resultList.add(new PathNodePayload(
                    previous.getDestSwitch().getSwitchId(), previous.getDestPort(), next.getSrcPort()));
        }
        // dest switch
        resultList.add(new PathNodePayload(
                flow.getDestSwitch().getSwitchId(), segments.get(segments.size() - 1).getDestPort(), flow.getDestPort())
        );
        return resultList;
    }

    /**
     * Update flow.
     *
     * @param flow flow.
     * @return updated flow.
     */
    public Flow updateFlow(Flow flow) throws FlowNotFoundException {
        return transactionManager.doInTransaction(() -> {
            Optional<FlowPair> foundFlowPair = flowRepository.findFlowPairById(flow.getFlowId());
            if (!foundFlowPair.isPresent()) {
                return Optional.<Flow>empty();
            }
            FlowPair currentFlowPair = foundFlowPair.get();

            Flow forwardFlow = currentFlowPair.getForward();
            Flow reverseFlow = currentFlowPair.getReverse();

            if (flow.getMaxLatency() != null) {
                forwardFlow.setMaxLatency(flow.getMaxLatency());
                reverseFlow.setMaxLatency(flow.getMaxLatency());
            }
            if (flow.getPriority() != null) {
                forwardFlow.setPriority(flow.getPriority());
                reverseFlow.setPriority(flow.getPriority());
            }

            flowRepository.createOrUpdate(FlowPair.builder()
                    .forward(forwardFlow)
                    .reverse(reverseFlow)
                    .build());

            return Optional.of(forwardFlow);

        }).orElseThrow(() -> new FlowNotFoundException(flow.getFlowId()));
    }
}
