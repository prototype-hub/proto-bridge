package com.gaiys.proto.logging.configuration;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.logging.properties.LogStorageProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(
        prefix = "proto.logging.elasticsearch",
        name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class ElasticsearchLogClientConfiguration {
    @Bean(destroyMethod = "close")
    public RestClient logRestClient(ElasticsearchProperties p, LogStorageProperties config)
            throws Exception {
        var settings = config.getElasticsearch();
        if (!settings.getDataStream().matches("[a-z][a-z0-9-]{2,100}")
                || !settings.getIlmPolicy().matches("[a-z][a-z0-9-]{2,100}"))
            throw new IllegalArgumentException("validation.log_index_invalid");
        var hosts = p.getUris().stream().map(HttpHost::create).toArray(HttpHost[]::new);
        var credentials = new BasicCredentialsProvider();
        if (p.getUsername() != null && !p.getUsername().isBlank())
            credentials.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(p.getUsername(), p.getPassword()));
        final SSLContext ssl;
        if (!settings.getCaCertificate().isBlank()) {
            var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null);
            try (var input = Files.newInputStream(Path.of(settings.getCaCertificate()))) {
                int i = 0;
                for (var certificate :
                        CertificateFactory.getInstance("X.509").generateCertificates(input))
                    keyStore.setCertificateEntry("ca-" + i++, certificate);
            }
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(keyStore);
            ssl = SSLContext.getInstance("TLS");
            ssl.init(null, trust.getTrustManagers(), null);
        } else ssl = null;
        var builder =
                RestClient.builder(hosts)
                        .setRequestConfigCallback(
                                request ->
                                        request.setConnectTimeout(
                                                        (int) p.getConnectionTimeout().toMillis())
                                                .setSocketTimeout(
                                                        (int) p.getSocketTimeout().toMillis()))
                        .setHttpClientConfigCallback(
                                client -> {
                                    client.setDefaultCredentialsProvider(credentials);
                                    if (ssl != null) client.setSSLContext(ssl);
                                    return client;
                                });
        if (!settings.getApiKey().isBlank())
            builder.setDefaultHeaders(
                    new org.apache.http.Header[] {
                        new BasicHeader("Authorization", "ApiKey " + settings.getApiKey())
                    });
        return builder.build();
    }

    @Bean
    public ElasticsearchAsyncClient logElasticsearchClient(RestClient client, ObjectMapper mapper) {
        return new ElasticsearchAsyncClient(
                new RestClientTransport(client, new JacksonJsonpMapper(mapper)));
    }
}
