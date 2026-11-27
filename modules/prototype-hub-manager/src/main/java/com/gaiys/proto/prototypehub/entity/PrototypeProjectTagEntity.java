package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

/** Project organization relation; permission and lifecycle belong to the project boundary. */
@Getter
@Setter
@Table(
        name = "p_project_tag",
        indexes = {
            @Index(
                    name = "idx_project_tag_binding",
                    columnList = "project_id,tag_name",
                    unique = true),
            @Index(name = "idx_project_tag_name", columnList = "tag_name,project_id")
        })
@EnableEntityEvent
@Schema(description = "PrototypeProjectTagEntity")
public class PrototypeProjectTagEntity extends GenericEntity<String> {
    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "tag_name", nullable = false, length = 32)
    private String tagName;
}
