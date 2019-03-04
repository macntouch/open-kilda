package org.openkilda.functionaltests.spec.grpc

import static org.openkilda.testing.ConstantsGrpc.DEFAULT_LOG_MESSAGES_STATE
import static org.openkilda.testing.ConstantsGrpc.DEFAULT_LOG_OF_MESSAGES_STATE
import static org.openkilda.testing.ConstantsGrpc.REMOTE_LOG_IP
import static org.openkilda.testing.ConstantsGrpc.REMOTE_LOG_PORT

import org.openkilda.functionaltests.BaseSpecification
import org.openkilda.grpc.speaker.model.LogMessagesDto
import org.openkilda.grpc.speaker.model.LogOferrorsDto
import org.openkilda.grpc.speaker.model.RemoteLogServerDto
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.model.grpc.OnOffState

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative
import spock.lang.Shared
import spock.lang.Unroll

@Slf4j
@Narrative("""This test suite checks that we are able to enable/disable:
 - log messages;
 - OF log messages.
 And checks that we are able to do the CRUD actions with the remote log server configuration.""")
class LogSpec extends BaseSpecification {
    @Value('${grpc.remote.log.server.ip}')
    String defaultRemoteLogServerIp
    @Value('${grpc.remote.log.server.port}')
    Integer defaultRemoteLogServerPort

    @Shared
    String switchIp

    def setUpOnce() {
        requireProfiles("hardware")
        def nFlowSwitch = northbound.activeSwitches.find { it.description =~ /NW[0-9]+.[0-9].[0-9]/ }
        def pattern = /(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\-){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)/
        switchIp = (nFlowSwitch.address =~ pattern)[0].replaceAll("-", ".")
    }

    def "Able to enable log messages"() {
        when: "Try to apply a ON state"
        def r1 = grpc.enableLogMessagesOnSwitch(switchIp, new LogMessagesDto(OnOffState.ON))

        then: "The ON state is applied"
        r1.enabled == OnOffState.ON

        cleanup:
        "Restore default($DEFAULT_LOG_MESSAGES_STATE) state"
        def r2 = grpc.enableLogMessagesOnSwitch(switchIp, new LogMessagesDto(DEFAULT_LOG_MESSAGES_STATE))
        r2.enabled == OnOffState.OFF
    }

    def "Able to enable OF log messages"() {
        when: "Try to apply a ON state"
        def r1 = grpc.enableLogOfErrorsOnSwitch(switchIp, new LogOferrorsDto(OnOffState.ON))

        then: "The ON state is applied"
        r1.enabled == OnOffState.ON

        cleanup:
        "Restore default($DEFAULT_LOG_OF_MESSAGES_STATE) state"
        def r2 = grpc.enableLogOfErrorsOnSwitch(switchIp, new LogOferrorsDto(DEFAULT_LOG_OF_MESSAGES_STATE))
        r2.enabled == OnOffState.OFF
    }

    def "Able to manipulate(CRUD) with a remote log server"() {
        when: "Remove current remote log server configuration"
        def r = grpc.deleteRemoteLogServerForSwitch(switchIp)

        then: "Current remote log server configuration is deleted"
        r.deleted

        def g = grpc.getRemoteLogServerForSwitch(switchIp)
        g.ipAddress == ""
        g.port == 0

        when: "Try to set custom remote log server"
        grpc.setRemoteLogServerForSwitch(switchIp, new RemoteLogServerDto(REMOTE_LOG_IP, REMOTE_LOG_PORT))

        then: "New custom remote log server configuration is set"
        def response = grpc.getRemoteLogServerForSwitch(switchIp)
        response.ipAddress == REMOTE_LOG_IP
        response.port == REMOTE_LOG_PORT

        cleanup: "Restore original configuration"
        grpc.setRemoteLogServerForSwitch(switchIp,
                new RemoteLogServerDto(defaultRemoteLogServerIp, defaultRemoteLogServerPort))
        def g1 = grpc.getRemoteLogServerForSwitch(switchIp)
        g1.ipAddress == defaultRemoteLogServerIp
        g1.port == defaultRemoteLogServerPort
    }

    @Unroll
    def "Not able to set incorrect remote log server configuration(ip/port): #remoteIp/#remotePort"() {
        when: "Try to set incorrect configuration"
        grpc.setRemoteLogServerForSwitch(switchIp, new RemoteLogServerDto(remoteIp, remotePort))

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        exc.responseBodyAsString.to(MessageError).errorMessage == errorMessage

        where:
        remoteIp      | remotePort      | errorMessage
        "1.1.1.1111"  | REMOTE_LOG_PORT | "Invalid IPv4 address."
        REMOTE_LOG_IP | 65537           | "Invalid remotelogserver port."
    }
}
