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

package org.openkilda.wfm.topology.floodlightrouter.service;

import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.discovery.DiscoverIslCommandData;
import org.openkilda.messaging.command.discovery.DiscoverPathCommandData;
import org.openkilda.messaging.command.flow.BaseInstallFlow;
import org.openkilda.messaging.command.flow.BatchInstallRequest;
import org.openkilda.messaging.command.flow.DeleteMeterRequest;
import org.openkilda.messaging.command.flow.MeterModifyCommandRequest;
import org.openkilda.messaging.command.flow.RemoveFlow;
import org.openkilda.messaging.command.switches.DumpMetersRequest;
import org.openkilda.messaging.command.switches.DumpPortDescriptionRequest;
import org.openkilda.messaging.command.switches.DumpRulesRequest;
import org.openkilda.messaging.command.switches.DumpSwitchPortsDescriptionRequest;
import org.openkilda.messaging.command.switches.PortConfigurationRequest;
import org.openkilda.messaging.command.switches.SwitchRulesDeleteRequest;
import org.openkilda.messaging.command.switches.SwitchRulesInstallRequest;
import org.openkilda.messaging.command.switches.ValidateRulesRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.floodlight.request.PingRequest;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.topology.floodlightrouter.Stream;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;

@Slf4j
public class RouterService {
    private final FloodlightTracker floodlightTracker;
    private final RequestTracker requestTracker;
    private final Set<String> floodlights;

    public RouterService(FloodlightTracker floodlightTracker, RequestTracker requestTracker, Set<String> floodlights) {
        this.floodlightTracker = floodlightTracker;
        this.requestTracker = requestTracker;
        this.floodlights = floodlights;
    }

    /**
     * Process periodic state update.
     * @param routerMessageSender callback to be used for message sending
     */
    public void doPeriodicProcessing(MessageSender routerMessageSender) {
        requestTracker.cleanupOldMessages();
    }

    /**
     * Process response from speaker flow.
     * @param routerMessageSender callback to be used for message sending
     * @param message message to be handled and resend
     */
    public void processSpeakerFlowResponse(MessageSender routerMessageSender, Message message) {
        if (!requestTracker.checkReplyMessage(message.getCorrelationId(), false)) {
            log.debug("Received outdated message {}", message);
            return;
        }
        routerMessageSender.send(message, Stream.KILDA_FLOW);
    }

    /**
     * Process request to speaker ping.
     * @param routerMessageSender callback to be used for message sending
     * @param message message to be handled and resend
     */
    public void processSpeakerPingRequest(MessageSender routerMessageSender, Message message) {
        SwitchId switchId = lookupSwitchIdInCommandMessage(message);
        if (switchId != null) {
            String region = floodlightTracker.lookupRegion(switchId);
            if (region == null) {
                log.error("Received command message for the untracked switch: {} {}", switchId, message);
            } else {
                routerMessageSender.send(message, Stream.formatWithRegion(Stream.SPEAKER_PING, region));
            }
        } else {
            log.warn("Received message without target switch from SPEAKER_PING stream: {}", message);
        }
    }

    /**
     * Process request to speaker flow.
     * @param routerMessageSender callback to be used for message sending
     * @param message message to be handled and resend
     */
    public void processSpeakerFlowRequest(MessageSender routerMessageSender, Message message) {
        SwitchId switchId = lookupSwitchIdInCommandMessage(message);
        if (switchId != null) {
            requestTracker.trackMessage(message.getCorrelationId(), false);
            String region = floodlightTracker.lookupRegion(switchId);
            if (region == null) {
                log.error("Received command message for the untracked switch: {} {}", switchId, message);
            } else {
                String stream = Stream.formatWithRegion(Stream.SPEAKER_FLOW, region);
                routerMessageSender.send(message, stream);
            }
        } else {
            log.warn("Received message without target switch from SPEAKER_FLOW stream: {}", message);
        }
    }

