package com.streamarr.server.fakes;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Fake probe repository concurrency")
class FakeMediaFileContainerInfoRepositoryConcurrencyTest {

  private static final int ATTEMPTS = 50_000;
  private static final SourceFileSnapshot OLD_SOURCE =
      new SourceFileSnapshot(10, Instant.parse("2026-09-10T00:00:00Z"));
  private static final SourceFileSnapshot NEW_SOURCE =
      new SourceFileSnapshot(20, Instant.parse("2026-09-11T00:00:00Z"));

  @Test
  @DisplayName("Should retain a matching publication when its request is recorded concurrently")
  void shouldRetainAMatchingPublicationWhenItsRequestIsRecordedConcurrently() throws Exception {
    var mediaFileId = UUID.randomUUID();
    var previous = publication(mediaFileId).snapshot(OLD_SOURCE).build();
    var replacement = publication(mediaFileId).snapshot(NEW_SOURCE).build();
    var requested = new ProbeInputs(NEW_SOURCE, ProbeVersion.CURRENT);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
        var repository = new FakeMediaFileContainerInfoRepository();
        assertThat(repository.publish(previous)).isTrue();

        runConcurrently(
            executor,
            () -> assertThat(repository.recordProbeRequest(mediaFileId, requested)).isTrue(),
            () -> assertThat(repository.publish(replacement)).isTrue());

        assertThat(repository.findByMediaFileId(mediaFileId))
            .as("Both serial orders retain the new outcome; concurrent attempt %s", attempt)
            .hasValueSatisfying(row -> assertThat(row.getSnapshot()).isEqualTo(NEW_SOURCE));
      }
    }
  }

  @Test
  @DisplayName("Should discard an old publication when a changed source is requested concurrently")
  void shouldDiscardAnOldPublicationWhenAChangedSourceIsRequestedConcurrently() throws Exception {
    var mediaFileId = UUID.randomUUID();
    var previous = publication(mediaFileId).snapshot(OLD_SOURCE).build();
    var requested = new ProbeInputs(NEW_SOURCE, ProbeVersion.CURRENT);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
        var repository = new FakeMediaFileContainerInfoRepository();
        assertThat(
                repository.recordProbeRequest(
                    mediaFileId, new ProbeInputs(OLD_SOURCE, ProbeVersion.CURRENT)))
            .isTrue();

        runConcurrently(
            executor,
            () -> repository.publish(previous),
            () -> assertThat(repository.recordProbeRequest(mediaFileId, requested)).isTrue());

        assertThat(repository.findByMediaFileId(mediaFileId))
            .as("Both serial orders discard the old outcome; concurrent attempt %s", attempt)
            .isEmpty();
      }
    }
  }

  @ParameterizedTest(name = "request first: {0}")
  @ValueSource(booleans = {true, false})
  @DisplayName("Should retain a matching publication when operations are sequential")
  void shouldRetainAMatchingPublicationWhenOperationsAreSequential(boolean requestFirst) {
    var mediaFileId = UUID.randomUUID();
    var repository = new FakeMediaFileContainerInfoRepository();
    assertThat(repository.publish(publication(mediaFileId).snapshot(OLD_SOURCE).build())).isTrue();

    runSequentially(
        requestFirst,
        () ->
            assertThat(
                    repository.recordProbeRequest(
                        mediaFileId, new ProbeInputs(NEW_SOURCE, ProbeVersion.CURRENT)))
                .isTrue(),
        () ->
            assertThat(repository.publish(publication(mediaFileId).snapshot(NEW_SOURCE).build()))
                .isTrue());

    assertThat(repository.findByMediaFileId(mediaFileId))
        .hasValueSatisfying(row -> assertThat(row.getSnapshot()).isEqualTo(NEW_SOURCE));
  }

  @ParameterizedTest(name = "request first: {0}")
  @ValueSource(booleans = {true, false})
  @DisplayName("Should discard an old publication when operations are sequential")
  void shouldDiscardAnOldPublicationWhenOperationsAreSequential(boolean requestFirst) {
    var mediaFileId = UUID.randomUUID();
    var repository = new FakeMediaFileContainerInfoRepository();
    assertThat(
            repository.recordProbeRequest(
                mediaFileId, new ProbeInputs(OLD_SOURCE, ProbeVersion.CURRENT)))
        .isTrue();

    runSequentially(
        requestFirst,
        () ->
            assertThat(
                    repository.recordProbeRequest(
                        mediaFileId, new ProbeInputs(NEW_SOURCE, ProbeVersion.CURRENT)))
                .isTrue(),
        () ->
            assertThat(repository.publish(publication(mediaFileId).snapshot(OLD_SOURCE).build()))
                .isEqualTo(!requestFirst));

    assertThat(repository.findByMediaFileId(mediaFileId)).isEmpty();
  }

  private static void runSequentially(
      boolean requestFirst, Runnable request, Runnable publication) {
    if (requestFirst) {
      request.run();
      publication.run();
      return;
    }

    publication.run();
    request.run();
  }

  private static ProbePublication.ProbePublicationBuilder publication(UUID mediaFileId) {
    return ProbePublication.builder()
        .mediaFileId(mediaFileId)
        .probeVersion(ProbeVersion.CURRENT)
        .outcome(new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA));
  }

  private static void runConcurrently(ExecutorService executor, Runnable first, Runnable second)
      throws Exception {
    var start = new CyclicBarrier(2);
    var firstExecution =
        executor.submit(
            () -> {
              start.await(5, TimeUnit.SECONDS);
              first.run();
              return true;
            });
    var secondExecution =
        executor.submit(
            () -> {
              start.await(5, TimeUnit.SECONDS);
              second.run();
              return true;
            });
    firstExecution.get(5, TimeUnit.SECONDS);
    secondExecution.get(5, TimeUnit.SECONDS);
  }
}
