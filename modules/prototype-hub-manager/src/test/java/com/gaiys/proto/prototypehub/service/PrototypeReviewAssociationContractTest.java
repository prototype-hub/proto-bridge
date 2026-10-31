package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewAssociationEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewGuestEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewThreadEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys;
import com.gaiys.proto.prototypehub.web.request.ReviewAssociationCreateRequest;
import org.junit.jupiter.api.Test;

/** 已发布线程JSON兼容及授权代次契约；实际DB谓词/并发/权限由隔离运行测试覆盖。 */
class PrototypeReviewAssociationContractTest {
    @Test
    void disabledAndReenabledTargetCannotReviveCapturedGrant() {
        var snapshot = snapshot();
        assertNull(PrototypeReviewAssociationService.invalidReason(snapshot, 1));
        snapshot.getGuest().setEnabled(false);
        snapshot.getGuest().setConfigVersion(1L);
        assertEquals(
                "TARGET_UNAVAILABLE", PrototypeReviewAssociationService.invalidReason(snapshot, 1));
        snapshot.getGuest().setEnabled(true);
        snapshot.getGuest().setConfigVersion(2L);
        assertEquals(
                "TARGET_CHANGED", PrototypeReviewAssociationService.invalidReason(snapshot, 1));
    }

    @Test
    void rotationInvalidatesEvenIfShareVersionWasNotChanged() {
        var snapshot = snapshot();
        snapshot.getShare().setAccessKey("rotated");
        assertEquals("SHARE_CHANGED", PrototypeReviewAssociationService.invalidReason(snapshot, 1));
        snapshot.getShare().setAccessKey("original");
        snapshot.getShare().setConfigVersion(1L);
        assertEquals("SHARE_CHANGED", PrototypeReviewAssociationService.invalidReason(snapshot, 1));
    }

    @Test
    void legacyNullVersionsMeanZeroAndRevisionDeletionDenies() {
        var snapshot = snapshot();
        snapshot.getShare().setConfigVersion(null);
        snapshot.getGuest().setConfigVersion(null);
        assertNull(PrototypeReviewAssociationService.invalidReason(snapshot, 1));
        snapshot.getVersion().setDeletedTime(1L);
        assertEquals(
                "REVISION_UNAVAILABLE",
                PrototypeReviewAssociationService.invalidReason(snapshot, 1));
    }

    @Test
    void revokedAuditSurvivesMissingCurrentShareAndTarget() {
        var snapshot = snapshot();
        snapshot.getAssociation().setRevoked(true);
        snapshot.setShare(null);
        snapshot.setGuest(null);
        var response = PrototypeReviewAssociationService.response(snapshot);
        assertEquals("revoked", response.status());
        assertNull(response.invalidReason());
        assertEquals("offline-123", response.evidenceReference());
        assertEquals("operator", response.createdBy());
    }

    @Test
    void memberProjectionOmitsOnlyNewFieldsAndKeepsHistoricalNullableFields() throws Exception {
        var row = new PrototypeReviewThreadEntity();
        row.setId("thread");
        row.setRevisionId("revision");
        row.setPageId("page");
        row.setAuthorName("Historical name");
        row.setVerificationLevel("unverified");
        row.setState("resolved");
        row.setConfigVersion(0L);
        row.setCreateTime(1L);
        row.setPublicUpdatedAt(2L);
        var json = new ObjectMapper().valueToTree(PrototypeReviewThreadService.response(row));
        assertFalse(json.has("canReopen"));
        assertFalse(json.has("accessMode"));
        assertTrue(json.has("authorCompany"));
        assertTrue(json.get("authorCompany").isNull());
        assertTrue(json.has("anchor"));
        assertEquals("unverified", json.get("verificationLevel").asText());
    }

    @Test
    void sourceAndTargetSelectorsRejectNumericCoercion() {
        var mapper = new ObjectMapper();
        assertThrows(
                Exception.class,
                () ->
                        mapper.readValue(
                                "{\"sourceThreadId\":123}", ReviewAssociationCreateRequest.class));
        assertThrows(
                Exception.class,
                () ->
                        mapper.readValue(
                                "{\"sourceThreadId\":\"s\",\"targetGuestId\":123}",
                                ReviewAssociationCreateRequest.class));
    }

    private static PrototypeReviewAssociationService.Snapshot snapshot() {
        var row = new PrototypeReviewAssociationEntity();
        row.setId("grant");
        row.setProjectId("project");
        row.setVersionId("version");
        row.setShareId("share");
        row.setRevisionId("revision");
        row.setShareConfigVersion(0L);
        row.setShareGeneration(PrototypeReviewKeys.digest("original"));
        row.setTargetGuestConfigVersion(0L);
        row.setConfigVersion(0L);
        row.setRevoked(false);
        row.setCreateTime(1L);
        row.setEvidenceReference("offline-123");
        row.setCreatedBy("operator");
        var share = new PrototypeShareEntity();
        share.setId("share");
        share.setProjectId("project");
        share.setVersionId("version");
        share.setRevisionId("revision");
        share.setEnabled(true);
        share.setReviewEnabled(true);
        share.setRevisionMode("fixed");
        share.setAccessKey("original");
        share.setConfigVersion(0L);
        var guest = new PrototypeReviewGuestEntity();
        guest.setId("target");
        guest.setProjectId("project");
        guest.setEmail("target@example.invalid");
        guest.setEnabled(true);
        guest.setConfigVersion(0L);
        var version = new PrototypeVersionEntity();
        version.setId("version");
        var snapshot = new PrototypeReviewAssociationService.Snapshot();
        snapshot.setAssociation(row);
        snapshot.setShare(share);
        snapshot.setGuest(guest);
        snapshot.setVersion(version);
        snapshot.setRevisionId("revision");
        return snapshot;
    }
}
