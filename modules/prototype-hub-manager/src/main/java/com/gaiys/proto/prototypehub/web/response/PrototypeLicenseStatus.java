package com.gaiys.proto.prototypehub.web.response;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 安装级凭证状态：已验声明与当前实际准入分别展示，不代表高级模块已激活。 */
@Schema(description = "安装与本地凭证状态")
public record PrototypeLicenseStatus(
        @Schema(description = "公共凭证协议版本") int schemaVersion,
        @Schema(description = "持久安装标识") String installationId,
        @Schema(description = "missing/valid/untrusted/invalid/not_yet_valid") String status,
        @Schema(description = "是否配置受信任公钥") boolean trustConfigured,
        @Schema(description = "接受凭证摘要；缺证为null") AcceptedCredential acceptedCredential,
        @Schema(description = "仅valid时提供已验证声明；不代表实际高级授权") VerifiedEntitlements verifiedEntitlements,
        @Schema(description = "无商业实现时永久免费权益") FreeEntitlements freeEntitlements,
        @Schema(description = "当前实际容量准入；与凭证声明分开") EnforcedCapacity enforcedCapacity) {
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
            @Schema(description = "声明项目总量") int maxProjects,
            @Schema(description = "基础每项目版本量") int baseVersionsPerProject,
            @Schema(description = "指定项目版本块声明") List<ProjectVersionGrant> projectVersionGrants,
            @Schema(description = "签发Unix毫秒") long issuedAt,
            @Schema(description = "生效Unix毫秒") long notBefore,
            @Schema(description = "试用截止声明；不实施试用激活") Long trialEndsAt,
            @Schema(description = "维护服务截止声明，不停止永久运行权益") Long maintenanceUntil) {
        public static VerifiedEntitlements from(JsonNode payload) {
            return new VerifiedEntitlements(
                    payload.path("customer").asText(),
                    payload.path("edition").asText(),
                    java.util.stream.StreamSupport.stream(
                                    payload.path("features").spliterator(), false)
                            .map(JsonNode::asText)
                            .toList(),
                    payload.path("maxProjects").asInt(),
                    payload.path("baseVersionsPerProject").asInt(),
                    java.util.stream.StreamSupport.stream(
                                    payload.path("projectVersionGrants").spliterator(), false)
                            .map(
                                    value ->
                                            new ProjectVersionGrant(
                                                    value.path("projectId").asText(),
                                                    value.path("extraVersionBlocks").asInt()))
                            .toList(),
                    payload.path("issuedAt").asLong(),
                    payload.path("notBefore").asLong(),
                    nullable(payload, "trialEndsAt"),
                    nullable(payload, "maintenanceUntil"));
        }

        private static Long nullable(JsonNode payload, String field) {
            return payload.path(field).isNull() ? null : payload.path(field).asLong();
        }
    }

    /** 项目ID只绑定声明，不触发新增或永久扩容。 */
    @Schema(description = "指定项目版本块声明")
    public record ProjectVersionGrant(
            @Schema(description = "项目标识") String projectId,
            @Schema(description = "额外版本块数；每块5个") int extraVersionBlocks) {}

    /** 基础能力不依赖商业包或验证码渠道权益。 */
    @Schema(description = "基础免费权益")
    public record FreeEntitlements(
            @Schema(description = "免费项目额度") int maxProjects,
            @Schema(description = "每项目免费逻辑版本额度") int baseVersionsPerProject,
            @Schema(description = "基础实现没有高级激活能力") List<String> features) {}

    /** 仅反映现有部署准入，不根据未执行的声明扩容。 */
    @Schema(description = "实际准入容量")
    public record EnforcedCapacity(
            @Schema(description = "当前项目上限") int projectLimit,
            @Schema(description = "当前每项目版本上限") int versionLimit,
            @Schema(description = "当前准入来源") String source) {}
}
