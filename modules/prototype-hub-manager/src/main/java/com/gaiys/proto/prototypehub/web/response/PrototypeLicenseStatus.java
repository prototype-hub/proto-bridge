package com.gaiys.proto.prototypehub.web.response;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/** 安装级凭证状态：声明、容量、首次试用收据与可选模块的实际功能准入分别展示。 */
@Schema(description = "安装与本地凭证状态")
public record PrototypeLicenseStatus(
        @Schema(description = "公共凭证协议版本") int schemaVersion,
        @Schema(description = "持久安装标识") String installationId,
        @Schema(description = "missing/valid/untrusted/invalid/not_yet_valid/unsupported")
                String status,
        @Schema(description = "是否配置受信任公钥") boolean trustConfigured,
        @Schema(description = "接受凭证摘要；缺证为null") AcceptedCredential acceptedCredential,
        @Schema(description = "仅valid时提供已验证声明；不代表实际高级授权") VerifiedEntitlements verifiedEntitlements,
        @Schema(description = "无商业实现时永久免费权益") FreeEntitlements freeEntitlements,
        @Schema(description = "当前实际容量准入；与凭证声明分开") EnforcedCapacity enforcedCapacity,
        @Schema(description = "持久的首次试用收据，不因模块缺失或换证重置") TrialActivation trialActivation,
        @Schema(description = "可选模块计算的当前实际高级权益；不能从声明自行推断")
                AdvancedEntitlements advancedEntitlements) {
    /** 不含原始封套、签名或联系方式，只用于解释接收状态和核对未知结果。 */
    @Schema(description = "已接受凭证摘要")
    public record AcceptedCredential(
            @Schema(description = "授权谱系标识") String licenseId,
            @Schema(description = "已接受单调序列") long sequence,
            @Schema(description = "规范完整封套SHA256") String fingerprint,
            @Schema(description = "首次接受Unix毫秒") long acceptedAt,
            @Schema(description = "签名公钥标识") String keyId) {}

    /** 只返回必要声明，不输出订单标识、原始payload或签名；它们不等于当前生效高级权益。 */
    @Schema(description = "已验签权益声明")
    public record VerifiedEntitlements(
            @Schema(description = "客户显示名称") String customer,
            @Schema(description = "声明版本") String edition,
            @Schema(description = "声明功能列表，不代表基础版已激活") List<String> features,
            @Schema(description = "声明项目总上限") int maxProjects,
            @Schema(description = "所有项目统一的版本总上限") int maxVersionsPerProject,
            @Schema(description = "签发Unix毫秒") long issuedAt,
            @Schema(description = "生效Unix毫秒") long notBefore,
            @Schema(description = "试用截止声明；不实施试用激活") Long trialEndsAt,
            @Schema(description = "维护服务截止声明，不停止永久运行权益") Long maintenanceUntil,
            @Schema(description = "已验凭证模型，2为旧声明，3支持首导起算") int schemaVersion,
            @Schema(description = "相对试用天数，模型3试用固定30，永久或模型2为null") Long trialDurationDays) {
        public static VerifiedEntitlements from(JsonNode payload) {
            return new VerifiedEntitlements(
                    payload.path("customer").asText(),
                    payload.path("edition").asText(),
                    java.util.stream.StreamSupport.stream(
                                    payload.path("features").spliterator(), false)
                            .map(JsonNode::asText)
                            .toList(),
                    payload.path("maxProjects").asInt(),
                    payload.path("maxVersionsPerProject").asInt(),
                    payload.path("issuedAt").asLong(),
                    payload.path("notBefore").asLong(),
                    nullable(payload, "trialEndsAt"),
                    nullable(payload, "maintenanceUntil"),
                    payload.path("schemaVersion").asInt(),
                    payload.has("trialDurationDays")
                            ? nullable(payload, "trialDurationDays")
                            : null);
        }

        private static Long nullable(JsonNode payload, String field) {
            return payload.path(field).isNull() ? null : payload.path(field).asLong();
        }
    }

    /** 安装级首次成功导入收据；持久时间与当前凭证的接受时间彼此独立。 */
    @Schema(description = "首次试用导入收据")
    public record TrialActivation(
            @Schema(description = "首次成功导入Unix毫秒；尚未开始为null") Long startedAt,
            @Schema(description = "首次时刻加30天的Unix毫秒；尚未开始为null") Long endsAt) {}

    /** 只列当前可实际准入的明确功能ID；失效、缺模块或未授予时不返回可用功能。 */
    @Schema(description = "当前实际高级权益")
    public record AdvancedEntitlements(
            @Schema(description = "高级执行策略是否已安装") boolean moduleAvailable,
            @Schema(
                            description =
                                    "module_unavailable/not_licensed/permanent/trial/expired/legacy_trial/not_started")
                    String status,
            @Schema(description = "当前可用的明确功能ID，不表示相应业务模块已经实现") List<String> features) {
        public AdvancedEntitlements {
            features = List.copyOf(features);
        }
    }

    /** 基础能力不依赖商业包或验证码渠道权益。 */
    @Schema(description = "基础免费权益")
    public record FreeEntitlements(
            @Schema(description = "免费项目额度") int maxProjects,
            @Schema(description = "每项目免费逻辑版本额度") int baseVersionsPerProject,
            @Schema(description = "基础实现没有高级激活能力") List<String> features) {}

    /** 实际准入快照；保留统一版本字段，逐项目差异由同一快照解析，不能据声明在前端自行扩容。 */
    @Schema(description = "实际准入容量")
    public record EnforcedCapacity(
            @Schema(description = "当前项目上限") int projectLimit,
            @Schema(description = "当前每项目版本上限") int versionLimit,
            @Schema(description = "当前准入来源") String source,
            @Schema(description = "指定项目实际版本上限；空表表示统一上限")
                    Map<String, Integer> projectVersionLimits) {
        public EnforcedCapacity {
            if (!java.util.Set.of("free", "license").contains(source == null ? "" : source))
                throw new IllegalArgumentException("Capacity source is required");
            if (projectLimit < 1 || versionLimit < 1)
                throw new IllegalArgumentException("Capacity must be finite and positive");
            projectVersionLimits = Map.copyOf(projectVersionLimits);
            if (projectVersionLimits.values().stream().anyMatch(limit -> limit < 1))
                throw new IllegalArgumentException("Project capacity must be finite and positive");
        }

        public EnforcedCapacity(int projectLimit, int versionLimit, String source) {
            this(projectLimit, versionLimit, source, Map.of());
        }

        public int versionLimitFor(String projectId) {
            return projectVersionLimits.getOrDefault(projectId, versionLimit);
        }
    }
}
