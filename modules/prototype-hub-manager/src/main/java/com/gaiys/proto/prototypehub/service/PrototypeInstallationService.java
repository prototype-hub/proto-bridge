package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeInstallationEntity;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.crud.configuration.EntityTableMetadataResolver;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 维护数据库共享的部署身份，使用数据库行锁协调不同应用副本的项目创建。 */
@Service
@AllArgsConstructor
public class PrototypeInstallationService
        extends GenericReactiveCrudService<PrototypeInstallationEntity, String> {
    private static final String SINGLETON_ID = "default";
    private final DatabaseOperator database;
    private final EntityTableMetadataResolver metadata;

    /** EasyORM 建表后调用；由官方 upsert 方言处理并发唯一键，绝不覆盖已有部署身份。 */
    public Mono<PrototypeInstallationEntity> initialize() {
        return Mono.defer(
                        () ->
                                database.dml()
                                        .upsert(metadata.resolve(PrototypeInstallationEntity.class))
                                        .columns("id", "installationId", "createTime")
                                        .values(
                                                SINGLETON_ID,
                                                UUID.randomUUID().toString(),
                                                System.currentTimeMillis())
                                        .doNothingOnConflict(true)
                                        .execute()
                                        .reactive())
                .then(current());
    }

    public Mono<PrototypeInstallationEntity> current() {
        return findById(SINGLETON_ID);
    }

    /** 必须在创建项目的事务中调用，锁持有至资源和回执一起提交或回滚。 */
    public Mono<Void> lock() {
        return createQuery()
                .select(PrototypeInstallationEntity::getId)
                .where(PrototypeInstallationEntity::getId, SINGLETON_ID)
                .forUpdate()
                .fetchOne()
                .switchIfEmpty(
                        Mono.error(new IllegalStateException("Installation is not initialized")))
                .then();
    }
}
