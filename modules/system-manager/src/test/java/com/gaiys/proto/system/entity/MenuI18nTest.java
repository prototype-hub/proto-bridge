package com.gaiys.proto.system.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;
import org.hswebframework.web.i18n.LocaleUtils;
import org.junit.jupiter.api.Test;

class MenuI18nTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void officialLocaleResolutionChangesOnlyTheReadOnlyDisplayName() {
        var menu = new MenuEntity();
        menu.setName("用户管理");
        menu.setI18nMessages(
                Map.of("name", Map.of("zh_CN", "用户管理", "en_US", "User Management", "en", "Users")));
        JsonNode english = LocaleUtils.doWith(Locale.US, () -> mapper.valueToTree(menu));
        assertEquals("用户管理", english.path("name").asText());
        assertEquals("User Management", english.path("i18nName").asText());
        assertEquals("Users", LocaleUtils.doWith(Locale.UK, menu::getI18nName));
        assertEquals("用户管理", LocaleUtils.doWith(Locale.SIMPLIFIED_CHINESE, menu::getI18nName));
        assertEquals("用户管理", menu.getName());
    }

    @Test
    void inputDisplayNameCannotOverrideStoredNamesOrTranslations() throws Exception {
        var menu =
                mapper.readValue(
                        """
                {"name":"用户管理","i18nName":"attempted override",
                 "i18nMessages":{"name":{"zh_CN":"用户管理","en":"Users"}}}
                """,
                        MenuEntity.class);
        assertEquals("用户管理", menu.getName());
        assertEquals("Users", LocaleUtils.doWith(Locale.ENGLISH, menu::getI18nName));
        assertEquals(
                Map.of("name", Map.of("zh_CN", "用户管理", "en", "Users")), menu.getI18nMessages());
        var fallback =
                mapper.readValue("{\"name\":\"自定义菜单\",\"i18nName\":\"ignored\"}", MenuEntity.class);
        assertNull(fallback.getI18nMessages());
        assertEquals("自定义菜单", LocaleUtils.doWith(Locale.ENGLISH, fallback::getI18nName));
    }
}
