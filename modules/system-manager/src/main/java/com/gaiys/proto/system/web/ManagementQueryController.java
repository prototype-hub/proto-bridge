package com.gaiys.proto.system.web;

import com.gaiys.proto.system.service.support.ManagementSupport;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.crud.web.reactive.ReactiveServiceQueryController;
import org.springframework.web.bind.annotation.GetMapping;
import reactor.core.publisher.Mono;

/** 管理端标准分页查询边界；GET 与框架 POST 委托共用校验，合法查询仍由 hsweb 执行。 */
public interface ManagementQueryController<E, K> extends ReactiveServiceQueryController<E, K> {
    @Override
    @GetMapping("/_query")
    @QueryAction
    default Mono<PagerResult<E>> queryPager(QueryParamEntity query) {
        // 在框架按 total 选择查询分支前校验，空表和客户端提供 total 时也保持相同边界。
        ManagementSupport.limit(query);
        return ReactiveServiceQueryController.super.queryPager(query);
    }
}
