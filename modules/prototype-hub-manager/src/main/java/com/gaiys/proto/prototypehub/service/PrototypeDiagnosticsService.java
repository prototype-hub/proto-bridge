package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.notification.NotificationMailTransport;
import com.gaiys.proto.notification.SmtpWork;
import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.FileCleanupEntity;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.response.PrototypeDiagnosticsSnapshot;
import com.gaiys.proto.prototypehub.web.response.PrototypeDiagnosticsSnapshot.Check;
import com.gaiys.proto.prototypehub.web.response.PrototypeDiagnosticsSnapshot.Checks;
import com.gaiys.proto.prototypehub.web.response.PrototypeDiagnosticsSnapshot.CleanupData;
import com.gaiys.proto.prototypehub.web.response.PrototypeDiagnosticsSnapshot.DatabaseData;
import com.gaiys.proto.prototypehub.web.response.PrototypeDiagnosticsSnapshot.LicenseData;
import com.gaiys.proto.prototypehub.web.response.PrototypeDiagnosticsSnapshot.MailData;
import com.gaiys.proto.prototypehub.web.response.PrototypeDiagnosticsSnapshot.StorageData;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.crud.configuration.EntityTableMetadataResolver;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 管理员本地只读诊断；不遍历通用health树，避免触发SMTP网络检查。 */
@Service
@RequiredArgsConstructor
public class PrototypeDiagnosticsService {
    static final Duration CHECK_TIMEOUT = Duration.ofSeconds(3);
    static final long MINIMUM_FREE_BYTES = 10 * 1_048_576L;
    private static final Set<String> LICENSE_STATES =
            Set.of("missing", "valid", "untrusted", "not_yet_valid", "unsupported", "invalid");
    private static final Set<String> ADVANCED_STATES =
            Set.of(
                    "module_unavailable",
                    "not_licensed",
                    "permanent",
                    "trial",
                    "expired",
                    "legacy_trial",
                    "not_started");
    private final PrototypeProjectService projects;
    private final PrototypeLicenseService licenses;
    private final PrototypeHubStorageProperties storage;
    private final FileWork files;
    private final NotificationMailTransport mail;
    private final SmtpWork smtp;
    private final DatabaseOperator database;
    private final EntityTableMetadataResolver metadata;
    private final ObservationRegistry observations;

    /** 五个固定分项各自限时且不重试；失败保留其他结果，不导出异常文本。 */
    public Mono<PrototypeDiagnosticsSnapshot> snapshot() {
        return Mono.defer(
                () -> {
                    long now = System.currentTimeMillis();
                    Observation observation =
                            Observation.createNotStarted("prototype.diagnostics.read", observations)
                                    .start();
                    return Mono.zip(
                                    check(this::database),
                                    check(this::storage),
                                    check(() -> cleanup(now)),
                                    check(this::mail),
                                    check(this::license))
                            .map(
                                    values ->
                                            new PrototypeDiagnosticsSnapshot(
                                                    1,
                                                    now,
                                                    values.getT1().data() != null
                                                            && values.getT2().data() != null
                                                            && values.getT3().data() != null
                                                            && values.getT4().data() != null
                                                            && values.getT5().data() != null,
                                                    new Checks(
                                                            values.getT1(),
                                                            values.getT2(),
                                                            values.getT3(),
                                                            values.getT4(),
                                                            values.getT5())))
                            .doOnError(observation::error)
                            .doFinally(signal -> observation.stop());
                });
    }

    static <T> Mono<Check<T>> check(Supplier<Mono<Check<T>>> probe) {
        return Mono.defer(probe)
                .switchIfEmpty(Mono.error(new IllegalStateException("Diagnostic unavailable")))
                .timeout(CHECK_TIMEOUT)
                .onErrorResume(
                        error ->
                                Mono.just(
                                        new Check<>(
                                                error instanceof TimeoutException
                                                        ? "timeout"
                                                        : "error",
                                                error instanceof TimeoutException
                                                        ? "TIMEOUT"
                                                        : "UNAVAILABLE",
                                                null)));
    }

