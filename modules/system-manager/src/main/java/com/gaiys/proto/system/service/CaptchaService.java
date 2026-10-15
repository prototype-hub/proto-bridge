package com.gaiys.proto.system.service;

import com.gaiys.proto.system.security.properties.CaptchaProperties;
import com.gaiys.proto.system.security.store.LoginChallengeStore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.hswebframework.web.authorization.events.AuthorizationBeforeEvent;
import org.hswebframework.web.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** 生成和校验一次性登录验证码的安全服务。图像与摘要计算隔离到专用工作线程，挑战消费由存储服务保证一次性，工作线程随组件销毁释放。 */
@Service
public class CaptchaService implements AutoCloseable {
    private final CaptchaProperties config;
    private final LoginChallengeStore store;
    private final SecureRandom random;
    private final Scheduler worker = Schedulers.newBoundedElastic(2, 64, "login-images");

    public CaptchaService(
            CaptchaProperties config,
            LoginChallengeStore store,
            @Qualifier("captchaRandom") SecureRandom random) {
        if (config.getTtl() == null
                || config.getTtl().isZero()
                || config.getTtl().isNegative()
                || config.getTtl().compareTo(java.time.Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("validation.captcha_ttl");
        this.config = config;
        this.store = store;
        this.random = random;
    }

    /** 一次性验证码的公开图像；答案摘要仅保存在服务端挑战存储。 */
    @Schema(name = "CaptchaImage", description = "登录验证码图像")
    public record Image(
            @Schema(description = "一次性验证码挑战标识") String key,
            @Schema(description = "验证码图像的 Base64 数据地址") String base64) {}

    public Mono<Image> image(int width, int height) {
        if (!config.isEnabled())
            return Mono.error(
                    new BusinessException("error.captcha_disabled", "CAPTCHA_DISABLED", 404));
        if (width < 100 || width > 400 || height < 30 || height > 150)
            return Mono.error(new IllegalArgumentException("validation.captcha_size"));
        return Mono.fromCallable(
                        () -> {
                            String key = UUID.randomUUID().toString();
                            StringBuilder answer = new StringBuilder();
                            String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
                            for (int i = 0; i < 5; i++)
                                answer.append(alphabet.charAt(random.nextInt(alphabet.length())));
                            BufferedImage image =
                                    new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                            var graphics = image.createGraphics();
                            try {
                                graphics.setColor(Color.WHITE);
                                graphics.fillRect(0, 0, width, height);
                                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, height - 8));
                                for (int i = 0; i < 12; i++) {
                                    graphics.setColor(
                                            new Color(
                                                    random.nextInt(160),
                                                    random.nextInt(160),
                                                    random.nextInt(160)));
                                    graphics.drawLine(
                                            random.nextInt(width),
                                            random.nextInt(height),
                                            random.nextInt(width),
                                            random.nextInt(height));
                                }
                                graphics.setColor(new Color(25, 55, 95));
                                graphics.drawString(answer.toString(), 8, height - 6);
                            } finally {
                                graphics.dispose();
                            }
                            var bytes = new ByteArrayOutputStream();
                            ImageIO.write(image, "png", bytes);
                            return new Challenge(
                                    new Image(
                                            key,
                                            "data:image/png;base64,"
                                                    + Base64.getEncoder()
                                                            .encodeToString(bytes.toByteArray())),
                                    digest(key, answer.toString()));
                        })
                .subscribeOn(worker)
                .flatMap(
                        c ->
                                store.put("captcha:" + c.image().key(), c.digest(), config.getTtl())
                                        .thenReturn(c.image()));
    }

    public Mono<Void> verify(String key, String answer) {
        if (key == null || answer == null || key.length() > 64 || answer.length() > 16)
            return invalid();
        return store.consume("captcha:" + key)
                .filter(
                        value ->
                                MessageDigest.isEqual(
                                        value.getBytes(StandardCharsets.UTF_8),
                                        digest(key, answer).getBytes(StandardCharsets.UTF_8)))
                .switchIfEmpty(
                        Mono.error(
                                new BusinessException(
                                        "error.captcha_invalid", "CAPTCHA_INVALID", 400)))
                .then();
    }

    @EventListener
    public void beforeLogin(AuthorizationBeforeEvent event) {
        if (config.isEnabled())
            event.async(
                    verify(
                            event.<String>getParameter("verifyKey").orElse(null),
                            event.<String>getParameter("verifyCode").orElse(null)));
    }

    private static Mono<Void> invalid() {
        return Mono.error(new BusinessException("error.captcha_invalid", "CAPTCHA_INVALID", 400));
    }

    private static String digest(String key, String answer) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (key + ":" + answer.toUpperCase(Locale.ROOT))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Challenge(Image image, String digest) {}

    @jakarta.annotation.PreDestroy
    public void close() {
        worker.dispose();
    }
}
