package com.gaiys.proto.prototypehub.service.keys;

import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 有期限的访问事件票据；与运行凭据用途隔离，使过期去重数据可以安全清理。 */
public final class PrototypeVisitTickets {
    public static final long TTL = Duration.ofHours(24).toMillis();

    private PrototypeVisitTickets() {}

    public static String issue(
            String signingKey, PrototypeShareEntity share, String revision, long now) {
        String payload = now + ":" + UUID.randomUUID();
        return payload + ":" + signature(signingKey, share, revision, payload);
    }

    /** 只接受服务端签发事件；分享的原密钥轮换会同时撤销此前的统计票据。 */
    public static String verify(
            String signingKey,
            PrototypeShareEntity share,
            String revision,
            String ticket,
            long now) {
        if (ticket == null || !ticket.matches("[0-9]{13}:[a-f0-9-]{36}:[A-Za-z0-9_-]{43}"))
            throw denied();
        String[] parts = ticket.split(":");
        long issued = Long.parseLong(parts[0]);
        if (issued > now
                || now - issued >= TTL
                || !PrototypeReviewKeys.matches(
                        signature(signingKey, share, revision, parts[0] + ":" + parts[1]),
                        parts[2])) throw denied();
        return parts[1];
    }

    private static String signature(
            String signingKey, PrototypeShareEntity share, String revision, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            mac.doFinal(
                                    ("prototype-hub/share-visit/v1:"
                                                    + field(share.getId())
                                                    + field(share.getVersionId())
                                                    + field(revision)
                                                    + field(share.getAccessKey())
                                                    + field(payload))
                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HmacSHA256 is unavailable", error);
        }
    }

    private static String field(String value) {
        return value.length() + ":" + value;
    }

    private static PrototypeHubException denied() {
        return PrototypeHubException.forbidden(
                "error.prototype-hub.visit_ticket_invalid", "PROTOTYPE_HUB_VISIT_TICKET_INVALID");
    }
}
