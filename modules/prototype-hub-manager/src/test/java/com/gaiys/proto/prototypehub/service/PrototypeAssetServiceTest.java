package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.storage.assets.PrototypeAssetFiles;
import com.gaiys.proto.prototypehub.storage.assets.PrototypeAssetFiles.Format;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest.FileEntry;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.request.AssetExportRequest;
import com.gaiys.proto.prototypehub.web.request.AssetQueryRequest;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hswebframework.web.authorization.Authentication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PrototypeAssetServiceTest {
    @TempDir Path root;

    @Test
    void listSearchIsLiteralCaseInsensitivePagedAndRetainsFixedRevisionIdentity() throws Exception {
        try (var work = new FileWork()) {
            var fixture = new Fixture(work);
            var query = new AssetQueryRequest();
            query.setSearch("A_%");
            query.setPageSize(1);
            var page = fixture.service.list("v", "old", query).block(Duration.ofSeconds(5));
            assertNotNull(page);
            assertEquals("old", page.revisionId());
            assertEquals(1, page.total());
            assertEquals(2, page.assetCount());
            assertEquals("a_%.svg", page.data().getFirst().path());
            query.setPageIndex(4);
            assertTrue(
                    fixture.service
                            .list("v", "old", query)
                            .block(Duration.ofSeconds(5))
                            .data()
                            .isEmpty());
        }
    }

    @Test
    void finalRevocationAfterCopyReturnsNoBodyAndClosesTheAttempt() throws Exception {
        try (var work = new FileWork()) {
            var fixture = new Fixture(work);
            var calls = new AtomicInteger();
            when(fixture.access.actor("u"))
                    .thenAnswer(
                            ignored ->
                                    Mono.defer(
                                            () ->
                                                    calls.incrementAndGet() == 3
                                                            ? Mono.error(
                                                                    PrototypeProjectAccessService
                                                                            .roleDenied())
                                                            : Mono.just(fixture.actor)));
            var response = new MockServerHttpResponse();
            StepVerifier.create(
                            fixture.service.export(
                                    "v", "old", fixture.input(), Format.FILE, response))
                    .expectErrorMatches(
                            error ->
                                    error instanceof PrototypeHubException failure
                                            && failure.getStatus() == 403)
                    .verify(Duration.ofSeconds(5));
            assertFalse(response.isCommitted());
            assertEquals(3, calls.get());
        }
    }

    @Test
    void responseOwnsTempUntilCancelledAndFourBusySlotsAreReleasedForNextExport() throws Exception {
        try (var work = new FileWork()) {
            var fixture = new Fixture(work);
            var outputs = new ArrayList<Path>();
            var closed = new CountDownLatch(4);
            var prepared = new CountDownLatch(4);
            doAnswer(
                            invocation -> {
                                var attempt =
                                        spy(
                                                (PrototypeAssetFiles.Attempt)
                                                        invocation.callRealMethod());
                                doAnswer(
                                                finish -> {
                                                    Path path = (Path) finish.callRealMethod();
                                                    synchronized (outputs) {
                                                        outputs.add(path);
                                                    }
                                                    return path;
                                                })
                                        .when(attempt)
                                        .finish();
                                doAnswer(
                                                close -> {
                                                    try {
                                                        return close.callRealMethod();
                                                    } finally {
                                                        closed.countDown();
                                                    }
                                                })
                                        .when(attempt)
                                        .close();
                                return attempt;
                            })
                    .when(fixture.files)
                    .attempt(any(), anyList(), any());
            var subscriptions = new ArrayList<Disposable>();
            try {
                for (int i = 0; i < 4; i++) {
                    var response = new MockServerHttpResponse();
                    response.setWriteHandler(
                            body ->
                                    body.doOnNext(
                                                    buffer ->
                                                            org.springframework.core.io.buffer
                                                                    .DataBufferUtils.release(
                                                                    buffer))
                                            .then(
                                                    Mono.defer(
                                                            () -> {
                                                                prepared.countDown();
                                                                return Mono.never();
                                                            })));
                    subscriptions.add(
                            fixture.service
                                    .export("v", "old", fixture.input(), Format.FILE, response)
                                    .subscribe());
                }
                assertTrue(prepared.await(5, TimeUnit.SECONDS));
                assertEquals(4, outputs.size());
                for (Path path : outputs) assertTrue(Files.exists(path));
                StepVerifier.create(
                                fixture.service.export(
                                        "v",
                                        "old",
                                        fixture.input(),
                                        Format.FILE,
                                        new MockServerHttpResponse()))
                        .expectErrorMatches(
                                error ->
                                        error instanceof PrototypeHubException failure
                                                && failure.getStatus() == 429)
                        .verify(Duration.ofSeconds(5));
            } finally {
                subscriptions.forEach(Disposable::dispose);
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            for (Path path : outputs) assertFalse(Files.exists(path.getParent()));
            fixture.service
                    .export("v", "old", fixture.input(), Format.FILE, new MockServerHttpResponse())
                    .block(Duration.ofSeconds(5));
        }
    }

    private final class Fixture {
        final PrototypeProjectAccessService access = mock(PrototypeProjectAccessService.class);
        final Actor actor;
        final PrototypeAssetFiles files;
        final PrototypeAssetService service;
        final PrototypeRevisionEntity revision = new PrototypeRevisionEntity();

        Fixture(FileWork work) throws Exception {
            var mapper = new ObjectMapper();
            var properties = new PrototypeHubStorageProperties();
            properties.setRoot(root);
            files = spy(new PrototypeAssetFiles(properties, mapper));
            byte[] bytes = "<svg/>".getBytes(StandardCharsets.UTF_8);
            String hash =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var entries =
                    List.of(
                            new FileEntry("a_%.svg", bytes.length, hash, "image/svg+xml"),
                            new FileEntry("b.svg", bytes.length, hash, "image/svg+xml"));
            for (var entry : entries) {
                Path path = root.resolve("projects/p/versions/s/site").resolve(entry.path());
                Files.createDirectories(path.getParent());
                Files.write(path, bytes);
            }
            revision.setId("old");
            revision.setVersionId("v");
            revision.setProjectId("p");
            revision.setStoragePath("projects/p/versions/s/site");
            revision.setManifestJson(
                    mapper.writeValueAsString(
                            new PrototypeManifest(1, "index.html", entries, List.of())));
            revision.setManifestSha256(
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    revision.getManifestJson()
                                                            .getBytes(StandardCharsets.UTF_8))));
            var authentication = mock(Authentication.class);
            when(authentication.hasPermission("prototype-hub-version", "query")).thenReturn(true);
            actor = new Actor("u", authentication, false);
            when(access.current()).thenReturn(Mono.just(actor));
            when(access.actor("u")).thenReturn(Mono.just(actor));
            var version = new PrototypeVersionEntity();
            version.setId("v");
            version.setProjectId("p");
            version.setCurrentRevisionId("new");
            when(access.requireVersion(
                            any(),
                            eq("v"),
                            eq(PrototypeProjectAccessService.Action.READ),
                            eq(false)))
                    .thenReturn(Mono.just(version));
            var revisions = mock(PrototypeRevisionService.class);
            when(revisions.requireManifest(version, "old")).thenReturn(Mono.just(revision));
            service =
                    new PrototypeAssetService(
                            access, revisions, files, work, ObservationRegistry.NOOP);
        }

        AssetExportRequest input() {
            var input = new AssetExportRequest();
            input.setExpectedManifestSha256(revision.getManifestSha256());
            input.setPaths(List.of("a_%.svg"));
            return input;
        }
    }
}
