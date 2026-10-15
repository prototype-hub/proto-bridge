package com.gaiys.proto.logging.properties;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "proto.logging")
public class LogStorageProperties {
    private boolean systemEnabled = true, accessEnabled = true;
    private String systemMinLevel = "INFO";
    private int localCapacity = 10000;
    private int localWriteQueueCapacity = 2048;
    private Duration localRetention = Duration.ofDays(1);
    private Elasticsearch elasticsearch = new Elasticsearch();

    @Getter
    @Setter
    public static class Elasticsearch {
        private boolean enabled;
        private String dataStream = "proto-bridge-logs",
                ilmPolicy = "proto-bridge-logs-retention",
                apiKey = "",
                caCertificate = "";
        private int batchSize = 100,
                queueCapacity = 2048,
                maxRetries = 3,
                retentionDays = 30,
                replicas = 0;
        private Duration flushInterval = Duration.ofSeconds(1),
                retryBackoff = Duration.ofMillis(200),
                requestTimeout = Duration.ofSeconds(3);
        private boolean manageTemplate = true;
    }
}
