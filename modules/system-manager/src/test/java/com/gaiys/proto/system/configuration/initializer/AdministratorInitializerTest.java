package com.gaiys.proto.system.configuration.initializer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.system.service.SystemInitializationLockService;
import java.util.List;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.hswebframework.web.system.authorization.api.service.reactive.ReactiveUserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

class AdministratorInitializerTest {
    @Test
    @SuppressWarnings("unchecked")
    void invalidFirstPasswordStopsBeforeCreatingAUser() {
        ReactiveUserService users = mock(ReactiveUserService.class);
        ReactiveRepository<DimensionUserEntity, String> bindings = mock(ReactiveRepository.class);
        ReactiveRepository<DimensionEntity, String> dimensions = mock(ReactiveRepository.class);
        TransactionalOperator transactions = mock(TransactionalOperator.class);
        when(users.findByUsername("admin")).thenReturn(Mono.empty());
        when(transactions.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        var lock = mock(SystemInitializationLockService.class);
        when(lock.acquire()).thenReturn(Mono.empty());
        for (String password : List.of("", "short", " ".repeat(12), "x".repeat(129))) {
            var initializer =
                    new AdministratorInitializer(
                            users, bindings, transactions, dimensions, lock, "admin", password);
            assertThrows(
                    AdministratorInitializer.InitialPasswordException.class,
                    () -> initializer.run(new DefaultApplicationArguments()));
        }
        verify(users, never()).addUser(any(UserEntity.class));
        verifyNoInteractions(bindings, dimensions);
    }

    @Test
    @SuppressWarnings("unchecked")
    void existingAdministratorDoesNotRequireOrChangeInitialCredentials() {
        ReactiveUserService users = mock(ReactiveUserService.class);
        ReactiveRepository<DimensionUserEntity, String> bindings = mock(ReactiveRepository.class);
        ReactiveRepository<DimensionEntity, String> dimensions = mock(ReactiveRepository.class);
        TransactionalOperator transactions = mock(TransactionalOperator.class);
        UserEntity existing = new UserEntity();
        existing.setUsername("admin");
        when(users.findByUsername("admin")).thenReturn(Mono.just(existing));
        when(transactions.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        var lock = mock(SystemInitializationLockService.class);
        when(lock.acquire()).thenReturn(Mono.empty());
        var initializer =
                new AdministratorInitializer(
                        users, bindings, transactions, dimensions, lock, "admin", "");
        assertDoesNotThrow(() -> initializer.run(new DefaultApplicationArguments()));
        verify(users, never()).addUser(any(UserEntity.class));
        verifyNoInteractions(bindings, dimensions);
    }
}
