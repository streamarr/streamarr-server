package com.streamarr.server.fakes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeState;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.support.BoundedTask;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Fake Probe Task Requests Tests")
class FakeProbeTaskRequestsTest {

  private final FakeMediaFileContainerInfoRepository outcomes =
      new FakeMediaFileContainerInfoRepository();
  private final FakeProbeTaskRequests requests = new FakeProbeTaskRequests(outcomes);
  private final UUID mediaFileId = UUID.randomUUID();

  private static final Duration BOUND = Duration.ofSeconds(5);

  @Test
  @DisplayName("Should keep the earlier probe state when the dispatcher rejects a request")
  void shouldKeepTheEarlierProbeStateWhenTheDispatcherRejectsARequest() {
    var earlier = request(10);
    requests.request(earlier);
    requests.fail(earlier, ItemFailureReason.SOURCE_INACCESSIBLE, Instant.EPOCH);
    var before = stateOf();
    var rejection = new IllegalStateException("probe queue unavailable");
    requests.dispatchWith(
        _ -> {
          throw rejection;
        });
    var changed = request(11);

    assertThatThrownBy(() -> requests.request(changed)).isSameAs(rejection);

    assertThat(stateOf()).isEqualTo(before);
    assertThat(requests.requests()).containsExactly(earlier);
  }

  @Test
  @DisplayName("Should keep a later request when an earlier request for the same file is rejected")
  void shouldKeepALaterRequestWhenAnEarlierRequestForTheSameFileIsRejected() throws Exception {
    var earlier = request(10);
    var later = request(11);
    var dispatchingEarlier = new CountDownLatch(1);
    var rejectEarlier = new CountDownLatch(1);
    requests.dispatchWith(
        dispatched -> {
          if (dispatched.equals(earlier)) {
            dispatchingEarlier.countDown();
            awaitRelease(rejectEarlier);
            throw new IllegalStateException("probe queue unavailable");
          }
        });

    try (var rejected = BoundedTask.start(() -> requests.request(earlier))) {
      assertThat(dispatchingEarlier.await(5, TimeUnit.SECONDS)).isTrue();
      try (var accepted = BoundedTask.start(() -> requests.request(later))) {
        outcomes.awaitBlockedWrites(mediaFileId, 1, BOUND);
        rejectEarlier.countDown();

        assertThatThrownBy(() -> rejected.await(BOUND)).isInstanceOf(IllegalStateException.class);
        accepted.await(BOUND);
      }
    }

    assertThat(stateOf().requested()).contains(later.inputs());
    assertThat(requests.requests()).containsExactly(later);
  }

  // An interrupted dispatcher stops instead of going on to reject the request.
  private static void awaitRelease(CountDownLatch release) {
    try {
      assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new CancellationException("dispatcher interrupted");
    }
  }

  private ProbeState stateOf() {
    return outcomes.findProbeStates(List.of(mediaFileId)).getFirst();
  }

  private ProbeTaskRequest request(long size) {
    return ProbeTaskRequest.builder()
        .mediaFileId(mediaFileId)
        .libraryId(UUID.randomUUID())
        .filepathUri("file:///movies/movie.mkv")
        .snapshot(new SourceFileSnapshot(size, Instant.EPOCH))
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }
}
