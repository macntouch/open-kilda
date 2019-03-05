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

package org.openkilda.wfm.topology.flowhs.service;

import static java.lang.String.format;

import org.openkilda.floodlight.flow.request.InstallEgressRule;
import org.openkilda.floodlight.flow.request.InstallIngressRule;
import org.openkilda.floodlight.flow.request.InstallTransitRule;
import org.openkilda.floodlight.flow.request.RemoveRule;
import org.openkilda.messaging.MessageContext;
import org.openkilda.messaging.command.switches.DeleteRulesCriteria;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.MeterId;
import org.openkilda.model.OutputVlanType;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.model.TransitVlan;
import org.openkilda.persistence.repositories.TransitVlanRepository;
import org.openkilda.wfm.CommandContext;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import com.google.common.collect.ImmutableList;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
public class FlowCommandFactory {
    private final NoArgGenerator commandIdGenerator = Generators.timeBasedGenerator();
    private final TransitVlanRepository transitVlanRepository;

    public FlowCommandFactory(TransitVlanRepository transitVlanRepository) {
        this.transitVlanRepository = transitVlanRepository;
    }

    /**
     * Creates commands for non ingress rules installation.
     * @param context operation's context.
     * @param flow input information for commands.
     * @return list with commands.
     */
    public List<InstallTransitRule> createInstallNonIngressRules(CommandContext context, Flow flow) {
        if (flow.isOneSwitchFlow()) {
            return Collections.emptyList();
        }

        List<InstallTransitRule> forwardRules = collectNonIngressRules(context, flow.getForwardPath(),
                flow.getDestPort(), flow.getSrcVlan(), flow.getDestVlan());
        List<InstallTransitRule> reverseRules = collectNonIngressRules(context, flow.getReversePath(),
                flow.getSrcPort(), flow.getDestVlan(), flow.getSrcVlan());
        return ListUtils.union(forwardRules, reverseRules);
    }

    /**
     * Creates commands for ingress rules installation.
     * @param context operation's context.
     * @param flow input information for commands.
     * @return list with commands.
     */
    public List<InstallIngressRule> createInstallIngressRules(CommandContext context, Flow flow) {
        InstallIngressRule forwardIngressRule = buildInstallIngressRule(context, flow.getForwardPath(),
                flow.getSrcPort(), flow.getSrcVlan(), flow.getDestVlan());
        InstallIngressRule reverseIngressRule = buildInstallIngressRule(context, flow.getReversePath(),
                flow.getDestPort(), flow.getDestVlan(), flow.getSrcVlan());

        return ImmutableList.of(forwardIngressRule, reverseIngressRule);
    }

    /**
     * Creates commands for non ingress rules deletion.
     * @param context operation's context.
     * @param flow input information for commands.
     * @return list with commands.
     */
    public List<RemoveRule> createRemoveNonIngressRules(CommandContext context, Flow flow) {
        if (flow.isOneSwitchFlow()) {
            // Removing of single switch rules is done with no output port in criteria.
            return Collections.emptyList();
        }

        List<RemoveRule> commands = new ArrayList<>();
        commands.addAll(collectRemoveNonIngressRules(context, flow.getForwardPath(), flow.getDestPort()));
        commands.addAll(collectRemoveNonIngressRules(context, flow.getReversePath(), flow.getSrcPort()));
        return commands;
    }

    /**
     * Creates commands for ingress rules deletion.
     * @param context operation's context.
     * @param flow input information for commands.
     * @return list with commands.
     */
    public List<RemoveRule> createRemoveIngressRules(CommandContext context, Flow flow) {
        RemoveRule removeForwardIngress =
                buildRemoveIngressRule(context, flow.getForwardPath(), flow.getSrcPort(), flow.getSrcVlan());
        RemoveRule removeReverseIngress =
                buildRemoveIngressRule(context, flow.getReversePath(), flow.getDestPort(), flow.getDestVlan());

        return ImmutableList.of(removeForwardIngress, removeReverseIngress);
    }

