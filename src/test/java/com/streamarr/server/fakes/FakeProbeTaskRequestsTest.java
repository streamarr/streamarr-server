package com.streamarr.server.fakes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeState;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
