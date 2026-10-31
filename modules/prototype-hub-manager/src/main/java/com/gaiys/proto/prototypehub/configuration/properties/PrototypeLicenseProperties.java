package com.gaiys.proto.prototypehub.configuration.properties;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 部署显式信任的Ed25519公钥；缺省无信任根，绝不接受凭证携带的密钥或私钥。 */
@Component
@ConfigurationProperties(prefix = "prototype-hub.license")
public class PrototypeLicenseProperties {
    private Map<String, String> trustedPublicKeys = Map.of();
    private Map<String, PublicKey> keys = Map.of();

    public Map<String, String> getTrustedPublicKeys() {
        return trustedPublicKeys;
    }

    public PublicKey key(String id) {
        return keys.get(id);
    }

    public boolean isTrustConfigured() {
        return !keys.isEmpty();
    }

    public void setTrustedPublicKeys(Map<String, String> values) {
        Map<String, PublicKey> parsed = new TreeMap<>();
        try {
            for (var value : values.entrySet()) {
                if (!value.getKey().matches("[A-Za-z0-9._-]{1,64}"))
                    throw new IllegalArgumentException("Invalid key ID");
                byte[] encoded = Base64.getDecoder().decode(value.getValue());
                PublicKey key =
                        KeyFactory.getInstance("Ed25519")
                                .generatePublic(new X509EncodedKeySpec(encoded));
                if (!Base64.getEncoder().encodeToString(key.getEncoded()).equals(value.getValue()))
                    throw new IllegalArgumentException("Non-canonical public key");
                parsed.put(value.getKey(), key);
            }
        } catch (java.security.GeneralSecurityException | IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "prototype-hub.license.trusted-public-keys requires Ed25519 X509 public keys",
                    error);
        }
        trustedPublicKeys = Map.copyOf(values);
        keys = Map.copyOf(parsed);
    }
}
