package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class PrototypeVersionService
        extends GenericReactiveCrudService<PrototypeVersionEntity, String> {
    public Flux<PrototypeVersionEntity> findAll(String project) {
        return project == null || project.isBlank()
                ? createQuery().fetch()
                : findByProjectId(project);
    }

    public Flux<PrototypeVersionEntity> findByProjectId(String project) {
        return createQuery().where(PrototypeVersionEntity::getProjectId, project).fetch();
    }

    public Mono<PrototypeVersionEntity> requireById(String id) {
        return findById(id)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("version")));
    }

    public Mono<PrototypeVersionEntity> replace(
            PrototypeVersionEntity old,
            String name,
            String description,
            String path,
            String entry) {
        PrototypeVersionEntity update = old.copyTo(new PrototypeVersionEntity());
        update.setVersionName(name.trim());
        update.setDescription(description);
        update.setStoragePath(path);
        update.setEntryFile(entry);
        return createUpdate()
                .set(update)
                .where(PrototypeVersionEntity::getId, old.getId())
                .and(PrototypeVersionEntity::getStoragePath, old.getStoragePath())
                .execute()
                .flatMap(
                        rows ->
                                rows == 1
                                        ? Mono.just(update)
                                        : Mono.error(
                                                new PrototypeHubException(
                                                        "error.prototype-hub.version_conflict",
                                                        "PROTOTYPE_HUB_VERSION_CONFLICT",
                                                        409)));
    }
}