    /**
     * Process request to speaker.
     * @param routerMessageSender callback to be used for message sending
     * @param message message to be handled and resend
     */
    public void processSpeakerRequest(MessageSender routerMessageSender, Message message) {
        SwitchId switchid = lookupSwitchIdInCommandMessage(message);
        requestTracker.trackMessage(message.getCorrelationId(), false);
        if (switchid == null) {
            log.debug("No target switch found, processing to all regions: {}", message);
            for (String region: floodlights) {
                routerMessageSender.send(message, Stream.formatWithRegion(Stream.SPEAKER, region));
            }
        } else {
            String region = floodlightTracker.lookupRegion(switchid);
            if (region != null) {
                String stream = Stream.formatWithRegion(Stream.SPEAKER, region);
                routerMessageSender.send(message, stream);
            } else {
                log.error("Received command message for the untracked switch: {} {}", switchid, message);
                if (message instanceof CommandMessage) {
                    CommandMessage commandMessage = (CommandMessage) message;
                    if (commandMessage.getData() instanceof ValidateRulesRequest) {
                        String errorDetails = String.format("Switch %s was not found", switchid.toString());
                        ErrorData errorData = new ErrorData(ErrorType.NOT_FOUND, errorDetails, errorDetails);
                        ErrorMessage errorMessage = new ErrorMessage(errorData, System.currentTimeMillis(),
                                message.getCorrelationId(), null);
                        routerMessageSender.send(errorMessage, Stream.NORTHBOUND_REPLY);
                    }
                }
            }
        }
    }

    public void updateSwitchMapping(SwitchLocation location) {
        floodlightTracker.updateSwitchRegion(location.getSwitchId(), location.getRegion());
    }

    private SwitchId lookupSwitchIdInCommandMessage(Message message) {
        if (message instanceof CommandMessage) {
            CommandData commandData = ((CommandMessage) message).getData();
            if (commandData instanceof BaseInstallFlow) {
                return ((BaseInstallFlow) commandData).getSwitchId();
            } else if (commandData instanceof RemoveFlow) {
                return ((RemoveFlow) commandData).getSwitchId();
            } else if (commandData instanceof DiscoverIslCommandData) {
                return ((DiscoverIslCommandData) commandData).getSwitchId();
            } else if (commandData instanceof PingRequest) {
                return ((PingRequest) commandData).getPing().getSource().getDatapath();
            } else if (commandData instanceof DiscoverPathCommandData) {
                return ((DiscoverPathCommandData) commandData).getSrcSwitchId();
            } else if (commandData instanceof SwitchRulesDeleteRequest) {
                return ((SwitchRulesDeleteRequest) commandData).getSwitchId();
            } else if (commandData instanceof SwitchRulesInstallRequest) {
                return ((SwitchRulesInstallRequest) commandData).getSwitchId();
            } else if (commandData instanceof DumpRulesRequest) {
                return ((DumpRulesRequest) commandData).getSwitchId();
            } else if (commandData instanceof BatchInstallRequest) {
                return ((BatchInstallRequest) commandData).getSwitchId();
            } else if (commandData instanceof DeleteMeterRequest) {
                return ((DeleteMeterRequest) commandData).getSwitchId();
            } else if (commandData instanceof PortConfigurationRequest) {
                return ((PortConfigurationRequest) commandData).getSwitchId();
            } else if (commandData instanceof DumpSwitchPortsDescriptionRequest) {
                return ((DumpSwitchPortsDescriptionRequest) commandData).getSwitchId();
            } else if (commandData instanceof DumpPortDescriptionRequest) {
                return ((DumpPortDescriptionRequest) commandData).getSwitchId();
            } else if (commandData instanceof DumpMetersRequest) {
                return ((DumpMetersRequest) commandData).getSwitchId();
            } else if (commandData instanceof ValidateRulesRequest) {
                return ((ValidateRulesRequest) commandData).getSwitchId();
            } else if (commandData instanceof MeterModifyCommandRequest) {
                return ((MeterModifyCommandRequest) commandData).getFwdSwitchId();
            }
        }
        return null;
    }
}
