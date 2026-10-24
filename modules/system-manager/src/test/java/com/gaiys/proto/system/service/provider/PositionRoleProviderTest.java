package com.gaiys.proto.system.service.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.gaiys.proto.system.entity.PositionEntity;
import com.gaiys.proto.system.service.PositionService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.web.authorization.DimensionType;
import org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionUserService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** 授权派生使用真实 Publisher 遍历，循环父级必须终止且每次订阅重新求值。 */
class PositionRoleProviderTest {
    private final DefaultDimensionService dimensions = mock(DefaultDimensionService.class);
    private final DefaultDimensionUserService bindings = mock(DefaultDimensionUserService.class);
    private final PositionService positions = mock(PositionService.class);

    @SuppressWarnings("unchecked")
    private final ReactiveQuery<DimensionUserEntity> query =
            mock(ReactiveQuery.class, RETURNS_SELF);

    private final PositionRoleProvider provider =
            new PositionRoleProvider(dimensions, bindings, positions);

    private void binding(String... ids) {
        when(bindings.createQuery()).thenReturn(query);
        when(query.fetch())
                .thenReturn(
                        Flux.fromArray(ids)
                                .map(
                                        id -> {
                                            var binding = new DimensionUserEntity();
                                            binding.setDimensionId(id);
                                            return binding;
                                        }));
    }

    private PositionEntity position(String id, String parent, String org) {
        var position = new PositionEntity();
        position.setId(id);
        position.setParentId(parent);
        position.setOrgId(org);
        return position;
    }

    private void completes(Flux<?> result) {
        StepVerifier.create(result).expectComplete().verify(Duration.ofSeconds(1));
    }

    @Test
    void twoNodeCycleTerminatesAndFiltersMissingRolesOnEverySubscription() {
        binding("a");
        var a = position("a", "b", "org-a");
        a.setRoles(Arrays.asList(null, Map.of(), Map.of("id", ""), Map.of("id", "role-a")));
        var b = position("b", "a", "org-b");
        b.setRoles(List.of(Map.of("id", "role-a"), Map.of("id", "role-b")));
        when(positions.findById(List.of("a"))).thenReturn(Flux.just(a));
        when(positions.findById("a")).thenReturn(Mono.just(a));
        when(positions.findById("b")).thenReturn(Mono.just(b));
        List<List<String>> requested = new ArrayList<>();
        when(dimensions.getDimensionsById(any(DimensionType.class), anyList()))
                .thenAnswer(
                        call -> {
                            requested.add(new ArrayList<>(call.getArgument(1)));
                            return Flux.empty();
                        });
        var result = provider.getDimensionByUserId("user");
        completes(result);
        completes(result);
        assertEquals(
                List.of(
                        List.of("role-a", "role-b"),
                        List.of("a", "b"),
                        List.of("org-a", "org-b"),
                        List.of("role-a", "role-b"),
                        List.of("a", "b"),
                        List.of("org-a", "org-b")),
                requested);
        verify(positions, times(2)).findById("a");
        verify(positions, times(2)).findById("b");
    }

    @Test
    void selfCycleStopsAfterOneParentLookupAndNormalParentRolesAreRetained() {
        binding("self");
        var self = position("self", "self", "org");
        when(positions.findById(List.of("self"))).thenReturn(Flux.just(self));
        when(positions.findById("self")).thenReturn(Mono.just(self));
        when(dimensions.getDimensionsById(any(DimensionType.class), anyList()))
                .thenReturn(Flux.empty());
        completes(provider.getDimensionByUserId("user"));
        verify(positions).findById("self");

        binding("child");
        var child = position("child", "parent", "org");
        var parent = position("parent", null, "org");
        parent.setRoles(List.of(Map.of("id", "inherited-role")));
        when(positions.findById(List.of("child"))).thenReturn(Flux.just(child));
        when(positions.findById("parent")).thenReturn(Mono.just(parent));
        completes(provider.getDimensionByUserId("user"));
        verify(dimensions)
                .getDimensionsById(
                        org.hswebframework.web.authorization.DefaultDimensionType.role,
                        List.of("inherited-role"));
    }

    @Test
    void emptyBindingsCompleteWithoutQueriesAndParentFailureStillPropagates() {
        binding();
        completes(provider.getDimensionByUserId("user"));
        verifyNoInteractions(positions, dimensions);
        binding("child");
        when(positions.findById(List.of("child")))
                .thenReturn(Flux.just(position("child", "parent", "org")));
        when(positions.findById("parent"))
                .thenReturn(Mono.error(new IllegalStateException("query failed")));
        StepVerifier.create(provider.getDimensionByUserId("user"))
                .expectErrorMessage("query failed")
                .verify(Duration.ofSeconds(1));
    }
}
