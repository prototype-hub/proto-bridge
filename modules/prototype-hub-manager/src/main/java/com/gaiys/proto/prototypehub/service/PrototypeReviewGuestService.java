package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeReviewChallengeEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewGuestEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewSessionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.web.request.ReviewGuestEnabledRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewGuestQueryRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewGuestResponse;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 已验证邮箱访客的分页与状态管理，不管理内部用户或投递通知。
 *
 * <p>复用公开验证流程的项目锁，使启停与验证码消费跨实例串行；会话和验证码撤销随状态一起提交。
 */
@Service
@RequiredArgsConstructor
public class PrototypeReviewGuestService {
    private final PrototypeProjectAccessService access;
    private final ReactiveRepository<PrototypeReviewGuestEntity, String> guests;
    private final ReactiveRepository<PrototypeReviewSessionEntity, String> sessions;
    private final ReactiveRepository<PrototypeReviewChallengeEntity, String> challenges;
    private final TransactionalOperator transactions;

    public Mono<PagerResult<ReviewGuestResponse>> query(
            String projectId, ReviewGuestQueryRequest input) {
        QueryParamEntity query =
                QueryParamEntity.newQuery()
                        .where(PrototypeReviewGuestEntity::getProjectId, projectId)
                        .orderByDesc(PrototypeReviewGuestEntity::getModifyTime)
                        .orderByAsc(PrototypeReviewGuestEntity::getId)
                        .getParam();
        query.doPaging(input.getPageIndex(), input.getPageSize());
        // 保留越界页，避免框架将其重定位到最后一页；COUNT 与结果不承诺并发写入强快照。
        query.setParallelPager(true);
        return access.requireProject(projectId, PrototypeProjectAccessService.Action.SHARE)
                .then(
                        QueryHelper.queryPager(
                                query, guests::createQuery, ReviewGuestResponse::from));
    }

    public Mono<ReviewGuestResponse> setEnabled(
            String projectId, String guestId, ReviewGuestEnabledRequest input) {
        return access.lockProject(projectId)
                .then(access.requireProject(projectId, PrototypeProjectAccessService.Action.SHARE))
                .then(
                        guests.createQuery()
                                .where(PrototypeReviewGuestEntity::getProjectId, projectId)
                                .and(PrototypeReviewGuestEntity::getId, guestId)
                                .fetch()
                                .singleOrEmpty())
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("review_guest")))
                .flatMap(guest -> changeEnabled(guest, input))
                .as(transactions::transactional);
    }

    private Mono<ReviewGuestResponse> changeEnabled(
            PrototypeReviewGuestEntity guest, ReviewGuestEnabledRequest input) {
        var current = ReviewGuestResponse.from(guest);
        if (current.configVersion() != input.expectedVersion()) return Mono.error(conflict());
        // 版本校验先于幂等判定；相同状态不换代，也不撤销仍然有效的验证流程。
        if (current.enabled() == input.enabled()) return Mono.just(current);
        if (current.configVersion() == Long.MAX_VALUE) return Mono.error(conflict());
        guest.setEnabled(input.enabled());
        guest.setConfigVersion(current.configVersion() + 1);
        guest.setModifyTime(System.currentTimeMillis());
        return guests.createUpdate()
                .set(PrototypeReviewGuestEntity::getEnabled, guest.getEnabled())
                .set(PrototypeReviewGuestEntity::getConfigVersion, guest.getConfigVersion())
                .set(PrototypeReviewGuestEntity::getModifyTime, guest.getModifyTime())
                .where(PrototypeReviewGuestEntity::getProjectId, guest.getProjectId())
                .and(PrototypeReviewGuestEntity::getId, guest.getId())
                .execute()
                // 恢复同样撤销旧凭据；限频账本和访客身份保留，不能通过反复启停退款发送额度。
                .then(
                        sessions.createDelete()
                                .where(
                                        PrototypeReviewSessionEntity::getProjectId,
                                        guest.getProjectId())
                                .and(PrototypeReviewSessionEntity::getGuestId, guest.getId())
                                .execute())
                .then(
                        challenges
                                .createDelete()
                                .where(
                                        PrototypeReviewChallengeEntity::getProjectId,
                                        guest.getProjectId())
                                .and(PrototypeReviewChallengeEntity::getEmail, guest.getEmail())
                                .execute())
                .thenReturn(ReviewGuestResponse.from(guest));
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.review_guest_config_conflict",
                "PROTOTYPE_HUB_REVIEW_GUEST_CONFIG_CONFLICT",
                409);
    }
}
