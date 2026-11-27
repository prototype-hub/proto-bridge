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
        name = "p_project_favorite",
        indexes = {
            @Index(
                    name = "idx_project_favorite_binding",
                    columnList = "user_id,project_id",
                    unique = true),
            @Index(
                    name = "idx_project_favorite_filter",
                    columnList = "user_id,favorite,project_id"),
            @Index(name = "idx_project_favorite_project", columnList = "project_id")
        })
@EnableEntityEvent
@Schema(description = "PrototypeProjectFavoriteEntity")
public class PrototypeProjectFavoriteEntity extends GenericEntity<String> {
    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "favorite", nullable = false)
    private Boolean favorite;

    @Column(name = "config_version", nullable = false)
    private Long configVersion;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
