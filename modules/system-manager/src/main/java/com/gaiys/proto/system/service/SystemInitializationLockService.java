package com.gaiys.proto.system.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.crud.configuration.EntityTableMetadataResolver;
import org.hswebframework.web.system.authorization.api.entity.DimensionTypeEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 启动阶段以已有角色维度行串行化权限、菜单和管理员种子初始化，不覆盖已存在的维度名称。
 *
 * <p>仅协调遵守同一协议的应用初始化流程，不承担运行期通用锁或外部数据库写入的互斥。 调用方须提供覆盖整批种子的响应式事务，锁随其提交或回滚释放；获取失败向启动入口传播。
 */
@Service
@RequiredArgsConstructor
public class SystemInitializationLockService {
    private final DatabaseOperator database;
    private final EntityTableMetadataResolver resolver;
    private final ReactiveRepository<DimensionTypeEntity, String> types;

    /** 必须在调用方的响应式事务内执行；行锁一直持有到全部种子提交或回滚。 */
    public Mono<Void> acquire() {
        return Mono.defer(
                () ->
                        database.dml()
                                .upsert(resolver.resolve(DimensionTypeEntity.class))
                                .columns("id", "name")
                                .values("role", "角色")
                                .doNothingOnConflict(true)
                                .execute()
                                .reactive()
                                .then(
                                        types.createQuery()
                                                .select(DimensionTypeEntity::getId)
                                                .where(DimensionTypeEntity::getId, "role")
                                                .forUpdate()
                                                .fetchOne()
                                                .switchIfEmpty(
                                                        Mono.error(
                                                                new IllegalStateException(
                                                                        "System initialization coordination row is missing"))))
                                .then());
    }
}
