package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeInstallationEntity;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 维护数据库共享的部署身份，使用数据库行锁协调不同应用副本的项目创建。 */
@Service
@AllArgsConstructor
public class PrototypeInstallationService
        extends GenericReactiveCrudService<PrototypeInstallationEntity, String> {
    private static final String SINGLETON_ID = "default";
    private final DatabaseClient database;

    /** EasyORM 建表完成后调用；并发启动只插入一次，已有超限数据不会影响身份初始化。 */
    public Mono<PrototypeInstallationEntity> initialize() {
        // 当前应用限定 PostgreSQL；ON CONFLICT 保证多副本启动时不覆盖已有部署身份。
        return database.sql(
                        "insert into p_installation(id, installation_id, create_time) values(:id,"
                                + " :installationId, :createTime) on conflict(id) do nothing")
                .bind("id", SINGLETON_ID)
                .bind("installationId", UUID.randomUUID().toString())
                .bind("createTime", System.currentTimeMillis())
                .fetch()
                .rowsUpdated()
                .then(current());
    }

    public Mono<PrototypeInstallationEntity> current() {
        return findById(SINGLETON_ID);
    }

    /** 必须在创建项目的事务中调用，锁持有至资源和回执一起提交或回滚。 */
    public Mono<Void> lock() {
        return database.sql("select id from p_installation where id=:id for update")
                .bind("id", SINGLETON_ID)
                .fetch()
                .one()
                .switchIfEmpty(
                        Mono.error(new IllegalStateException("Installation is not initialized")))
                .then();
    }
}