    private InstallIngressRule buildInstallIngressRule(CommandContext context, FlowPath flowPath, int inputPort,
                                                       int inputVlanId, int outputVlanId) {
        TransitVlan transitVlan = transitVlanRepository.findByPathId(flowPath.getPathId())
                .orElseThrow(() ->
                        new IllegalStateException(format("Transit vlan should be present for path %s for flow %s",
                                flowPath.getPathId(), flowPath.getFlowId())));

        PathSegment ingressSegment = flowPath.getSegments().stream()
                .filter(segment -> segment.getSrcSwitch().equals(flowPath.getSrcSwitch()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        format("PathSegment was not found for ingress flow rule, flowId: %s", flowPath.getFlowId())));

        String commandId = commandIdGenerator.generate().toString();
        return InstallIngressRule.builder()
                .messageContext(new MessageContext(commandId, context.getCorrelationId()))
                .commandId(commandIdGenerator.generate().toString())
                .flowId(flowPath.getFlowId())
                .switchId(flowPath.getSrcSwitch().getSwitchId())
                .cookie(flowPath.getCookie().getValue())
                .bandwidth(flowPath.getBandwidth())
                .meterId(Optional.ofNullable(flowPath.getMeterId())
                        .map(MeterId::getValue)
                        .orElse(null))
                .inputPort(inputPort)
                .outputPort(ingressSegment.getSrcPort())
                .outputVlanType(getOutputVlanType(inputVlanId, outputVlanId))
                .inputVlanId(inputVlanId)
                .transitVlanId(transitVlan.getVlan())
                .build();
    }

    private List<InstallTransitRule> collectNonIngressRules(CommandContext context, FlowPath flowPath, int outputPort,
                                                            int srcVlan, int destVlan) {
        if (flowPath == null || CollectionUtils.isEmpty(flowPath.getSegments())) {
            throw new IllegalArgumentException("Flow path with segments is required");
        }

        List<PathSegment> segments = flowPath.getSegments();
        List<InstallTransitRule> commands = new ArrayList<>(segments.size());

        int transitVlan = getTransitVlan(flowPath.getPathId());
        for (int i = 1; i < segments.size(); i++) {
            PathSegment income = segments.get(i - 1);
            PathSegment outcome = segments.get(i);

            InstallTransitRule transitRule = buildInstallTransitRule(context, flowPath,
                    income.getDestSwitch().getSwitchId(), income.getDestPort(), outcome.getSrcPort(), transitVlan);
            commands.add(transitRule);
        }

        PathSegment egressSegment = segments.get(segments.size() - 1);
        if (!egressSegment.getDestSwitch().getSwitchId().equals(flowPath.getDestSwitch().getSwitchId())) {
            throw new IllegalStateException(format("PathSegment was not found for egress flow rule pathId: %s",
                    flowPath.getPathId()));
        }

        InstallEgressRule egressRule = buildInstallEgressRule(context, flowPath, egressSegment.getDestPort(),
                outputPort, srcVlan, transitVlan, destVlan);
        commands.add(egressRule);

        return commands;
    }

    private InstallTransitRule buildInstallTransitRule(CommandContext context, FlowPath flowPath, SwitchId switchId,
                                                       int inputPort, int outputPort, int transitVlan) {
        String commandId = commandIdGenerator.generate().toString();
        MessageContext messageContext = new MessageContext(commandId, context.getCorrelationId());

        return new InstallTransitRule(messageContext, commandId, flowPath.getFlowId(), flowPath.getCookie().getValue(),
                switchId, inputPort, outputPort, transitVlan);
    }

    private InstallEgressRule buildInstallEgressRule(CommandContext context, FlowPath flowPath, int inputPort,
                                                     int outputPort, int srcVlan, int transitVlan, int destVlan) {
        String commandId = commandIdGenerator.generate().toString();
        return InstallEgressRule.builder()
                .messageContext(new MessageContext(commandId, context.getCorrelationId()))
                .commandId(commandId)
                .flowId(flowPath.getFlowId())
                .switchId(flowPath.getDestSwitch().getSwitchId())
                .cookie(flowPath.getCookie().getValue())
                .inputPort(inputPort)
                .transitVlanId(transitVlan)
                .outputPort(outputPort)
                .outputVlanId(destVlan)
                .outputVlanType(getOutputVlanType(srcVlan, destVlan))
                .build();
    }

