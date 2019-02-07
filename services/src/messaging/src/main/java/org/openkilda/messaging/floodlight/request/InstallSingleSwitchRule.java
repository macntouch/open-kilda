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

package org.openkilda.messaging.floodlight.request;

import static org.openkilda.messaging.Utils.FLOW_ID;

import org.openkilda.messaging.CommandContext;
import org.openkilda.model.OutputVlanType;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

@Value
public class InstallSingleSwitchRule extends InstallMeteredRule {
    /**
     * Output action on the vlan tag.
     */
    @JsonProperty("output_vlan_type")
    private OutputVlanType outputVlanType;

    /**
     * Optional input vlan id value.
     */
    @JsonProperty("input_vlan_id")
    private Integer inputVlanId;

    /**
     * Optional output vlan id value.
     */
    @JsonProperty("output_vlan_id")
    private Integer outputVlanId;

    @JsonCreator
    @Builder
    public InstallSingleSwitchRule(@JsonProperty("command-context") CommandContext commandContext,
                                   @JsonProperty(FLOW_ID) final String id,
                                   @JsonProperty("cookie") final Long cookie,
                                   @JsonProperty("switch_id") final SwitchId switchId,
                                   @JsonProperty("input_port") final Integer inputPort,
                                   @JsonProperty("output_port") final Integer outputPort,
                                   @JsonProperty("bandwidth") final Long bandwidth,
                                   @JsonProperty("meter_id") final Long meterId,
                                   @JsonProperty("output_vlan_type") final OutputVlanType outputVlanType,
                                   @JsonProperty("input_vlan_id") final Integer inputVlanId,
                                   @JsonProperty("output_vlan_id") final Integer outputVlanId) {
        super(commandContext, id, cookie, switchId, inputPort, outputPort, meterId, bandwidth);

        this.outputVlanType = outputVlanType;
        this.inputVlanId = inputVlanId;
        this.outputVlanId = outputVlanId;
    }
}
