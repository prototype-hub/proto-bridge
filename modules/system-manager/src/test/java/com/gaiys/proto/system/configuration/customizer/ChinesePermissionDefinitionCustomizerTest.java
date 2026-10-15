package com.gaiys.proto.system.configuration.customizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.hswebframework.web.authorization.annotation.Logical;
import org.hswebframework.web.authorization.define.MergedAuthorizeDefinition;
import org.hswebframework.web.authorization.define.ResourceDefinition;
import org.hswebframework.web.system.authorization.api.entity.ActionEntity;
import org.hswebframework.web.system.authorization.api.entity.PermissionEntity;
import org.hswebframework.web.system.authorization.defaults.service.PermissionSynchronization;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.support.StaticMessageSource;

class ChinesePermissionDefinitionCustomizerTest {
    @Test
    void standardResourcesTranslateDefinitionsWithoutChangingIdentifiersOrAuthorizationMetadata() {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/system/messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        var customizer = new ChinesePermissionDefinitionCustomizer(messages);
        var resource = ResourceDefinition.of("organization", "organization");
        resource.setDescription("Existing description");
        resource.setGroup(List.of("system"));
        resource.setLogical(Logical.AND);
        resource.addAction("query", "query")
                .addAction("save", "save")
                .addAction("delete", "delete");
        var definitions = new MergedAuthorizeDefinition();
        definitions.addResource(resource);

        customizer.custom(definitions);
        var translated = definitions.getResources().iterator().next();
        assertEquals("organization", translated.getId());
        assertEquals("组织管理", translated.getName());
        assertEquals("Existing description", translated.getDescription());
        assertEquals(List.of("system"), translated.getGroup());
        assertEquals(Logical.AND, translated.getLogical());
        assertEquals("查询", translated.getAction("query").orElseThrow().getName());
        assertEquals("保存", translated.getAction("save").orElseThrow().getName());
        assertEquals("删除", translated.getAction("delete").orElseThrow().getName());
    }

    @Test
    void newPermissionResourcesUseChineseWhileTheCallingThreadKeepsItsEnglishLocale() {
        var messages = new StaticMessageSource();
        messages.addMessage(
                "hswebframework.web.system.permission.review-center", Locale.CHINESE, "审核中心");
        messages.addMessage(
                "hswebframework.web.system.permission.review-center",
                Locale.ENGLISH,
                "Review center");
        messages.addMessage("hswebframework.web.system.action.review", Locale.CHINESE, "审核");
        messages.addMessage("hswebframework.web.system.action.review", Locale.ENGLISH, "Review");
        var definitions = new MergedAuthorizeDefinition();
        definitions.addResource(
                ResourceDefinition.of("review-center", "Review center")
                        .addAction("review", "Review"));
        var previous = LocaleContextHolder.getLocaleContext();
        try {
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            new ChinesePermissionDefinitionCustomizer(messages).custom(definitions);
            var resource = definitions.getResources().iterator().next();
            assertEquals("审核中心", resource.getName());
            assertEquals("审核", resource.getAction("review").orElseThrow().getName());
            assertEquals(Locale.ENGLISH, LocaleContextHolder.getLocale());
        } finally {
            LocaleContextHolder.setLocaleContext(previous);
        }
    }

    @Test
    void chineseAnnotationsRemainTheFallbackForNewResourcesWithoutMessageKeys() {
        var definitions = new MergedAuthorizeDefinition();
        definitions.addResource(
                ResourceDefinition.of("inspection", "巡检管理").addAction("inspect", "巡检"));
        new ChinesePermissionDefinitionCustomizer(new StaticMessageSource()).custom(definitions);
        var resource = definitions.getResources().iterator().next();
        assertEquals("巡检管理", resource.getName());
        assertEquals("巡检", resource.getAction("inspect").orElseThrow().getName());
    }

    @Test
    void officialMergePreservesCustomNamesAndDisabledStateWhileAddingChineseActions() {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/system/messages");
        messages.setDefaultEncoding("UTF-8");
        var definitions = new MergedAuthorizeDefinition();
        definitions.addResource(
                ResourceDefinition.of("user", "user")
                        .addAction("query", "query")
                        .addAction("save", "save"));
        new ChinesePermissionDefinitionCustomizer(messages).custom(definitions);
        var stored = new PermissionEntity();
        stored.setId("user");
        stored.setName("自定义用户权限");
        stored.setStatus((byte) 0);
        stored.setActions(
                List.of(
                        ActionEntity.builder()
                                .action("query")
                                .name("自定义查询")
                                .describe("原有描述")
                                .build()));
        Map<String, PermissionEntity> existing = new LinkedHashMap<>();
        existing.put("user", stored);

        var merged =
                PermissionSynchronization.convert(
                        existing, definitions.getResources().iterator().next(), Map.of());
        assertSame(stored, merged);
        assertEquals("自定义用户权限", merged.getName());
        assertEquals((byte) 0, merged.getStatus());
        assertEquals(
                List.of("query", "save"),
                merged.getActions().stream().map(ActionEntity::getAction).toList());
        assertEquals("自定义查询", merged.getActions().get(0).getName());
        assertEquals("原有描述", merged.getActions().get(0).getDescribe());
        assertEquals("保存", merged.getActions().get(1).getName());
    }
}