    private RemoveRule buildRemoveIngressRule(CommandContext context, FlowPath flowPath, int inputPort,
                                              int inputVlanId) {
        PathSegment ingressSegment = flowPath.getSegments().stream()
                .filter(segment -> segment.getSrcSwitch().equals(flowPath.getSrcSwitch()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        format("PathSegment was not found for ingress flow rule, flowId: %s", flowPath.getFlowId())));

        DeleteRulesCriteria ingressCriteria = new DeleteRulesCriteria(flowPath.getCookie().getValue(), inputPort,
                inputVlanId, 0, ingressSegment.getSrcPort());
        String commandId = commandIdGenerator.generate().toString();
        return RemoveRule.builder()
                .messageContext(new MessageContext(commandId, context.getCorrelationId()))
                .commandId(commandId)
                .flowId(flowPath.getFlowId())
                .switchId(flowPath.getSrcSwitch().getSwitchId())
                .cookie(flowPath.getCookie().getValue())
                .meterId(Optional.ofNullable(flowPath.getMeterId())
                        .map(MeterId::getValue)
                        .orElse(null))
                .criteria(ingressCriteria)
                .build();
    }

    private List<RemoveRule> collectRemoveNonIngressRules(CommandContext context, FlowPath flowPath, int outputPort) {
        if (flowPath == null || CollectionUtils.isEmpty(flowPath.getSegments())) {
            throw new IllegalArgumentException("Flow path with segments is required");
        }

        List<PathSegment> segments = flowPath.getSegments();
        List<RemoveRule> commands = new ArrayList<>(segments.size());

        int transitVlan = getTransitVlan(flowPath.getPathId());
        for (int i = 1; i < segments.size(); i++) {
            PathSegment income = segments.get(i - 1);
            PathSegment outcome = segments.get(i);

            RemoveRule transitRule = buildRemoveTransitRule(context, flowPath, income.getDestSwitch().getSwitchId(),
                    income.getDestPort(), outcome.getSrcPort(), transitVlan);
            commands.add(transitRule);
        }

        PathSegment egressSegment = segments.get(segments.size() - 1);
        if (!egressSegment.getDestSwitch().getSwitchId().equals(flowPath.getDestSwitch().getSwitchId())) {
            throw new IllegalStateException(
                    format("PathSegment was not found for egress flow rule, flowId: %s", flowPath.getFlowId()));
        }

        RemoveRule egressRule =
                buildRemoveEgressRule(context, flowPath, egressSegment.getDestPort(), outputPort, transitVlan);
        commands.add(egressRule);

        return commands;
    }

    private RemoveRule buildRemoveTransitRule(CommandContext context, FlowPath flowPath, SwitchId switchId,
                                              int inputPort, int outputPort, int transitVlan) {
        DeleteRulesCriteria criteria = new DeleteRulesCriteria(flowPath.getCookie().getValue(), inputPort, transitVlan,
                0, outputPort);
        String commandId = commandIdGenerator.generate().toString();
        return RemoveRule.builder()
                .messageContext(new MessageContext(commandId, context.getCorrelationId()))
                .commandId(commandId)
                .flowId(flowPath.getFlowId())
                .cookie(flowPath.getCookie().getValue())
                .switchId(switchId)
                .criteria(criteria)
                .build();
    }

    private RemoveRule buildRemoveEgressRule(CommandContext context, FlowPath flowPath, int inputPort, int outputPort,
                                             int transitVlan) {
        DeleteRulesCriteria criteria = new DeleteRulesCriteria(flowPath.getCookie().getValue(), inputPort, transitVlan,
                0, outputPort);
        String commandId = commandIdGenerator.generate().toString();
        return RemoveRule.builder()
                .messageContext(new MessageContext(commandId, context.getCorrelationId()))
                .commandId(commandId)
                .flowId(flowPath.getFlowId())
                .cookie(flowPath.getCookie().getValue())
                .criteria(criteria)
                .switchId(flowPath.getDestSwitch().getSwitchId())
                .build();
    }

    private OutputVlanType getOutputVlanType(int srcVlan, int destVlan) {
        if (srcVlan == 0) {
            return destVlan == 0 ? OutputVlanType.NONE : OutputVlanType.PUSH;
        }
        return destVlan == 0 ? OutputVlanType.POP : OutputVlanType.REPLACE;
    }

    private int getTransitVlan(PathId pathId) {
        return transitVlanRepository.findByPathId(pathId)
                .map(TransitVlan::getVlan)
                .orElseThrow(() ->
                        new IllegalStateException(format("No flow path found for flow %s", pathId)));
    }

}
