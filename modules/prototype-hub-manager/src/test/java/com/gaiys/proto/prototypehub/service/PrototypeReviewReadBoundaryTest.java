package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewGuestEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewInvitationEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.enums.ReviewVerificationLevel;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys;
import com.gaiys.proto.prototypehub.service.keys.PrototypeShareKeys;
import com.gaiys.proto.prototypehub.service.notification.NotificationGateway;
import com.gaiys.proto.prototypehub.storage.scanner.PrototypePackageQualityScanner;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.request.ReviewChallengeRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewGuestEnabledRequest;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class PrototypeReviewReadBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"share", "version", "project", "revision", "invitation", "guest"})
    void challengeWaitsForScopeConnectionReleaseBeforePreparingBcrypt(String stage) {
        var row = new PrototypeRevisionEntity();
        row.setId("r");
        var version = new PrototypeVersionEntity();
        version.setId("v");
        version.setProjectId("p");
        version.setCurrentRevisionId("r");
        var share = new PrototypeShareEntity();
        share.setId("s");
        share.setProjectId("p");
        share.setVersionId("v");
        share.setRevisionId("r");
        share.setEnabled(true);
        share.setReviewEnabled(true);
        share.setAccessKey("generation");
        share.setConfigVersion(1L);
        var revisionQuery = mock(ReactiveQuery.class, RETURNS_SELF);
        var revisionRepository = mock(ReactiveRepository.class);
        when(revisionRepository.createQuery()).thenReturn(revisionQuery);
        var shareQuery = mock(ReactiveQuery.class, RETURNS_SELF);
        var shareRepository = mock(ReactiveRepository.class);
        when(shareRepository.createQuery()).thenReturn(shareQuery);
        when(shareQuery.fetchOne()).thenReturn(Mono.just(share));
        when(shareQuery.fetch()).thenReturn(Flux.just(share));
        var project = new PrototypeProjectEntity();
        project.setId("p");
        var projects = mock(ReactiveRepository.class);
        var versions = mock(ReactiveRepository.class);
        var projectQuery = mock(ReactiveQuery.class, RETURNS_SELF);
        var versionQuery = mock(ReactiveQuery.class, RETURNS_SELF);
        when(projects.createQuery()).thenReturn(projectQuery);
        when(versions.createQuery()).thenReturn(versionQuery);
        var access =
                new PrototypeProjectAccessService(
                        projects, versions, null, null, null, null, null, null, null, null);
        var work = mock(FileWork.class);
        var preparing = new AtomicBoolean();
        when(work.call(any()))
                .thenAnswer(
                        ignored ->
                                Mono.defer(
                                        () -> {
                                            preparing.set(true);
                                            return Mono.never();
                                        }));
        var release = Sinks.<Void>one();
        var releasing = new AtomicBoolean();
        Mono<Void> cleanup = release.asMono();
        Flux<PrototypeRevisionEntity> revisionRows =
                gate(row, stage.equals("revision"), releasing, cleanup);
        Flux<PrototypeShareEntity> shareRows =
                gate(share, stage.equals("share"), releasing, cleanup);
        Flux<PrototypeVersionEntity> versionRows =
                gate(version, stage.equals("version"), releasing, cleanup);
        Flux<PrototypeProjectEntity> projectRows =
                gate(project, stage.equals("project"), releasing, cleanup);
        when(revisionQuery.fetch()).thenReturn(revisionRows);
        when(revisionQuery.fetchOne()).thenAnswer(ignored -> revisionRows.take(1).singleOrEmpty());
        when(shareQuery.fetch()).thenReturn(shareRows);
        when(shareQuery.fetchOne()).thenAnswer(ignored -> shareRows.take(1).singleOrEmpty());
        when(versionQuery.fetch()).thenReturn(versionRows);
        when(versions.findById("v")).thenAnswer(ignored -> versionRows.take(1).singleOrEmpty());
        when(projectQuery.fetch()).thenReturn(projectRows);
        when(projects.findById("p")).thenAnswer(ignored -> projectRows.take(1).singleOrEmpty());
        var revisions =
                new PrototypeRevisionService(
                        work,
                        ObservationRegistry.NOOP,
                        mock(PrototypePackageQualityScanner.class)) {
                    @Override
                    public ReactiveRepository<PrototypeRevisionEntity, String> getRepository() {
                        return revisionRepository;
                    }
                };
        var shares =
                new PrototypeShareService(
                        mock(PrototypeProjectService.class),
                        mock(PrototypeVersionService.class),
                        work,
                        mock(TransactionalOperator.class),
                        access,
                        revisions,
                        mock(NotificationGateway.class),
                        mock(PrototypeInstallationService.class)) {
                    @Override
                    public ReactiveRepository<PrototypeShareEntity, String> getRepository() {
                        return shareRepository;
                    }
                };
        var notifications = mock(NotificationGateway.class);
        when(notifications.available()).thenReturn(true);
        var invitation = new PrototypeReviewInvitationEntity();
        invitation.setProjectId("p");
        invitation.setVersionId("v");
        invitation.setShareId("s");
        invitation.setRevisionId("r");
        invitation.setEmail("a@example.invalid");
        invitation.setShareConfigVersion(1L);
        invitation.setShareGeneration(PrototypeReviewKeys.digest("generation"));
        invitation.setGuestConfigVersion(0L);
        invitation.setExpiresAt(System.currentTimeMillis() + 60_000);
        var guest = new PrototypeReviewGuestEntity();
        guest.setEnabled(true);
        var invitationQuery = mock(ReactiveQuery.class, RETURNS_SELF);
        var invitationRepository = mock(ReactiveRepository.class);
        when(invitationRepository.createQuery()).thenReturn(invitationQuery);
        when(invitationQuery.fetch())
                .thenReturn(gate(invitation, stage.equals("invitation"), releasing, cleanup));
        var guestQuery = mock(ReactiveQuery.class, RETURNS_SELF);
        var guestRepository = mock(ReactiveRepository.class);
        when(guestRepository.createQuery()).thenReturn(guestQuery);
        var guestRows = gate(guest, stage.equals("guest"), releasing, cleanup);
        when(guestQuery.fetch()).thenReturn(guestRows);
        when(guestRepository.findById(anyString()))
                .thenAnswer(ignored -> guestRows.take(1).singleOrEmpty());
        var invitations =
                new PrototypeReviewInvitationService(
                        access, shares, invitationRepository, guestRepository, null, null);
        var service =
                new PrototypeReviewService(
                        shares,
                        access,
                        mock(ReactiveRepository.class),
                        mock(ReactiveRepository.class),
                        mock(ReactiveRepository.class),
                        mock(ReactiveRepository.class),
                        mock(ReactiveRepository.class),
                        invitations,
                        notifications,
                        work,
                        mock(TransactionalOperator.class),
                        ObservationRegistry.NOOP);
        var input =
                new ReviewChallengeRequest(
                        "r",
                        PrototypeShareKeys.derive(share, "r"),
                        "a@example.invalid",
                        "Name",
                        null,
                        stage.equals("invitation") || stage.equals("guest")
                                ? "x".repeat(43)
                                : null);
        StepVerifier.create(service.challenge("token", input, "cookie"))
                .then(
                        () -> {
                            assertTrue(releasing.get());
                            assertFalse(preparing.get(), "BCrypt必须等待授权修订查询的异步连接归还");
                            release.tryEmitEmpty();
                        })
                .then(() -> assertTrue(preparing.get()))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    private static <T> Flux<T> gate(
            T row, boolean gated, AtomicBoolean releasing, Mono<Void> cleanup) {
        if (!gated) return Flux.just(row);
        return Flux.usingWhen(
                Mono.just("connection"),
                ignored -> Flux.just(row),
                ignored -> {
                    releasing.set(true);
                    return cleanup;
                },
                (ignored, error) -> cleanup,
                ignored -> {
                    releasing.set(true);
                    return cleanup;
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidExplicitInvitationIsRejectedBeforeSmtpAvailabilityOrBcrypt(boolean mailAvailable) {
        var share = new PrototypeShareEntity();
        share.setId("s");
        share.setProjectId("p");
        share.setVersionId("v");
        share.setRevisionId("r");
        share.setEnabled(true);
        share.setReviewEnabled(true);
        share.setAccessKey("generation");
        var shares = mock(PrototypeShareService.class);
        when(shares.requireActive("token")).thenReturn(Mono.just(share));
        when(shares.requireRevisionAccess(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new PrototypeRevisionEntity()));
        var invitations = mock(PrototypeReviewInvitationService.class);
        when(invitations.requireToken(any(), eq("r"), eq("a@example.invalid"), anyString()))
                .thenReturn(Mono.error(PrototypeReviewInvitationService.invalid()));
        var notifications = mock(NotificationGateway.class);
        when(notifications.available()).thenReturn(mailAvailable);
        var work = mock(FileWork.class);
        var executed = new AtomicBoolean();
        when(work.call(any()))
                .thenReturn(
                        Mono.defer(
                                () -> {
                                    executed.set(true);
                                    return Mono.empty();
                                }));
        var service =
                new PrototypeReviewService(
                        shares,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        invitations,
                        notifications,
                        work,
                        null,
                        ObservationRegistry.NOOP);
        var input =
                new ReviewChallengeRequest(
                        "r",
                        PrototypeShareKeys.derive(share, "r"),
                        "a@example.invalid",
                        "Name",
                        null,
                        "x".repeat(43));
        StepVerifier.create(service.challenge("token", input, "cookie"))
                .expectErrorSatisfies(
                        error -> assertEquals(403, ((PrototypeHubException) error).getStatus()))
                .verify(Duration.ofSeconds(5));
        assertFalse(executed.get());
        verify(notifications, never()).available();
        verify(notifications, never()).sendVerification(anyString(), anyString());
    }

    @Test
    void expiredGuestCutoffIsBuiltOnlyAfterProjectLockCompletes() {
        var access = mock(PrototypeProjectAccessService.class);
        var lock = Sinks.<Void>one();
        when(access.lockProject("p")).thenReturn(lock.asMono());
        when(access.requireProject("p", PrototypeProjectAccessService.Action.SHARE))
                .thenReturn(Mono.empty());
        var unverified = mock(ReactiveRepository.class);
        var query = mock(ReactiveQuery.class, RETURNS_SELF);
        when(unverified.createQuery()).thenReturn(query);
        // 已过期的行由订阅后的期限条件排除。
        when(query.fetch()).thenReturn(Flux.empty());
        var transactions = mock(TransactionalOperator.class);
        when(transactions.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var service =
                new PrototypeReviewGuestService(access, null, unverified, null, null, transactions);
        var result =
                service.setEnabled(
                        "p",
                        "g",
                        new ReviewGuestEnabledRequest(true, 0L),
                        ReviewVerificationLevel.unverified);
        verifyNoInteractions(unverified);
        StepVerifier.create(result)
                .then(
                        () -> {
                            verifyNoInteractions(unverified);
                            lock.tryEmitEmpty();
                        })
                .expectErrorSatisfies(
                        error -> {
                            assertInstanceOf(PrototypeHubException.class, error);
                            assertEquals(404, ((PrototypeHubException) error).getStatus());
                        })
                .verify(Duration.ofSeconds(5));
        verify(unverified).createQuery();
    }

    @Test
    void scheduledCleanupDoesNotTouchDatabaseBeforeSubscriptionAndRebuildsEachBatch() {
        var challenges = mock(ReactiveRepository.class);
        var sessions = mock(ReactiveRepository.class);
        var rates = mock(ReactiveRepository.class);
        var unverified = mock(ReactiveRepository.class);
        var query = mock(ReactiveQuery.class, RETURNS_SELF);
        when(query.fetch()).thenReturn(Flux.empty());
        when(challenges.createQuery()).thenReturn(query);
        when(sessions.createQuery()).thenReturn(query);
        when(rates.createQuery()).thenReturn(query);
        when(unverified.createQuery()).thenReturn(query);
        var invitations = mock(PrototypeReviewInvitationService.class);
        when(invitations.cleanupExpired(anyLong())).thenReturn(Mono.empty());
        var service =
                new PrototypeReviewService(
                        null,
                        null,
                        null,
                        unverified,
                        challenges,
                        sessions,
                        rates,
                        invitations,
                        null,
                        null,
                        null,
                        null);
        Mono<Void> scheduled = assertDoesNotThrow(service::cleanupExpired);
        verifyNoInteractions(challenges, sessions, rates, unverified);
        StepVerifier.create(scheduled).verifyComplete();
        StepVerifier.create(scheduled).verifyComplete();
        verify(challenges, times(2)).createQuery();
        verify(sessions, times(2)).createQuery();
        verify(rates, times(2)).createQuery();
        verify(unverified, times(2)).createQuery();
    }
}
