package com.gaiys.proto.prototypehub.service.keys;

import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 将分享随机密钥派生为单个不可变快照的能力凭据，不保存会话或缓存。
 *
 * <p>域标识和长度前缀绑定分享、逻辑版本及快照，避免跨用途或拼接歧义；随机密钥仅留在服务端。
 */
public final class PrototypeShareKeys {
    private PrototypeShareKeys() {}

    public static String derive(PrototypeShareEntity share, String revisionId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            share.getAccessKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String content =
                    "prototype-hub/share-revision/v1:"
                            + field(share.getId())
                            + field(share.getVersionId())
                            + field(revisionId);
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HmacSHA256 is unavailable", error);
        }
    }

    private static String field(String value) {
        return value.length() + ":" + value;
    }
}
