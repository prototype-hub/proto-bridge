package com.gaiys.proto.prototypehub.service.cleanup;

import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectMemberService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.system.authorization.api.event.UserDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 账号删除时同步撤销原型资产绑定，防止 hsweb 同名账号重建复用用户 ID 后继承先前的项目权限。
 *
 * <p>使用官方异步事件的事务内链路；数据库清理失败会使账号删除失败，不在提交后尽力删除。
 */
@Component
@RequiredArgsConstructor
public class PrototypeAccountAccessCleanup {
    private final PrototypeProjectAccessService access;
    private final PrototypeProjectMemberService members;
    private final DatabaseClient database;

    @EventListener
    public void deleted(UserDeletedEvent event) {
        event.async(revoke(event.getUser().getId()));
    }

    private Mono<Void> revoke(String userId) {
        return revokeBatch(userId, "")
                .then(
                        database.sql("delete from p_preview_session where user_id=:userId")
                                .bind("userId", userId)
                                .fetch()
                                .rowsUpdated())
                .then();
    }

    private Mono<Void> revokeBatch(String userId, String after) {
        // 删除事务已锁用户行；所有绑定生产者也先锁用户，再锁项目，扫描后不会插入遗漏的新绑定。
        // 稳定 ID 顺序、有界读取且不预取下一批，避免跨项目锁倒序及同事务连接上交叠查询。
        return batch(userId, after)
                .flatMap(
                        ids -> {
                            if (ids.isEmpty()) return Mono.empty();
                            return Flux.fromIterable(ids)
                                    .concatMap(
                                            projectId ->
                                                    access.lockProject(projectId)
                                                            .then(
                                                                    database.sql(
                                                                                    "update p_project set owner_id=null where id=:id and owner_id=:userId")
                                                                            .bind("id", projectId)
                                                                            .bind("userId", userId)
                                                                            .fetch()
                                                                            .rowsUpdated())
                                                            .then(
                                                                    database.sql(
                                                                                    "delete from p_project_member where project_id=:id and user_id=:userId")
                                                                            .bind("id", projectId)
                                                                            .bind("userId", userId)
                                                                            .fetch()
                                                                            .rowsUpdated())
                                                            // 非空 epoch
                                                            // 记录已初始化授权谱系；显式迁移也不能给同名新账号复活旧所有权。
                                                            .then(members.rotate(projectId)),
                                            1)
                                    .then(
                                            Mono.defer(
                                                    () ->
                                                            revokeBatch(
                                                                    userId,
                                                                    ids.get(ids.size() - 1))));
                        });
    }

    private Mono<List<String>> batch(String userId, String after) {
        return database.sql(
                        """
                select p.id from p_project p where p.id>:after and
                (p.owner_id=:userId or exists(select 1 from p_project_member m where m.project_id=p.id and m.user_id=:userId)
                or exists(select 1 from p_preview_session s where s.project_id=p.id and s.user_id=:userId)
                or (p.creator_id=:userId and (p.owner_id is null or p.owner_id !~ '[^[:space:]]')
                and (p.access_epoch is null or p.access_epoch !~ '[^[:space:]]')))
                order by p.id limit 100
                """)
                .bind("after", after)
                .bind("userId", userId)
                .map((row, metadata) -> row.get("id", String.class))
                .all()
                .collectList();
    }
}
