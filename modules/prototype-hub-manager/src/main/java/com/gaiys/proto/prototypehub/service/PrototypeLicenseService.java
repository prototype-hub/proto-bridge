package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeLicenseProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeInstallationEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.license.PrototypeCapacityPolicy;
import com.gaiys.proto.prototypehub.service.license.PrototypeEntitlementProvider;
import com.gaiys.proto.prototypehub.service.license.PrototypeLicenseVerifier;
import com.gaiys.proto.prototypehub.service.license.PrototypeLicenseVerifier.Verified;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus.AcceptedCredential;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus.EnforcedCapacity;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus.FreeEntitlements;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** 安装级全量凭证接收与只读声明；安装行锁协调多副本序列，将已验声明交给可选纯容量策略。 */
@Service
@RequiredArgsConstructor
public class PrototypeLicenseService implements PrototypeEntitlementProvider {
    private final PrototypeInstallationService installations;
    private final PrototypeProjectAccessService access;
    private final PrototypeLicenseVerifier verifier;
    private final PrototypeLicenseProperties trust;
    private final ObjectProvider<PrototypeCapacityPolicy> capacityPolicies;
    private final TransactionalOperator transactions;
    private final ObservationRegistry observations;

    @Override
    public Mono<PrototypeLicenseStatus> current() {
        return installations.current().map(this::status);
    }

    /** 安装锁内重验当前ADMIN，失败不改变上次接收记录；不记录封套、客户名称或签名。 */
    public Mono<ImportResult> importDocument(String document) {
        return Mono.defer(
                () -> {
                    Observation observation =
                            Observation.createNotStarted("prototype.license.import", observations)
                                    .start();
                    return installations
                            .lock()
                            .then(access.requireAdministrator())
                            .then(installations.current())
                            .flatMap(installation -> accept(installation, document))
                            .as(transactions::transactional)
                            .doOnError(observation::error)
                            .doFinally(signal -> observation.stop());
                });
    }

    private Mono<ImportResult> accept(PrototypeInstallationEntity installation, String document) {
        Verified value =
                verifier.verify(
                        document, installation.getInstallationId(), System.currentTimeMillis());
        String licenseId = value.payload().path("licenseId").asText();
        long sequence = value.payload().path("sequence").asLong();
        // 序列护栏来自持久接收记录，不依赖旧证当前是否仍受信任，避免撤key后静默降序覆盖。
        if (installation.getLicenseId() != null) {
            if (!licenseId.equals(installation.getLicenseId()))
                throw PrototypeLicenseVerifier.failure("LINEAGE_CONFLICT");
            if (sequence < installation.getLicenseSequence()
                    || (sequence == installation.getLicenseSequence()
                            && !value.fingerprint().equals(installation.getLicenseFingerprint())))
                throw PrototypeLicenseVerifier.failure("SEQUENCE_CONFLICT");
            if (value.fingerprint().equals(installation.getLicenseFingerprint()))
                return Mono.just(new ImportResult("unchanged", status(installation)));
        }
        installation.setLicenseDocument(value.document());
        installation.setLicenseId(licenseId);
        installation.setLicenseSequence(sequence);
        installation.setLicenseFingerprint(value.fingerprint());
        installation.setLicenseAcceptedAt(System.currentTimeMillis());
        installation.setLicenseKeyId(value.keyId());
        return installations
                .createUpdate()
                .set(
                        PrototypeInstallationEntity::getLicenseDocument,
                        installation.getLicenseDocument())
                .set(PrototypeInstallationEntity::getLicenseId, licenseId)
                .set(PrototypeInstallationEntity::getLicenseSequence, sequence)
                .set(PrototypeInstallationEntity::getLicenseFingerprint, value.fingerprint())
                .set(
                        PrototypeInstallationEntity::getLicenseAcceptedAt,
                        installation.getLicenseAcceptedAt())
                .set(PrototypeInstallationEntity::getLicenseKeyId, value.keyId())
                .where(PrototypeInstallationEntity::getId, installation.getId())
                .execute()
                .thenReturn(new ImportResult("imported", status(installation)));
    }

    private PrototypeLicenseStatus status(PrototypeInstallationEntity installation) {
        String state = "missing";
        PrototypeLicenseStatus.VerifiedEntitlements claims = null;
        AcceptedCredential accepted = null;
        if (installation.getLicenseDocument() != null) {
            accepted =
                    new AcceptedCredential(
                            installation.getLicenseId(),
                            installation.getLicenseSequence(),
                            installation.getLicenseFingerprint(),
                            installation.getLicenseAcceptedAt(),
                            installation.getLicenseKeyId());
            try {
                claims =
                        PrototypeLicenseStatus.VerifiedEntitlements.from(
                                verifier.verify(
                                                installation.getLicenseDocument(),
                                                installation.getInstallationId(),
                                                System.currentTimeMillis())
                                        .payload());
                state = "valid";
            } catch (PrototypeHubException error) {
                state =
                        switch (error.getCode()) {
                            case "PROTOTYPE_HUB_LICENSE_UNTRUSTED_KEY" -> "untrusted";
                            case "PROTOTYPE_HUB_LICENSE_NOT_YET_VALID" -> "not_yet_valid";
                            default -> "invalid";
                        };
            }
        }
        return new PrototypeLicenseStatus(
                1,
                installation.getInstallationId(),
                state,
                trust.isTrustConfigured(),
                accepted,
                claims,
                new FreeEntitlements(5, 5, List.of()),
                resolveCapacity(claims));
    }

    private EnforcedCapacity resolveCapacity(PrototypeLicenseStatus.VerifiedEntitlements claims) {
        // 缺商业模块仍独立提供免费容量；多个策略直接配置失败，不任意选择或回退。
        PrototypeCapacityPolicy policy = capacityPolicies.getIfAvailable();
        return policy == null
                ? new EnforcedCapacity(5, 5, "free")
                : java.util.Objects.requireNonNull(
                        policy.resolve(claims), "Capacity policy must return a snapshot");
    }

    /** 同一规范封套重复导入返回unchanged，状态摘要用于未知结果核对。 */
    @io.swagger.v3.oas.annotations.media.Schema(description = "凭证导入结果")
    public record ImportResult(
            @io.swagger.v3.oas.annotations.media.Schema(description = "imported或unchanged")
                    String result,
            @io.swagger.v3.oas.annotations.media.Schema(description = "导入后的安装状态")
                    PrototypeLicenseStatus status) {}
}
