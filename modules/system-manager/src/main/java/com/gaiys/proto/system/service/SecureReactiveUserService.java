package com.gaiys.proto.system.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.hswebframework.web.system.authorization.api.PasswordEncoder;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.hswebframework.web.system.authorization.defaults.service.DefaultReactiveUserService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Reuses hsweb user CRUD and events, isolating expensive hash computation. */
public class SecureReactiveUserService extends DefaultReactiveUserService {
    private final PasswordEncoder encoder;
    private final BuiltInAdministratorService administrators;
    private final Scheduler passwords = Schedulers.newBoundedElastic(4, 64, "password-hash");

    public SecureReactiveUserService(
            PasswordEncoder encoder, BuiltInAdministratorService administrators) {
        this.encoder = encoder;
        this.administrators = administrators;
    }

    @Override
    protected Mono<UserEntity> doAdd(UserEntity user) {
        return Mono.defer(() -> super.doAdd(user)).subscribeOn(passwords);
    }

    @Override
    protected Mono<UserEntity> doUpdate(UserEntity old, UserEntity newer) {
        return Mono.defer(
                        () -> {
                            administrators.validateUpdate(old, newer);
                            return super.doUpdate(old, newer);
                        })
                .subscribeOn(passwords);
    }

    @Override
    public Mono<Integer> updateById(String id, Mono<UserEntity> request) {
        return findById(id)
                .zipWith(request)
                .flatMap(
                        values -> {
                            administrators.validateUpdate(values.getT1(), values.getT2());
                            return super.updateById(id, Mono.just(values.getT2()));
                        });
    }

    @Override
    public Mono<UserEntity> findById(String id) {
        return super.findById(id).publishOn(passwords);
    }

    @Override
    public Mono<UserEntity> findByUsernameAndPassword(String username, String password) {
        return findByUsername(username)
                .publishOn(passwords)
                .filter(
                        user ->
                                MessageDigest.isEqual(
                                        encoder.encode(password, user.getSalt())
                                                .getBytes(StandardCharsets.UTF_8),
                                        user.getPassword().getBytes(StandardCharsets.UTF_8)));
    }

    public void dispose() {
        passwords.dispose();
    }
}
