package com.gaiys.proto.system.service.validation;

import com.gaiys.proto.system.entity.PositionEntity;
import com.gaiys.proto.system.service.PositionService;
import com.gaiys.proto.system.service.support.ManagementSupport;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.crud.events.EntityBeforeModifyEvent;
import org.hswebframework.web.crud.events.EntityBeforeSaveEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 已有职位的组织不可变约束，覆盖专用导入以外的 hsweb 保存与修改入口。 在写库前以批量读取的持久化组织为准，拒绝移走职位导致成员绑定及组织授权脱节；不产生写入或外部副作用。
 *
 * @see org.hswebframework.web.crud.annotation.EnableEntityEvent
 */
@Component
@RequiredArgsConstructor
public class PositionOrganizationValidation {
    private final PositionService positions;

    private Mono<Void> validate(List<PositionEntity> rows) {
        List<String> ids =
                rows.stream()
                        .map(PositionEntity::getId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        if (ids.isEmpty()) return Mono.empty();
        return positions
                .findById(ids)
                .collectMap(PositionEntity::getId)
                .doOnNext(
                        existing -> {
                            for (PositionEntity row : rows) {
                                PositionEntity before = existing.get(row.getId());
                                if (before != null
                                        && row.getOrgId() != null
                                        && !Objects.equals(before.getOrgId(), row.getOrgId()))
                                    throw ManagementSupport.conflict();
                            }
                        })
                .then();
    }

    @EventListener
    public void beforeSave(EntityBeforeSaveEvent<PositionEntity> event) {
        event.async(validate(event.getEntity()));
    }

    @EventListener
    public void beforeModify(EntityBeforeModifyEvent<PositionEntity> event) {
        event.async(validate(event.getAfter()));
    }
}
