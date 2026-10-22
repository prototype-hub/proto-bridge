package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypePreviewSessionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import lombok.AllArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Service
@AllArgsConstructor
public class PrototypeDeletionService {
    private final PrototypeProjectService projects;
    private final PrototypeVersionService versions;
    private final PrototypeShareService shares;
    private final FileCleanupService cleanup;
    private final TransactionalOperator transactions;
    private final PrototypeProjectAccessService access;
    private final ReactiveRepository<PrototypePreviewSessionEntity, String> sessions;
    private final ReactiveRepository<PrototypeRevisionEntity, String> revisions;
    private final ReactiveRepository<PrototypeProjectMemberEntity, String> members;

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
                        version ->
                                // 有界批次登记全部历史目录，释放读取后批量写入，不在开放游标中嵌套写入。
                                cleanup.enqueueVersionHistory(version)
                                        .then(shares.deleteByVersionId(id))
                                        .then(
                                                sessions.createDelete()
                                                        .where(
                                                                PrototypePreviewSessionEntity
                                                                        ::getVersionId,
                                                                id)
                                                        .execute())
                                        .then(
                                                revisions
                                                        .createDelete()
                                                        .where(
                                                                PrototypeRevisionEntity
                                                                        ::getProjectId,
                                                                version.getProjectId())
                                                        .and(
                                                                PrototypeRevisionEntity
                                                                        ::getVersionId,
                                                                id)
                                                        .execute())
                                        .then(versions.deleteById(id))
                                        .thenReturn(version))
                .as(transactions::transactional);
        // 目录只在提交后由有界持久任务回收，DELETE 不等待无上限的历史 I/O。
    }

    public Mono<PrototypeProjectEntity> deleteProject(String id) {
        SafePaths.id(id);
        return access.lockProject(id)
                .then(access.requireProject(id, PrototypeProjectAccessService.Action.DELETE))
                .map(PrototypeProjectAccessService.Grant::project)
                .flatMap(
                        project ->
                                rejectProjectWithVersions(id)
                                        .then(shares.deleteByProjectId(id))
                                        .then(
                                                sessions.createDelete()
                                                        .where(
                                                                PrototypePreviewSessionEntity
                                                                        ::getProjectId,
                                                                id)
                                                        .execute())
                                        .then(
                                                members.createDelete()
                                                        .where(
                                                                PrototypeProjectMemberEntity
                                                                        ::getProjectId,
                                                                id)
                                                        .execute())
                                        .then(projects.deleteById(id))
                                        .then(cleanup.enqueue(id, null, null))
                                        .map(
                                                task ->
                                                        reactor.util.function.Tuples.of(
                                                                project, task)))
                .as(transactions::transactional)
                .flatMap(pair -> cleanup.attempt(pair.getT2()).thenReturn(pair.getT1()));
    }

    /** 项目锁保证版本上传不能越过校验；存在任一版本时终止事务，不触发删除或文件清理。 */
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
