package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.web.request.ProjectRequest;
import java.util.UUID;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class PrototypeProjectService
        extends GenericReactiveCrudService<PrototypeProjectEntity, String> {
    public Mono<PrototypeProjectEntity> requireById(String id) {
        return findById(id)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("project")));
    }

    public Mono<PrototypeProjectEntity> create(ProjectRequest input, String actor) {
        PrototypeProjectEntity project = new PrototypeProjectEntity();
        project.setId(UUID.randomUUID().toString().replace("-", ""));
        project.setCreatorId(actor);
        project.setCreateTime(System.currentTimeMillis());
        apply(project, input, actor);
        return insert(project).thenReturn(project);
    }

    public Mono<PrototypeProjectEntity> update(String id, ProjectRequest input, String actor) {
        return requireById(id)
                .flatMap(
                        project -> {
                            apply(project, input, actor);
                            return updateById(id, project).thenReturn(project);
                        });
    }

    private static void apply(PrototypeProjectEntity project, ProjectRequest input, String actor) {
        project.setName(input.name().trim());
        project.setDescription(input.description());
        project.setCover(input.cover());
        project.setModifierId(actor);
        project.setModifyTime(System.currentTimeMillis());
    }
}
