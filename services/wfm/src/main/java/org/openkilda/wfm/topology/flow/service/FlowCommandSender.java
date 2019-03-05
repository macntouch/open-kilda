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

package org.openkilda.wfm.topology.flow.service;

import org.openkilda.wfm.topology.flow.model.FlowHolder;
import org.openkilda.wfm.topology.flow.model.UpdatedFlow;

public interface FlowCommandSender {
    /**
     * Constructs and sends the install rule commands for the given flow.
     */
    void sendInstallRulesCommand(FlowHolder flow);

    /**
     * Constructs and sends the update (or install + delete) rule commands for the given flow pair.
     */
    void sendUpdateRulesCommand(UpdatedFlow flowPair);

    /**
     * Constructs and sends the remove rule commands for the given flow.
     */
    void sendRemoveRulesCommand(FlowHolder flow);

    /**
     * Constructs and sends commands for swapping ingress to protected flow path.
     */
    void sendSwapIngressCommand(FlowHolder flow);
}
