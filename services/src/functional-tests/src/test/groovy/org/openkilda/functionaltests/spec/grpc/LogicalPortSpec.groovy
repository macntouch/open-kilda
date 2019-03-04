package org.openkilda.functionaltests.spec.grpc


import org.openkilda.functionaltests.BaseSpecification
import org.openkilda.grpc.speaker.model.LogicalPortDto
import org.openkilda.messaging.error.MessageError

import groovy.util.logging.Slf4j
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative
import spock.lang.Shared
import spock.lang.Unroll

@Slf4j
@Narrative("""This test suit checks the CRUD actions on a logical port.
Logical ports are defined by associating a single physical port to them to define
Bidirectional Forwarding Detection(BFD) ports or
by associating a list of physical ports to them to createLink Aggregation Groups (LAG) or
a list of BFD ports to them to create a LAG for fast-failover for BFD sessions.

NOTE: The GRPC implementation supports the LAG type only and it is set by default.""")
class LogicalPortSpec extends BaseSpecification {
    @Shared
    String switchIp
    @Shared
    Integer switchPort
    @Shared
    Integer switchLogicalPort

    def setUpOnce() {
        requireProfiles("hardware")
        def nFlowSwitch = northbound.activeSwitches.find { it.description =~ /NW[0-9]+.[0-9].[0-9]/ }
        def pattern = /(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\-){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)/
        switchIp = (nFlowSwitch.address =~ pattern)[0].replaceAll("-", ".")
        switchPort = northbound.getPorts(nFlowSwitch.switchId) find { it.state == ["LINK_DOWN"] }
        switchLogicalPort = (10.toString() + switchPort.toString()).toInteger()
    }

    def "Able to create/read/delete logicalport"() {
        /**the update action is not working(issue on a Noviflow switch side)*/
        when: "Create logical port"
        grpc.createLogicalPort(switchIp, new LogicalPortDto([switchPort], switchLogicalPort))

        then: "Able to get the created logical port"
        def r = grpc.getSwitchLogicalPortConfig(switchIp, switchLogicalPort)
        r.logicalPortNumber == switchLogicalPort
        r.name == "novi_lport" + switchLogicalPort.toString()
        r.portNumbers[0] == switchPort

        and: "The created port is existed in a list of all logical port"
        grpc.getSwitchLogicalPorts(switchIp).contains(r)

        //        TODO(andriidovhan): add update action
        //        and: "able to edit the created logical port"
        when: "Try to delete the created logical port"
        def r1 = grpc.deleteSwitchLogicalPort(switchIp, switchLogicalPort)

        then: "Logical port is deleted"
        r1.deleted

        when: "Try to get the deleted logical port"
        grpc.getSwitchLogicalPortConfig(switchIp, switchLogicalPort)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.NOT_FOUND
        exc.responseBodyAsString.to(MessageError).errorMessage == "Provided logical port does not exist."
        Boolean testIsCompleted = true

        cleanup: "Remove created port"
        if (!testIsCompleted) {
            grpc.deleteSwitchLogicalPort(switchIp, switchLogicalPort)
        }
    }

    @Unroll
    def "Not able to create logical port with incorrect port number(lPort/sPort): #logicalPortNumber/#portNumber"() {
        when:
        "Try to create logical port: $logicalPortNumber/$portNumber"
        grpc.createLogicalPort(switchIp, new LogicalPortDto([portNumber], logicalPortNumber))

        then: "Human readable error is returned."
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        exc.responseBodyAsString.to(MessageError).errorMessage == errorMessage

        where:
        logicalPortNumber | portNumber | errorMessage
        99                | switchPort | "Valid logicalportno range is 100 to 63487."
        63488             | switchPort | "Valid logicalportno range is 100 to 63487."
        switchLogicalPort | 44444      | "Invalid portno value."
    }

    def "Not able to delete non-existent logical port number"() {
        when: "Try to delete incorrect logicalPortNumber"
        //        TODO(andriidovhan) add check that fakeNumber is not exist on a switch
        Integer fakeNumber = 11114
        grpc.deleteSwitchLogicalPort(switchIp, fakeNumber)

        then: "Human readable error is returned."
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.NOT_FOUND
        exc.responseBodyAsString.to(MessageError).errorMessage == "Provided logical port does not exist."
    }
}
