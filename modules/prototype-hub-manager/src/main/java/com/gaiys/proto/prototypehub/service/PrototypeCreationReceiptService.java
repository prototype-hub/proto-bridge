package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeCreationReceiptEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Function;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 资源创建回执的唯一校验与持久化边界，不保存原始 Idempotency-Key 或请求内容。 */
@Service
public class PrototypeCreationReceiptService
        extends GenericReactiveCrudService<PrototypeCreationReceiptEntity, String> {

    /** 请求键可省略；提供时限定为 1–128 个可打印 ASCII 非空白字符。 */
    public Request request(
            String actor, String operation, String scope, String key, String... fields) {
        if (key == null) return null;
        if (key.isEmpty() || key.length() > 128 || key.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw PrototypeHubException.badRequest(
                    "error.prototype-hub.idempotency_key_invalid",
                    "PROTOTYPE_HUB_IDEMPOTENCY_KEY_INVALID");
        }
        return new Request(digest(actor, operation, scope, key), digest(fields));
    }

    /** 调用方先取得安装行或项目行锁；重放先于额度检查，已删除资源不会被重建。 */
    public <T> Mono<T> replay(Request request, Function<String, Mono<T>> resource) {
        if (request == null) return Mono.empty();
        return findById(request.id())
                .flatMap(
                        receipt -> {
                            if (!receipt.getFingerprint().equals(request.fingerprint())) {
                                return Mono.error(conflict());
                            }
                            return resource.apply(receipt.getResourceId())
                                    .switchIfEmpty(
                                            Mono.error(PrototypeCreationReceiptService::conflict));
                        });
    }

    /** 与资源 INSERT 同一事务提交；失败时资源和回执一起回滚。 */
    public Mono<Void> record(Request request, String resourceId) {
        if (request == null) return Mono.empty();
        PrototypeCreationReceiptEntity receipt = new PrototypeCreationReceiptEntity();
        receipt.setId(request.id());
        receipt.setFingerprint(request.fingerprint());
        receipt.setResourceId(resourceId);
        receipt.setCreateTime(System.currentTimeMillis());
        return insert(receipt).then();
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.idempotency_conflict",
                "PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT",
                409);
    }

    private static String digest(String... fields) {
        MessageDigest digest = sha256();
        // 长度前缀区分 null、空串及分隔符，避免不同字段组合得到相同的请求指纹。
        for (String field : fields) {
            byte[] value = field == null ? null : field.getBytes(StandardCharsets.UTF_8);
            digest.update(
                    ByteBuffer.allocate(Integer.BYTES)
                            .putInt(value == null ? -1 : value.length)
                            .array());
            if (value != null) digest.update(value);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", error);
        }
    }

    /** 经校验的请求摘要；ZIP 内容在专用文件线程计算摘要后并入指纹。 */
    public record Request(String id, String fingerprint) {
        public Request withPayload(String payloadDigest) {
            return new Request(id, digest(fingerprint, payloadDigest));
        }
    }
}
