package com.gaiys.proto.prototypehub.configuration.properties;

import java.nio.file.Path;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PrototypeHub 的原型存储位置、上传限额与 Inspector 配置。
 *
 * <p>配置使用 prototype-hub.storage 前缀；默认存储目录继续使用既有路径，避免移动已保存的文件。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "prototype-hub.storage")
public class PrototypeHubStorageProperties {
    private Path root = Path.of("./data/protohub");
    private long maxUploadSizeMb = 500;
    private long maxExtractedSizeMb = 2048;
    private int maxZipFiles = 20_000;
    private int maxEntrySearchDepth = 6;
    private long maxHtmlSizeMb = 20;
    private String inspectorPath = "/api/prototype-hub/public/inspector.js";

    public long getMaxUploadSizeBytes() {
        return Math.multiplyExact(maxUploadSizeMb, 1_048_576);
    }

    public long getMaxExtractedSizeBytes() {
        return Math.multiplyExact(maxExtractedSizeMb, 1_048_576);
    }

    public long getMaxHtmlSizeBytes() {
        return Math.multiplyExact(maxHtmlSizeMb, 1_048_576);
    }
}
