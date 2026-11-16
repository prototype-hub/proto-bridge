package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeSearchService;
import com.gaiys.proto.prototypehub.web.request.PrototypeSearchRequest;
import com.gaiys.proto.prototypehub.web.response.PrototypeSearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 只读成员搜索沿既有版本query动作，项目READ/EDIT仍由实时数据范围约束。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "权限范围内基础搜索")
@RequestMapping("/prototype-hub/search")
public class PrototypeSearchController {
    private final PrototypeSearchService search;

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "数据库分页搜索已收录文案、资料元数据或有权编辑的评审消息")
    public Mono<PagerResult<PrototypeSearchResponse>> query(
            @Valid @RequestBody PrototypeSearchRequest input) {
        return search.query(input);
    }
}
