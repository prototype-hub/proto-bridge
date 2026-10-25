package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewChallengeEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewGuestEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewInvitationEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewSessionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewUnverifiedGuestEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys;
import com.gaiys.proto.prototypehub.web.request.ReviewChallengeRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewGuestEnabledRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewGuestQueryRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewUnverifiedRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewVerifyRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewGuestResponse;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class PrototypeReviewBoundaryTest {
    private static final String COOKIE = PrototypeReviewKeys.random();

    private static PrototypeShareEntity share() {
        var s = new PrototypeShareEntity();
        s.setId("s");
        s.setProjectId("p");
        s.setVersionId("v");
        s.setAccessKey("generation");
        s.setConfigVersion(3L);
        return s;
    }

    private static PrototypeReviewChallengeEntity challenge() {
        var c = new PrototypeReviewChallengeEntity();
        c.setId("c");
        c.setShareId("s");
        c.setProjectId("p");
        c.setVersionId("v");
        c.setRevisionId("r");
        c.setConfigVersion(3L);
        c.setShareGeneration(PrototypeReviewKeys.digest("generation"));
        c.setCookieHash(PrototypeReviewKeys.digest(COOKIE));
        c.setDelivered(true);
        c.setConsumed(false);
        c.setAttempts(0);
        c.setExpiresAt(2000L);
        return c;
    }

    @Test
    void invitationStatusHonorsLegacyGuestVersionExpiryAndTerminalHistory() {
        var share = new PrototypeShareEntity();
        share.setId("s");
        share.setProjectId("p");
        share.setVersionId("v");
        share.setRevisionId("r");
        share.setEnabled(true);
        share.setReviewEnabled(true);
        share.setAccessKey("generation");
        var row = new PrototypeReviewInvitationEntity();
        row.setShareId("s");
        row.setProjectId("p");
        row.setVersionId("v");
        row.setRevisionId("r");
        row.setShareConfigVersion(0L);
        row.setGuestConfigVersion(0L);
        row.setExpiresAt(100L);
        row.setShareGeneration(PrototypeReviewKeys.digest("generation"));
        var guest = new PrototypeReviewGuestEntity();
        assertEquals("pending", PrototypeReviewInvitationService.status(row, share, guest, 99));
        assertEquals("expired", PrototypeReviewInvitationService.status(row, share, guest, 100));
        guest.setEnabled(false);
        assertEquals("invalidated", PrototypeReviewInvitationService.status(row, share, guest, 99));
        guest.setEnabled(true);
        guest.setConfigVersion(2L);
        assertEquals("invalidated", PrototypeReviewInvitationService.status(row, share, guest, 99));
        guest.setConfigVersion(0L);
        share.setConfigVersion(1L);
        assertEquals("invalidated", PrototypeReviewInvitationService.status(row, share, guest, 99));
        share.setConfigVersion(0L);
        share.setAccessKey("new-generation");
        assertEquals("invalidated", PrototypeReviewInvitationService.status(row, share, guest, 99));
        row.setConsumedAt(50L);
        assertEquals("consumed", PrototypeReviewInvitationService.status(row, share, guest, 101));
        row.setConsumedAt(null);
        row.setRevoked(true);
        assertEquals("revoked", PrototypeReviewInvitationService.status(row, share, guest, 101));
    }

    @Test
    void onlyDeliveredUnspentUnexpiredAndCorrectlyBoundOtpCanVerify() {
        var c = challenge();
        var s = share();
        assertTrue(PrototypeReviewService.validChallenge(c, s, "r", COOKIE, 1000));
        c.setDelivered(false);
        assertFalse(PrototypeReviewService.validChallenge(c, s, "r", COOKIE, 1000));
        c.setDelivered(true);
        c.setConsumed(true);
        assertFalse(PrototypeReviewService.validChallenge(c, s, "r", COOKIE, 1000));
        c.setConsumed(false);
        c.setAttempts(5);
        assertFalse(PrototypeReviewService.validChallenge(c, s, "r", COOKIE, 1000));
        c.setAttempts(4);
        assertTrue(PrototypeReviewService.validChallenge(c, s, "r", COOKIE, 1000));
        assertFalse(PrototypeReviewService.validChallenge(c, s, "r", COOKIE, 2000));
        assertFalse(PrototypeReviewService.validChallenge(c, s, "other", COOKIE, 1000));
        assertFalse(
                PrototypeReviewService.validChallenge(
                        c, s, "r", PrototypeReviewKeys.random(), 1000));
        s.setConfigVersion(4L);
        assertFalse(PrototypeReviewService.validChallenge(c, s, "r", COOKIE, 1000));
        s.setConfigVersion(3L);
        s.setAccessKey("rotated");
        assertFalse(PrototypeReviewService.validChallenge(c, s, "r", COOKIE, 1000));
    }

    @Test
    void sessionCannotMoveToAnotherShareProjectVersionRevisionOrGeneration() {
        var s = share();
        var session = new PrototypeReviewSessionEntity();
        session.setShareId("s");
        session.setProjectId("p");
        session.setVersionId("v");
        session.setRevisionId("r");
        session.setConfigVersion(3L);
        session.setShareGeneration(PrototypeReviewKeys.digest("generation"));
        session.setExpiresAt(2000L);
        assertTrue(PrototypeReviewService.validSession(session, s, "r", 1000));
        assertFalse(PrototypeReviewService.validSession(session, s, "r", 2000));
        assertFalse(PrototypeReviewService.validSession(session, s, "r2", 1000));
        s.setId("other");
        assertFalse(PrototypeReviewService.validSession(session, s, "r", 1000));
        s.setId("s");
        s.setProjectId("other");
        assertFalse(PrototypeReviewService.validSession(session, s, "r", 1000));
        s.setProjectId("p");
        s.setVersionId("other");
        assertFalse(PrototypeReviewService.validSession(session, s, "r", 1000));
    }

    @Test
    void trimmedInputsValidateWithoutAcceptingUnicodeOtpOrBlankIdentity() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var v = factory.getValidator();
            var valid =
                    new ReviewChallengeRequest(
                            "r", "key", " a@example.com ", " Name ", " Company ");
            assertTrue(v.validate(valid).isEmpty());
            // 保留原输入供边界校验，不能先strip而吞掉首尾控制字符。
            assertEquals(" Name ", valid.displayName());
            assertEquals(" Company ", valid.company());
            assertFalse(
                    v.validate(
                                    new ReviewChallengeRequest(
                                            "r", "k", "a@example.com", "Name\n", null))
                            .isEmpty());
            assertFalse(
                    v.validate(new ReviewUnverifiedRequest("r", "k", "Name", "Company\u0085"))
                            .isEmpty());
            assertTrue(v.validate(new ReviewUnverifiedRequest("r", "k", "Name", null)).isEmpty());
            assertFalse(v.validate(new ReviewUnverifiedRequest("r", "k", " ", null)).isEmpty());
            for (String blank : java.util.List.of("\u2003", "\u3000")) {
                assertFalse(
                        v.validate(new ReviewUnverifiedRequest("r", "k", blank, null)).isEmpty());
                assertFalse(
                        v.validate(
                                        new ReviewChallengeRequest(
                                                "r", "k", "a@example.invalid", blank, null))
                                .isEmpty());
            }
            assertFalse(
                    v.validate(new ReviewChallengeRequest("r", "k", "bad", " ", "x".repeat(201)))
                            .isEmpty());
            assertTrue(v.validate(new ReviewVerifyRequest("r", "k", "id", "123456")).isEmpty());
            assertFalse(v.validate(new ReviewVerifyRequest("r", "k", "id", "１２３４５６")).isEmpty());
        }
    }

    @Test
    void databaseEntitiesDoNotSerializePrivateEmailNameCookieOrHash() throws Exception {
        var mapper = new ObjectMapper();
        var c = challenge();
        c.setEmail("secret@example.com");
        c.setDisplayName("Private Name");
        c.setCompany("Private Co");
        c.setCodeHash("private-code-hash");
        var json = mapper.writeValueAsString(c);
        for (String secret :
                new String[] {
                    "secret@example.com",
                    "Private Name",
                    "Private Co",
                    "private-code-hash",
                    PrototypeReviewKeys.digest(COOKIE),
                    PrototypeReviewKeys.digest("generation")
                }) assertFalse(json.contains(secret));
        var guest = new PrototypeReviewGuestEntity();
        guest.setEmail("secret@example.com");
        guest.setDisplayName("Private Name");
        assertFalse(mapper.writeValueAsString(guest).contains("secret@example.com"));
    }

    @Test
    void managementViewKeepsHistoricalGuestActiveButNeverExposesFullEmailOrProject()
            throws Exception {
        var guest = new PrototypeReviewGuestEntity();
        guest.setId("guest");
        guest.setProjectId("private-project");
        guest.setEmail("visitor@example.invalid");
        guest.setDisplayName("Self-declared name");
        var response = ReviewGuestResponse.from(guest);
        assertTrue(response.enabled());
        assertEquals(0, response.configVersion());
        assertNull(response.company());
        assertEquals("email", response.verificationLevel());
        String json = new ObjectMapper().writeValueAsString(response);
        assertFalse(json.contains("visitor@example.invalid"));
        assertFalse(json.contains("private-project"));
        assertFalse(json.contains("\"email\":"));
        guest.setEnabled(false);
        guest.setConfigVersion(2L);
        assertFalse(ReviewGuestResponse.from(guest).enabled());
        assertEquals(2, ReviewGuestResponse.from(guest).configVersion());
    }

    @Test
    void managementInputsHaveBoundedPagesAndRequireExplicitStateAndVersion() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var request = new ReviewGuestQueryRequest();
            assertTrue(validator.validate(request).isEmpty());
            request.setPageIndex(10001);
            request.setPageSize(101);
            assertEquals(2, validator.validate(request).size());
            request.setPageIndex(-1);
            request.setPageSize(0);
            assertEquals(2, validator.validate(request).size());
            assertEquals(2, validator.validate(new ReviewGuestEnabledRequest(null, null)).size());
            assertFalse(validator.validate(new ReviewGuestEnabledRequest(false, -1L)).isEmpty());
            assertTrue(validator.validate(new ReviewGuestEnabledRequest(false, 0L)).isEmpty());
        }
    }

    @Test
    void onlyKnownSessionLevelsUseTheirExplicitPolicyAndLegacyNullRemainsEmail() {
        var share = share();
        var session = new PrototypeReviewSessionEntity();
        session.setShareId("s");
        session.setProjectId("p");
        session.setVersionId("v");
        session.setRevisionId("r");
        session.setConfigVersion(3L);
        session.setShareGeneration(PrototypeReviewKeys.digest("generation"));
        session.setExpiresAt(2000L);
        assertTrue(PrototypeReviewService.validSession(session, share, "r", 1000));
        session.setVerificationLevel("unverified");
        assertFalse(PrototypeReviewService.validSession(session, share, "r", 1000));
        share.setReviewAllowUnverified(true);
        assertTrue(PrototypeReviewService.validSession(session, share, "r", 1000));
        session.setVerificationLevel("admin");
        assertFalse(PrototypeReviewService.validSession(session, share, "r", 1000));
        session.setVerificationLevel("email");
        assertTrue(PrototypeReviewService.validSession(session, share, "r", 1000));
    }

    @Test
    void unverifiedManagementResponseHasNoEmailAndCarriesFixedExpiry() throws Exception {
        var guest = new PrototypeReviewUnverifiedGuestEntity();
        guest.setId("random-id");
        guest.setProjectId("private-project");
        guest.setDisplayName("Self-reported");
        guest.setEnabled(true);
        guest.setConfigVersion(0L);
        guest.setExpiresAt(12345L);
        var response = ReviewGuestResponse.from(guest);
        assertEquals("unverified", response.verificationLevel());
        assertNull(response.maskedEmail());
        assertEquals(12345L, response.expiresAt());
        assertFalse(new ObjectMapper().writeValueAsString(guest).contains("Self-reported"));
        assertFalse(new ObjectMapper().writeValueAsString(response).contains("private-project"));
    }
}
