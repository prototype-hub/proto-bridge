package com.gaiys.proto.system.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.system.entity.UserSettingEntity;
import com.gaiys.proto.system.service.support.ManagementSupport;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Stores saved queries without exposing another user's settings through generic CRUD. */
@Service
@RequiredArgsConstructor
public class UserSettingService extends GenericReactiveCrudService<UserSettingEntity, String> {
    private final ObjectMapper mapper;

    public Flux<UserSettingEntity> list(String userId, String type) {
        check(type, 128);
        QueryParamEntity query = new QueryParamEntity();
        query.setPageIndex(0);
        query.setPageSize(200);
        query.toQuery()
                .orderByDesc(UserSettingEntity::getCreateTime)
                .orderByAsc(UserSettingEntity::getId);
        return createQuery()
                .setParam(query)
                .where(UserSettingEntity::getUserId, userId)
                .and(UserSettingEntity::getType, type)
                .fetch();
    }

    public Mono<UserSettingEntity> get(String userId, String type, String key) {
        check(type, 128);
        check(key, 64);
        return findById(UserSettingEntity.storageId(userId, type, key))
                .switchIfEmpty(Mono.error(ManagementSupport.missing()));
    }

    public Mono<UserSettingEntity> save(
            String userId, String type, String key, String name, Object content) {
        check(type, 128);
        if (key == null) key = UUID.randomUUID().toString().replace("-", "");
        check(key, 64);
        if (name == null || name.isBlank() || name.length() > 128 || content == null)
            return Mono.error(ManagementSupport.invalid());
        try {
            // Saved query payloads are personal data, with a bounded size per record.
            if (mapper.writeValueAsBytes(content).length > 16384)
                return Mono.error(ManagementSupport.invalid());
        } catch (JsonProcessingException error) {
            return Mono.error(ManagementSupport.invalid());
        }
        UserSettingEntity entity = new UserSettingEntity();
        entity.setId(UserSettingEntity.storageId(userId, type, key));
        entity.setUserId(userId);
        entity.setType(type);
        entity.setKey(key);
        entity.setName(name);
        // The official JSON codec treats String as pre-encoded JSON. A TextNode keeps
        // the original string type, including JSON.stringify queries and plain text.
        entity.setContent(
                content instanceof String text ? mapper.getNodeFactory().textNode(text) : content);
        entity.setCreateTime(System.currentTimeMillis());
        return save(entity).then(findById(entity.getId()));
    }

    public Mono<Void> delete(String userId, String type, String key) {
        check(type, 128);
        check(key, 64);
        return deleteById(UserSettingEntity.storageId(userId, type, key)).then();
    }

    private static void check(String value, int max) {
        if (value == null || value.length() > max || !value.matches("[A-Za-z0-9_.:-]+"))
            throw ManagementSupport.invalid();
    }
}
