package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeStatisticsService;
import com.gaiys.proto.prototypehub.web.response.PrototypeStatisticsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 复用项目查询动作；各额外指标仍在服务端与版本、分享动作及项目角色取交集。 */
@RestController
@Authorize
@Resource(id = "prototype-hub-project", name = "原型项目管理")
@Tag(name = "原型统计概览")
@RequiredArgsConstructor
public class PrototypeStatisticsController {
    private final PrototypeStatisticsService statistics;

    @GetMapping("/prototype-hub/statistics")
    @QueryAction
    @Operation(summary = "读取当前授权范围的统计概览", description = "7或30个Asia/Shanghai自然日；缺少对应动作时指标为null，不是零")
    public Mono<PrototypeStatisticsResponse> summary(@RequestParam(defaultValue = "7") int days) {
        if (days != 7 && days != 30)
            throw PrototypeHubException.badRequest(
                    "error.prototype-hub.statistics_days_invalid",
                    "PROTOTYPE_HUB_STATISTICS_DAYS_INVALID");
        return statistics.summary(days);
    }
}
