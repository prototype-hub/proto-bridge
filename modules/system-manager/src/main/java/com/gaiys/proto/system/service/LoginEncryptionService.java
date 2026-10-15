package com.gaiys.proto.system.service;

import com.gaiys.proto.system.security.store.LoginChallengeStore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import org.hswebframework.web.authorization.events.AuthorizationDecodeEvent;
import org.hswebframework.web.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** 登录请求加密的安全服务。RSA 计算隔离到专用工作线程，公开配置不包含私钥，私钥挑战按原有有效期保存并在解密时一次性消费。 */
@Service
public class LoginEncryptionService {
    private final boolean enabled;
    private final LoginChallengeStore store;
    private final Scheduler worker = Schedulers.newBoundedElastic(2, 32, "login-crypto");

    public LoginEncryptionService(
            @Value("${proto.security.login-encryption-enabled:false}") boolean enabled,
            LoginChallengeStore store) {
        this.enabled = enabled;
        this.store = store;
    }

    /** 单次登录加密的公开配置；关闭加密时密钥标识和公钥为空。 */
    @Schema(name = "LoginEncryptionConfig", description = "登录请求加密公开配置")
    public record Config(
            @Schema(description = "是否启用登录加密") boolean enabled,
            @Schema(description = "本次加密密钥标识") String id,
            @Schema(description = "客户端加密使用的公钥") String publicKey) {}

    public Mono<Config> config() {
        if (!enabled) return Mono.just(new Config(false, null, null));
        return Mono.fromCallable(
                        () -> {
                            var generator = KeyPairGenerator.getInstance("RSA");
                            generator.initialize(2048);
                            var pair = generator.generateKeyPair();
                            return new Keys(
                                    UUID.randomUUID().toString(),
                                    Base64.getEncoder()
                                            .encodeToString(pair.getPublic().getEncoded()),
                                    Base64.getEncoder()
                                            .encodeToString(pair.getPrivate().getEncoded()));
                        })
                .subscribeOn(worker)
                .flatMap(
                        key ->
                                store.put(
                                                "rsa:" + key.id(),
                                                key.privateKey(),
                                                Duration.ofMinutes(5))
                                        .thenReturn(new Config(true, key.id(), key.publicKey())));
    }

    public Mono<String> decrypt(String id, String encrypted) {
        if (id == null || id.length() > 64 || encrypted == null || encrypted.length() > 1024)
            return invalid();
        return store.consume("rsa:" + id)
                .switchIfEmpty(invalid())
                .flatMap(
                        privateKey ->
                                Mono.fromCallable(
                                                () -> {
                                                    var key =
                                                            KeyFactory.getInstance("RSA")
                                                                    .generatePrivate(
                                                                            new PKCS8EncodedKeySpec(
                                                                                    Base64
                                                                                            .getDecoder()
                                                                                            .decode(
                                                                                                    privateKey)));
                                                    var cipher =
                                                            Cipher.getInstance(
                                                                    "RSA/ECB/PKCS1Padding");
                                                    cipher.init(Cipher.DECRYPT_MODE, key);
                                                    return new String(
                                                            cipher.doFinal(
                                                                    Base64.getDecoder()
                                                                            .decode(encrypted)),
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8);
                                                })
                                        .subscribeOn(worker))
                .onErrorMap(
                        e ->
                                new BusinessException(
                                        "error.login_encryption_invalid",
                                        "LOGIN_ENCRYPTION_INVALID",
                                        400));
    }

    @EventListener
    public void decode(AuthorizationDecodeEvent event) {
        if (enabled)
            event.async(
                    decrypt(
                                    event.<String>getParameter("encryptId").orElse(null),
                                    event.getPassword())
                            .doOnNext(event::setPassword)
                            .then());
    }

    private static <T> Mono<T> invalid() {
        return Mono.error(
                new BusinessException(
                        "error.login_encryption_invalid", "LOGIN_ENCRYPTION_INVALID", 400));
    }

    private record Keys(String id, String publicKey, String privateKey) {}

    @jakarta.annotation.PreDestroy
    public void close() {
        worker.dispose();
    }
}
