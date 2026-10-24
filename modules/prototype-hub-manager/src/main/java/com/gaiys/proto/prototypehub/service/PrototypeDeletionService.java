package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypePreviewSessionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import lombok.AllArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** 事务内软删除并撤销访问；修订、分享、创建回执、成员谱系及文件留待显式保留策略处理。 */
@Service
@AllArgsConstructor
public class PrototypeDeletionService {
    private final PrototypeProjectService projects;
    private final PrototypeVersionService versions;
    private final PrototypeShareService shares;
    private final TransactionalOperator transactions;
    private final PrototypeProjectAccessService access;
    private final ReactiveRepository<PrototypePreviewSessionEntity, String> sessions;

    public Mono<PrototypeVersionEntity> deleteVersion(String id) {
        SafePaths.id(id);
        return versions.requireById(id)
                .flatMap(
                        found ->
                                access.lockProject(found.getProjectId())
                                        .then(access.lockVersion(id))
                                        .then(
                                                access.requireVersion(
                                                        id,
                                                        PrototypeProjectAccessService.Action.EDIT)))
                .flatMap(
                        version -> {
                            long deletedTime = System.currentTimeMillis();
                            return versions.createUpdate()
                                    .set(PrototypeVersionEntity::getDeletedTime, deletedTime)
                                    .where(PrototypeVersionEntity::getId, id)
                                    .execute()
                                    .then(shares.revokeByVersionId(id))
                                    .then(
                                            sessions.createDelete()
                                                    .where(
                                                            PrototypePreviewSessionEntity
                                                                    ::getVersionId,
                                                            id)
                                                    .execute())
                                    .thenReturn(version)
                                    .doOnNext(deleted -> deleted.setDeletedTime(deletedTime));
                        })
                .as(transactions::transactional);
    }

    public Mono<PrototypeProjectEntity> deleteProject(String id) {
        SafePaths.id(id);
        return access.lockProject(id)
                .then(access.requireProject(id, PrototypeProjectAccessService.Action.DELETE))
                .map(PrototypeProjectAccessService.Grant::project)
                .flatMap(
                        project ->
                                rejectProjectWithVersions(id)
                                        .then(
                                                Mono.defer(
                                                        () -> {
                                                            long deletedTime =
                                                                    System.currentTimeMillis();
                                                            return projects.createUpdate()
                                                                    .set(
                                                                            PrototypeProjectEntity
                                                                                    ::getDeletedTime,
                                                                            deletedTime)
                                                                    .where(
                                                                            PrototypeProjectEntity
                                                                                    ::getId,
                                                                            id)
                                                                    .execute()
                                                                    .then(
                                                                            shares
                                                                                    .revokeByProjectId(
                                                                                            id))
                                                                    .then(
                                                                            sessions.createDelete()
                                                                                    .where(
                                                                                            PrototypePreviewSessionEntity
                                                                                                    ::getProjectId,
                                                                                            id)
                                                                                    .execute())
                                                                    .thenReturn(project)
                                                                    .doOnNext(
                                                                            deleted ->
                                                                                    deleted
                                                                                            .setDeletedTime(
                                                                                                    deletedTime));
                                                        })))
                .as(transactions::transactional);
    }

    /** 项目锁与上传互斥；软删版本不再占用额度或阻止删除项目，但其引用和目录仍保留。 */
    private Mono<Void> rejectProjectWithVersions(String project) {
        return versions.findByProjectId(project)
                .hasElements()
                .flatMap(
                        hasVersions ->
                                hasVersions
                                        ? Mono.error(PrototypeHubException.projectHasVersions())
                                        : Mono.empty());
    }
}
