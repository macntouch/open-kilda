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

package org.openkilda.wfm.topology.flowhs;

import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.ROUTER_TO_FLOW_CREATE_HUB;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.ROUTER_TO_FLOW_REROUTE_HUB;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.SPEAKER_WORKER_TO_HUB_CREATE;

import org.openkilda.messaging.AbstractMessage;
import org.openkilda.messaging.Message;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.share.hubandspoke.CoordinatorBolt;
import org.openkilda.wfm.share.hubandspoke.CoordinatorSpout;
import org.openkilda.wfm.share.hubandspoke.WorkerBolt.Config;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.flowhs.bolts.FlowCreateHubBolt;
import org.openkilda.wfm.topology.flowhs.bolts.RouterBolt;
import org.openkilda.wfm.topology.flowhs.bolts.SpeakerWorkerBolt;

import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.topology.TopologyBuilder;

public class FlowHsTopology extends AbstractTopology<FlowHsTopologyConfig> {

    public FlowHsTopology(LaunchEnvironment env) {
        super(env, FlowHsTopologyConfig.class);
    }

    @Override
    public StormTopology createTopology() {
        TopologyBuilder tb = new TopologyBuilder();

        inputSpout(tb);
        inputRouter(tb);

        flowCreateHub(tb);
        flowRerouteHub(tb);

        speakerSpout(tb);
        speakerRouter(tb);
        speakerWorker(tb);
        speakerOutput(tb);

        coordinator(tb);

        northboundOutput(tb);

        return tb.createTopology();
    }

    private void inputSpout(TopologyBuilder topologyBuilder) {
        KafkaSpout<String, Message> mainSpout = buildKafkaSpout(getConfig().getKafkaFlowHsTopic(),
                ComponentId.FLOW_SPOUT.name());
        topologyBuilder.setSpout(ComponentId.FLOW_SPOUT.name(), mainSpout);
    }

    private void inputRouter(TopologyBuilder topologyBuilder) {
        topologyBuilder.setBolt(ComponentId.FLOW_ROUTER_BOLT.name(), new RouterBolt())
                .shuffleGrouping(ComponentId.FLOW_SPOUT.name());
    }

    private void flowCreateHub(TopologyBuilder topologyBuilder) {
        topologyBuilder.setBolt(ComponentId.FLOW_CREATE_HUB.name(),
                new FlowCreateHubBolt(ComponentId.FLOW_ROUTER_BOLT.name(), 500, true))
                .fieldsGrouping(ComponentId.FLOW_ROUTER_BOLT.name(), ROUTER_TO_FLOW_CREATE_HUB.name(), FIELDS_KEY)
                .directGrouping(SpeakerWorkerBolt.ID, Stream.SPEAKER_WORKER_TO_HUB_CREATE.name())
                .directGrouping(CoordinatorBolt.ID);
    }

    private void flowRerouteHub(TopologyBuilder topologyBuilder) {
        topologyBuilder.setBolt(ComponentId.FLOW_REROUTE_HUB.name(),
                new FlowCreateHubBolt(ComponentId.FLOW_ROUTER_BOLT.name(), 500, true))
                .fieldsGrouping(ComponentId.FLOW_ROUTER_BOLT.name(), ROUTER_TO_FLOW_REROUTE_HUB.name(), FIELDS_KEY)
                .directGrouping(SpeakerWorkerBolt.ID, Stream.SPEAKER_WORKER_TO_HUB_REROUTE.name())
                .directGrouping(CoordinatorBolt.ID);
    }

    private void speakerSpout(TopologyBuilder topologyBuilder) {
        KafkaSpout<String, AbstractMessage> flWorkerSpout = buildKafkaSpoutForAbstractMessage(
                getConfig().getKafkaFlowSpeakerWorkerTopic(),
                ComponentId.SPEAKER_WORKER_SPOUT.name());
        topologyBuilder.setSpout(ComponentId.SPEAKER_WORKER_SPOUT.name(), flWorkerSpout);
    }

