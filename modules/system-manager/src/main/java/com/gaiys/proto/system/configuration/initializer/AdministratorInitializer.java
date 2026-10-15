package com.gaiys.proto.system.configuration.initializer;

import java.time.Duration;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.hswebframework.web.system.authorization.api.service.reactive.ReactiveUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** 仅在配置账号不存在时创建中文管理员并绑定内置角色，已有账号的密码和授权保持原值。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 200)
public class AdministratorInitializer implements ApplicationRunner {
    private final ReactiveUserService users;
    private final ReactiveRepository<DimensionUserEntity, String> bindings;
    private final TransactionalOperator transactions;
    private final ReactiveRepository<DimensionEntity, String> dimensions;
    private final String username;
    private final String password;

    public AdministratorInitializer(
            ReactiveUserService users,
            ReactiveRepository<DimensionUserEntity, String> bindings,
            TransactionalOperator transactions,
            ReactiveRepository<DimensionEntity, String> dimensions,
            @Value("${proto.admin.username:admin}") String username,
            @Value("${proto.admin.password:}") String password) {
        this.users = users;
        this.bindings = bindings;
        this.transactions = transactions;
        this.dimensions = dimensions;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Startup boundary only: initialization completes before readiness and HTTP traffic.
        users.findByUsername(username)
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    if (password.isBlank()
                                            || password.length() < 12
                                            || password.length() > 128)
                                        return Mono.error(new InitialPasswordException());
                                    UserEntity user = new UserEntity();
                                    user.setUsername(username);
                                    user.setName("管理员");
                                    user.setPassword(password);
                                    user.setStatus((byte) 1);
                                    user.setType("admin");
                                    return users.addUser(user)
                                            .flatMap(created -> bindAdministrator(created));
                                }))
                .as(transactions::transactional)
                .block(Duration.ofMinutes(2));
    }

    public static final class InitialPasswordException extends IllegalStateException {
        public InitialPasswordException() {
            super(
                    "Initial administrator password must not be blank and must contain 12 to 128 characters");
        }
    }

    private Mono<UserEntity> bindAdministrator(UserEntity user) {
        DimensionUserEntity binding = new DimensionUserEntity();
        binding.setDimensionTypeId("role");
        binding.setDimensionId("ADMIN");
        binding.setUserId(user.getId());
        binding.setUserName(user.getName());
        binding.setRelationTime(System.currentTimeMillis());
        binding.generateId();
        return dimensions
                .findById("ADMIN")
                .switchIfEmpty(
                        Mono.error(
                                new IllegalStateException(
                                        "Administrator role must be initialized before creating its account")))
                .flatMap(
                        role -> {
                            binding.setDimensionName(role.getName());
                            return bindings.insert(Mono.just(binding)).thenReturn(user);
                        });
    }
}
