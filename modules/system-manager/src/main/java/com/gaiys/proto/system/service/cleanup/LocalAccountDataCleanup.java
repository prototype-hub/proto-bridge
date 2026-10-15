package com.gaiys.proto.system.service.cleanup;

import com.gaiys.proto.system.entity.UserSettingEntity;
import com.gaiys.proto.system.service.RoleMenuGrantService;
import com.gaiys.proto.system.service.UserDetailService;
import com.gaiys.proto.system.service.UserSettingService;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.system.authorization.api.event.DimensionDeletedEvent;
import org.hswebframework.web.system.authorization.api.event.UserDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 在 hsweb 删除事件中清理本地账号扩展资料和角色菜单选择。
 *
 * <p>沿用官方账号与维度删除校验及事务，替代这些本地关联原先依赖的数据库级联； 成员绑定和权限设置继续由 hsweb 自己清理。
 */
@Component
@RequiredArgsConstructor
public class LocalAccountDataCleanup {
    private final UserDetailService details;
    private final UserSettingService settings;
    private final RoleMenuGrantService grants;

    @EventListener
    public void userDeleted(UserDeletedEvent event) {
        String userId = event.getUser().getId();
        event.async(
                details.deleteById(userId)
                        .then(
                                settings.createDelete()
                                        .where(UserSettingEntity::getUserId, userId)
                                        .execute()));
    }

    @EventListener
    public void dimensionDeleted(DimensionDeletedEvent event) {
        if ("role".equals(event.getDimensionType())) {
            event.async(grants.deleteById(event.getDimensionId()));
        }
    }
}
