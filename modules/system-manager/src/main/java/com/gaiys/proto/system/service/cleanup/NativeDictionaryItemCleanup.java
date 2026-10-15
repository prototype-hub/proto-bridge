package com.gaiys.proto.system.service.cleanup;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.crud.events.EntityDeletedEvent;
import org.hswebframework.web.crud.events.EntityEventListenerConfigure;
import org.hswebframework.web.crud.events.EntityEventListenerCustomizer;
import org.hswebframework.web.crud.events.EntityEventPhase;
import org.hswebframework.web.crud.events.EntityEventType;
import org.hswebframework.web.dictionary.entity.DictionaryEntity;
import org.hswebframework.web.dictionary.entity.DictionaryItemEntity;
import org.hswebframework.web.dictionary.service.DefaultDictionaryItemService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 字典删除时在官方删除事务中批量清理选项，保持无物理外键时的既有级联语义。 */
@Component
@RequiredArgsConstructor
public class NativeDictionaryItemCleanup implements EntityEventListenerCustomizer {
    // 配置事件阶段早于 Repository 构造；只在实际删除时获取官方服务，避免初始化循环。
    private final ObjectProvider<DefaultDictionaryItemService> items;

    /** 仅启用官方字典实体的删除后事件；不改变其他增删改与权限校验阶段。 */
    @Override
    public void customize(EntityEventListenerConfigure configure) {
        configure.enable(DictionaryEntity.class, EntityEventType.delete, EntityEventPhase.after);
    }

    @EventListener
    public void deleted(EntityDeletedEvent<DictionaryEntity> event) {
        List<String> ids = event.getEntity().stream().map(DictionaryEntity::getId).toList();
        if (ids.isEmpty()) return;
        event.async(
                items.getObject()
                        .createDelete()
                        .in(DictionaryItemEntity::getDictId, ids)
                        .execute());
    }
}
