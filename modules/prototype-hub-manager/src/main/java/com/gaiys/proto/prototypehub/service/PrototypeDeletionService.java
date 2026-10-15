package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import lombok.AllArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
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
    private final DatabaseClient database;

    public Mono<PrototypeVersionEntity> deleteVersion(String id) {
        SafePaths.id(id);
        return database.sql("select id from p_version where id=:id for update")
                .bind("id", id)
                .fetch()
                .one()
                .flatMap(lock -> versions.findById(id))
                .flatMap(
                        version ->
                                shares.deleteByVersionId(id)
                                        .then(versions.deleteById(id))
                                        .then(
                                                cleanup.enqueue(
                                                        version.getProjectId(),
                                                        id,
                                                        version.getStoragePath()))
                                        .map(
                                                task ->
                                                        reactor.util.function.Tuples.of(
                                                                version, task)))
                .as(transactions::transactional)
                .flatMap(pair -> cleanup.attempt(pair.getT2()).thenReturn(pair.getT1()));
    }

    public Mono<PrototypeProjectEntity> deleteProject(String id) {
        SafePaths.id(id);
        return database.sql("select id from p_project where id=:id for update")
                .bind("id", id)
                .fetch()
                .one()
                .flatMap(lock -> projects.findById(id))
                .flatMap(
                        project ->
                                rejectProjectWithVersions(id)
                                        .then(shares.deleteByProjectId(id))
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
