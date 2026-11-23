package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 固定允许字段的本地诊断；不承载配置、身份、路径、许可证声明或原始错误。 */
@Schema(description = "本地运行诊断快照；分项独立采样，不是事务或数据完整性证明")
public record PrototypeDiagnosticsSnapshot(
        int schemaVersion, long collectedAt, boolean complete, Checks checks) {
    /** 有数据的warning仍是完整采集；error/timeout的data为null。 */
    public record Check<T>(String status, String code, T data) {}

    public record Checks(
            Check<DatabaseData> database,
            Check<StorageData> storage,
            Check<CleanupData> cleanup,
            Check<MailData> mail,
            Check<LicenseData> license) {}

    public record DatabaseData(CapacityUsage projects) {}

    public record StorageData(
            boolean writable, long freeBytes, long minimumFreeBytes, String scope) {}

    /** due仅含当前识别类型；unknown保留，不被本诊断消费或重试。 */
    public record CleanupData(long pending, long due, long retried, long unknown) {}

    public record MailData(
            boolean configured, int active, int queued, long completed, long rejected) {}

    public record LicenseData(
            String status,
            boolean trustConfigured,
            String capacitySource,
            int projectLimit,
            int versionLimit,
            boolean advancedModuleAvailable,
            String advancedStatus,
            int enabledFeatureCount) {}
}