    private Mono<Check<DatabaseData>> database() {
        return projects.usage()
                .map(
                        usage ->
                                new Check<>(
                                        usage.projects().overLimit() ? "warning" : "ok",
                                        usage.projects().overLimit() ? "CAPACITY_EXCEEDED" : "OK",
                                        new DatabaseData(usage.projects())));
    }

    private Mono<Check<StorageData>> storage() {
        // 仅查询最近存在的父目录；不创建、探写文件或枚举业务资源。
        return files.call(
                () -> {
                    Path root = storage.getRoot().toAbsolutePath();
                    while (!Files.exists(root) && root.getParent() != null) root = root.getParent();
                    boolean writable = Files.isDirectory(root) && Files.isWritable(root);
                    long freeBytes = Files.getFileStore(root).getUsableSpace();
                    boolean healthy = writable && freeBytes > MINIMUM_FREE_BYTES;
                    return new Check<>(
                            healthy ? "ok" : "warning",
                            healthy ? "OK" : "STORAGE_UNAVAILABLE",
                            new StorageData(
                                    writable,
                                    freeBytes,
                                    MINIMUM_FREE_BYTES,
                                    "nearest_existing_parent"));
                });
    }

    private Mono<Check<CleanupData>> cleanup(long now) {
        // 结构化单次聚合只返回四个计数，不加载任务、路径或触发清理/重试。
        var table = metadata.resolve(FileCleanupEntity.class);
        var type = DSL.field(DSL.name("resource_type"), String.class);
        var known = type.in("upload-candidate", "expired-snapshot", "review-image", "material");
        var nextAttempt = DSL.field(DSL.name("next_attempt"), Long.class);
        var attempts = DSL.field(DSL.name("attempts"), Integer.class);
        var query =
                DSL.using(SQLDialect.POSTGRES)
                        .select(
                                DSL.count().as("pending"),
                                DSL.count().filterWhere(known.and(nextAttempt.le(now))).as("due"),
                                DSL.count().filterWhere(attempts.gt(0)).as("retried"),
                                DSL.count()
                                        .filterWhere(known.not().or(type.isNull()))
                                        .as("unknown"))
                        .from(DSL.table(DSL.name(table.getSchema().getName(), table.getName())));
        return database.sql()
                .reactive()
                .select(
                        SqlRequests.of(query.getSQL(), query.getBindValues().toArray()),
                        ResultWrappers.map())
                .single()
                .map(
                        row -> {
                            var data =
                                    new CleanupData(
                                            ((Number) row.get("pending")).longValue(),
                                            ((Number) row.get("due")).longValue(),
                                            ((Number) row.get("retried")).longValue(),
                                            ((Number) row.get("unknown")).longValue());
                            return new Check<>(
                                    data.pending() > 0 ? "warning" : "ok",
                                    data.pending() > 0 ? "CLEANUP_BACKLOG" : "OK",
                                    data);
                        });
    }

    private Mono<Check<MailData>> mail() {
        return Mono.fromSupplier(
                () -> {
                    boolean configured = mail.available();
                    var workers = smtp.snapshot();
                    boolean busy = workers.queued() >= 64;
                    return new Check<>(
                            !configured || busy ? "warning" : "ok",
                            !configured ? "MAIL_NOT_CONFIGURED" : busy ? "SMTP_BUSY" : "OK",
                            new MailData(
                                    configured,
                                    workers.active(),
                                    workers.queued(),
                                    workers.completed(),
                                    workers.rejected()));
                });
    }

    private Mono<Check<LicenseData>> license() {
        return licenses.current().map(value -> new Check<>("ok", "OK", licenseData(value)));
    }

    static LicenseData licenseData(PrototypeLicenseStatus value) {
        var advanced = value.advancedEntitlements();
        var capacity = value.enforcedCapacity();
        // 扩展策略的字符串也不直接透传，未知状态固定为unknown而不是输出任意声明。
        return new LicenseData(
                LICENSE_STATES.contains(value.status()) ? value.status() : "unknown",
                value.trustConfigured(),
                capacity.source(),
                capacity.projectLimit(),
                capacity.versionLimit(),
                advanced.moduleAvailable(),
                ADVANCED_STATES.contains(advanced.status()) ? advanced.status() : "unknown",
                advanced.features().size());
    }
}
