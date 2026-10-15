package com.gaiys.proto.system.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveDelete;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionUserService;
import org.springframework.transaction.reactive.TransactionalOperator;

/** 保留官方成员维度服务和事件行为，为原生删除 DSL 的完整执行链提供事务。 */
public class NativeDimensionUserService extends DefaultDimensionUserService {
    private final TransactionalOperator transactions;

    public NativeDimensionUserService(TransactionalOperator transactions) {
        this.transactions = transactions;
    }

    @Override
    public ReactiveDelete createDelete() {
        // 原生解绑直接执行 DSL；包住 before、SQL 与 after 清理，失败或取消时一起回滚。
        // REQUIRED 事务加入业务入口已有事务，不更换官方缓存和成员事件处理。
        return super.createDelete()
                .onExecute((delete, operation) -> transactions.transactional(operation));
    }
}
