package com.gaiys.proto.system.service;

import com.gaiys.proto.system.service.support.ManagementSupport;
import java.util.Objects;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 内置账号身份以配置和当前持久化用户为准，不从请求用户名、账号类型或 ADMIN 成员身份推断。 */
@Service
public class BuiltInAdministratorService {
    private final ReactiveRepository<UserEntity, String> users;
    private final String username;

    public BuiltInAdministratorService(
            ReactiveRepository<UserEntity, String> users,
            @Value("${proto.admin.username:admin}") String username) {
        this.users = users;
        this.username = username;
    }

    public Mono<UserEntity> find() {
        return users.createQuery().where(UserEntity::getUsername, username).fetchOne();
    }

    public Mono<Boolean> isEnabled(String userId) {
        return users.findById(userId)
                .map(
                        user ->
                                isConfigured(user)
                                        && Byte.valueOf(UserEntity.STATUS_ENABLED)
                                                .equals(user.getStatus()))
                .defaultIfEmpty(false);
    }

    public boolean isConfigured(UserEntity persisted) {
        return username.equals(persisted.getUsername());
    }

    public void validateUpdate(UserEntity persisted, UserEntity update) {
        if (!isConfigured(persisted)) return;
        if ((update.getId() != null && !Objects.equals(persisted.getId(), update.getId()))
                || (update.getUsername() != null && !username.equals(update.getUsername()))
                || (update.getStatus() != null
                        && !Byte.valueOf(UserEntity.STATUS_ENABLED).equals(update.getStatus())))
            throw ManagementSupport.conflict();
    }

    public void validateReplacement(UserEntity persisted, UserEntity replacement) {
        if (isConfigured(persisted)
                && (!Objects.equals(persisted.getId(), replacement.getId())
                        || !username.equals(replacement.getUsername())
                        || !Byte.valueOf(UserEntity.STATUS_ENABLED)
                                .equals(replacement.getStatus())))
            throw ManagementSupport.conflict();
    }
}
