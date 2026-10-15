package com.gaiys.proto.system.configuration.customizer;

import java.util.Locale;
import org.hswebframework.web.authorization.define.AuthorizeDefinitionContext;
import org.hswebframework.web.authorization.define.AuthorizeDefinitionCustomizer;
import org.hswebframework.web.authorization.define.MergedAuthorizeDefinition;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * 在 hsweb 自动同步权限前，将代码定义的权限和动作名称解析为中文持久化名称。
 *
 * <p>已有名称由框架合并规则保留；旧库中的历史默认英文名称仅由显式旧库转换流程处理， 此定制器不会在启动时改写已有名称。
 */
@Component
public class ChinesePermissionDefinitionCustomizer implements AuthorizeDefinitionCustomizer {
    private final MessageSource messages;

    public ChinesePermissionDefinitionCustomizer(MessageSource messages) {
        this.messages = messages;
    }

    @Override
    public void custom(AuthorizeDefinitionContext context) {
        // PermissionSynchronization 使用此公开定义类型；SPI 的通用上下文只提供新增资源方法。
        if (context instanceof MergedAuthorizeDefinition definitions) {
            for (var resource : definitions.getResources()) {
                resource.setName(
                        chineseName(
                                "hswebframework.web.system.permission." + resource.getId(),
                                resource.getName()));
                for (var action : resource.getActions()) {
                    action.setName(
                            chineseName(
                                    "hswebframework.web.system.action." + action.getId(),
                                    action.getName()));
                }
            }
        }
    }

    private String chineseName(String key, String annotationName) {
        // 存储语言固定为中文，不能受启动机器或请求语言影响；缺少资源时沿用中文注解。
        return messages.getMessage(key, null, annotationName, Locale.CHINESE);
    }
}
