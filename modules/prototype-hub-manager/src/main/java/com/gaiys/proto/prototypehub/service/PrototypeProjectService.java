package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.web.request.ProjectRequest;
import com.gaiys.proto.prototypehub.web.response.CapacityUsage;
import com.gaiys.proto.prototypehub.web.response.ProjectUsageResponse;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 管理项目元信息及部署的实际用量。
 *
 * <p>创建时在安装行锁内校验容量并持久化回执；删除约束和文件清理由删除服务协调。
 */
@Service
@AllArgsConstructor
public class PrototypeProjectService
        extends GenericReactiveCrudService<PrototypeProjectEntity, String> {
    private final PrototypeInstallationService installations;
    private final PrototypeCreationReceiptService receipts;
    private final TransactionalOperator transactions;
    private final PrototypeProjectAccessService access;

    public Mono<PrototypeProjectEntity> requireById(String id) {
        return findById(id)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("project")));
    }

    public Mono<PrototypeProjectEntity> create(ProjectRequest input, String actor) {
        return create(input, actor, null);
    }

    /** 安装行锁覆盖回执、实际用量和插入，不同副本争抢最后一个名额也只会成功一次。 */
    public Mono<PrototypeProjectEntity> create(ProjectRequest input, String actor, String key) {
        return Mono.defer(
                () -> {
                    var request =
                            receipts.request(
                                    actor,
                                    "create-project",
                                    "",
                                    key,
                                    input.name().trim(),
                                    input.description(),
                                    input.cover());
                    return access.lockActiveUsers(actor)
                            .then(installations.lock())
                            .then(
                                    receipts.replay(
                                            request,
                                            id ->
                                                    findById(id)
                                                            .flatMap(
                                                                    project ->
                                                                            access.requireProject(
                                                                                            id,
                                                                                            PrototypeProjectAccessService
                                                                                                    .Action
                                                                                                    .EDIT)
                                                                                    .thenReturn(
                                                                                            project))))
                            .switchIfEmpty(
                                    Mono.defer(() -> createWithinCapacity(input, actor, request)))
                            .as(transactions::transactional);
                });
    }

    /** 用量只读真实数据；超过上限的既有库仍可启动、查看、修改和删除。 */
    public Mono<ProjectUsageResponse> usage() {
        return installations
                .current()
                .flatMap(
                        installation ->
                                createQuery()
                                        .count()
                                        .map(
                                                used ->
                                                        new ProjectUsageResponse(
                                                                installation.getInstallationId(),
                                                                CapacityUsage.from(
                                                                        used.longValue()))));
    }

    private Mono<PrototypeProjectEntity> createWithinCapacity(
            ProjectRequest input, String actor, PrototypeCreationReceiptService.Request request) {
        return createQuery()
                .count()
                .flatMap(
                        used -> {
                            if (used.longValue() >= CapacityUsage.LIMIT) {
                                return Mono.error(
                                        new PrototypeHubException(
                                                "error.prototype-hub.project_limit_reached",
                                                "PROTOTYPE_HUB_PROJECT_LIMIT_REACHED",
                                                409,
                                                CapacityUsage.LIMIT));
                            }
                            PrototypeProjectEntity project = new PrototypeProjectEntity();
                            project.setId(UUID.randomUUID().toString().replace("-", ""));
                            project.setCreatorId(actor);
                            project.setOwnerId(actor);
                            project.setAccessEpoch(UUID.randomUUID().toString());
                            project.setCreateTime(System.currentTimeMillis());
                            apply(project, input, actor);
                            return insert(project)
                                    .then(receipts.record(request, project.getId()))
                                    .thenReturn(project);
                        });
    }

    public Mono<PrototypeProjectEntity> update(String id, ProjectRequest input, String actor) {
        return access.lockProject(id)
                .then(access.requireProject(id, PrototypeProjectAccessService.Action.EDIT))
                .map(PrototypeProjectAccessService.Grant::project)
                .flatMap(
                        project -> {
                            apply(project, input, actor);
                            return updateById(id, project).thenReturn(project);
                        })
                .as(transactions::transactional);
    }

    private static void apply(PrototypeProjectEntity project, ProjectRequest input, String actor) {
        project.setName(input.name().trim());
        project.setDescription(input.description());
        project.setCover(input.cover());
        project.setModifierId(actor);
        project.setModifyTime(System.currentTimeMillis());
    }
}