    private void speakerRouter(TopologyBuilder topologyBuilder) {
        topologyBuilder.setBolt(ComponentId.SPEAKER_ROUTER_BOLT, new SpeakerRouter())
                .shuffleGrouping(ComponentId.SPEAKER_WORKER_SPOUT.toString());
    }

    private void speakerWorker(TopologyBuilder topologyBuilder) {
        SpeakerWorkerBolt speakerWorker = new SpeakerWorkerBolt(Config.builder()
                .autoAck(true)
                .defaultTimeout(100)
                .workerSpoutComponent(ComponentId.SPEAKER_ROUTER_BOLT.name())
                .hubComponent(ComponentId.FLOW_CREATE_HUB.name())
                .streamToHub(SPEAKER_WORKER_TO_HUB_CREATE.name())
                .build());
        topologyBuilder.setBolt(SpeakerWorkerBolt.ID, speakerWorker)
                .fieldsGrouping(ComponentId.SPEAKER_WORKER_SPOUT.name(), FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_CREATE_HUB.name(), Stream.HUB_TO_SPEAKER_WORKER.name(),
                        FIELDS_KEY)
                .directGrouping(CoordinatorBolt.ID);
    }

    private void speakerOutput(TopologyBuilder topologyBuilder) {
        KafkaBolt flKafkaBolt = buildKafkaBolt(getConfig().getKafkaSpeakerFlowTopic());
        topologyBuilder.setBolt(ComponentId.SPEAKER_REQUEST_SENDER.name(), flKafkaBolt)
                .shuffleGrouping(SpeakerWorkerBolt.ID, Stream.SPEAKER_WORKER_REQUEST_SENDER.name());
    }

    private void coordinator(TopologyBuilder topologyBuilder) {
        topologyBuilder.setSpout(CoordinatorSpout.ID, new CoordinatorSpout());
        topologyBuilder.setBolt(CoordinatorBolt.ID, new CoordinatorBolt())
                .allGrouping(CoordinatorSpout.ID)
                .fieldsGrouping(SpeakerWorkerBolt.ID, CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_CREATE_HUB.name(), CoordinatorBolt.INCOME_STREAM, FIELDS_KEY);
    }

    private void northboundOutput(TopologyBuilder topologyBuilder) {
        KafkaBolt nbKafkaBolt = buildKafkaBolt(getConfig().getKafkaNorthboundTopic());
        topologyBuilder.setBolt(ComponentId.NB_RESPONSE_SENDER.name(), nbKafkaBolt)
                .shuffleGrouping(ComponentId.FLOW_CREATE_HUB.name(), Stream.HUB_TO_NB_RESPONSE_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_REROUTE_HUB.name(), Stream.HUB_TO_NB_RESPONSE_SENDER.name());
    }

    public enum ComponentId {
        FLOW_SPOUT("flow.spout"),
        SPEAKER_WORKER_SPOUT("fl.worker.spout"),
        SPEAKER_ROUTER_BOLT("fl.router.bolt"),

        FLOW_ROUTER_BOLT("flow.router.bolt"),
        FLOW_CREATE_HUB("flow.create.hub.bolt"),
        FLOW_REROUTE_HUB("flow.reroute.hub.bolt"),

        NB_RESPONSE_SENDER("nb.kafka.bolt"),
        SPEAKER_REQUEST_SENDER("speaker.kafka.bolt");

        private final String value;

        ComponentId(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }

    }

    public enum Stream {
        ROUTER_TO_FLOW_CREATE_HUB,
        ROUTER_TO_FLOW_REROUTE_HUB,

        HUB_TO_SPEAKER_WORKER,

        SPEAKER_WORKER_TO_HUB_CREATE,
        SPEAKER_WORKER_TO_HUB_REROUTE,

        SPEAKER_WORKER_REQUEST_SENDER,
        HUB_TO_NB_RESPONSE_SENDER
    }

    /**
     * Launches and sets up the topology.
     *
     * @param args the command-line arguments.
     */
    public static void main(String[] args) {
        try {
            LaunchEnvironment env = new LaunchEnvironment(args);
            new FlowHsTopology(env).setup();
        } catch (Exception e) {
            System.exit(handleLaunchException(e));
        }
    }
}
