package com.gaiys.proto.prototypehub.service.keys;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeInstallationEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 证明公开运行凭据不能签发事件，票据严格绑定分享、修订、配置和有效期。 */
class PrototypeVisitTicketsTest {
    private final String secret = UUID.randomUUID().toString();
    private final long now = 1793404800000L;

    private PrototypeShareEntity share() {
        var s = new PrototypeShareEntity();
        s.setId("share");
        s.setVersionId("version");
        s.setAccessKey("private-share-secret");
        return s;
    }

    @Test
    void exactLifetimeAndFutureTimesAreRejected() {
        var s = share();
        var ticket = PrototypeVisitTickets.issue(secret, s, "R1", now);
        assertNotNull(PrototypeVisitTickets.verify(secret, s, "R1", ticket, now));
        assertNotNull(
                PrototypeVisitTickets.verify(
                        secret, s, "R1", ticket, now + PrototypeVisitTickets.TTL - 1));
        assertThrows(
                PrototypeHubException.class,
                () -> PrototypeVisitTickets.verify(secret, s, "R1", ticket, now - 1));
        assertThrows(
                PrototypeHubException.class,
                () ->
                        PrototypeVisitTickets.verify(
                                secret, s, "R1", ticket, now + PrototypeVisitTickets.TTL));
    }

    @Test
    void rawAndDerivedRuntimeCredentialsCannotForgeTickets() {
        var s = share();
        for (String known : new String[] {s.getAccessKey(), PrototypeShareKeys.derive(s, "R1")}) {
            var forged = PrototypeVisitTickets.issue(known, s, "R1", now);
            assertThrows(
                    PrototypeHubException.class,
                    () -> PrototypeVisitTickets.verify(secret, s, "R1", forged, now));
        }
    }

    @Test
    void shareVersionRevisionAndRotatedConfigurationCannotReuseTicket() {
        var s = share();
        var ticket = PrototypeVisitTickets.issue(secret, s, "R1", now);
        assertThrows(
                PrototypeHubException.class,
                () -> PrototypeVisitTickets.verify(secret, s, "R2", ticket, now));
        s.setId("other");
        assertThrows(
                PrototypeHubException.class,
                () -> PrototypeVisitTickets.verify(secret, s, "R1", ticket, now));
        s.setId("share");
        s.setVersionId("other");
        assertThrows(
                PrototypeHubException.class,
                () -> PrototypeVisitTickets.verify(secret, s, "R1", ticket, now));
        s.setVersionId("version");
        s.setAccessKey("rotated");
        assertThrows(
                PrototypeHubException.class,
                () -> PrototypeVisitTickets.verify(secret, s, "R1", ticket, now));
    }

    @Test
    void installationSigningKeyIsNeverSerialized() throws Exception {
        var installation = new PrototypeInstallationEntity();
        installation.setStatisticsSigningKey(secret);
        installation.setStatisticsStartedAt(now);
        String json = new ObjectMapper().writeValueAsString(installation);
        assertFalse(json.contains(secret));
        assertFalse(json.contains("statisticsSigningKey"));
    }
}
