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

package org.openkilda.wfm.topology.flowhs.service;

import org.openkilda.floodlight.flow.request.FlowRequest;
import org.openkilda.floodlight.flow.response.FlowResponse;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.topology.flowhs.model.FlowCommands;
import org.openkilda.wfm.topology.flowhs.model.FlowResponses;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class SpeakerWorkerService {
    private final Map<String, Set<String>> commandsToKey = new HashMap<>();
    private final Map<String, List<FlowResponse>> responses = new HashMap<>();

    /**
     * Send batch of commands.
     * @param flowCommands list of commands to be sent.
     */
    public void sendCommands(String key, FlowCommands flowCommands, SpeakerCommandCarrier carrier)
            throws PipelineException {
        Set<String> commands = new HashSet<>(flowCommands.getCommands().size());
        for (FlowRequest command : flowCommands.getCommands()) {
            log.warn("New command for switch {}", command.getSwitchId());
            commands.add(command.getCommandId());
            carrier.sendCommand(key, command);
        }

        commandsToKey.put(key, commands);
    }

    /**
     * Process received response. If it is the last in the batch - then send response to the hub.
     * @param key operation's key.
     * @param response response payload.
     */
    public void handleResponse(String key, FlowResponse response, SpeakerCommandCarrier carrier)
            throws PipelineException {
        log.warn("Received response with key {} for switch {}", key, response.getSwitchId());

        if (!commandsToKey.containsKey(key)) {
            log.warn("Received response for non pending request. Payload: {}", response);
        }

        Set<String> pendingRequests = commandsToKey.get(key);
        if (pendingRequests == null) {
            log.warn("Received response for non pending request. Payload: {}", response);
        } else {
            pendingRequests.remove(response.getCommandId());
            responses.computeIfAbsent(key, function -> new ArrayList<>())
                    .add(response);

            if (pendingRequests.isEmpty()) {
                carrier.sendResponse(key, new FlowResponses(responses.remove(key)));
            }
        }

    }

    /**
     * Handles operation timeout.
     * @param key operation identifier.
     */
    public void handleTimeout(String key, SpeakerCommandCarrier carrier) throws PipelineException {
        commandsToKey.remove(key);
        responses.remove(key);

        carrier.sendResponse(key, new FlowResponses(responses.remove(key)));
    }
}
