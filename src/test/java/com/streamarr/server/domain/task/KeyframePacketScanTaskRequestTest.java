package com.streamarr.server.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Keyframe packet scan task request")
class KeyframePacketScanTaskRequestTest {

  private static final SourceFileSnapshot SNAPSHOT = new SourceFileSnapshot(1, Instant.EPOCH);
  private static final String SOURCE = "file:///library/movie.mkv";

  @Test
  @DisplayName("Should pair the snapshot with the probe version when a caller reads its inputs")
  void shouldPairTheSnapshotWithTheProbeVersionWhenACallerReadsItsInputs() {
    var request = builder().build();

    assertThat(request.inputs()).isEqualTo(new ProbeInputs(SNAPSHOT, 3));
  }

  @Test
  @DisplayName("Should reject a request when its media file is missing")
  void shouldRejectARequestWhenItsMediaFileIsMissing() {
    assertThatNullPointerException()
        .isThrownBy(() -> new KeyframePacketScanTaskRequest(null, SOURCE, SNAPSHOT, 3, 0))
        .withMessageContaining("mediaFileId");
  }

  @Test
  @DisplayName("Should reject a request when its source path is missing")
  void shouldRejectARequestWhenItsSourcePathIsMissing() {
    var mediaFileId = UUID.randomUUID();

    assertThatNullPointerException()
        .isThrownBy(() -> new KeyframePacketScanTaskRequest(mediaFileId, null, SNAPSHOT, 3, 0))
        .withMessageContaining("filepathUri");
  }

  @Test
  @DisplayName("Should reject a request when its snapshot is missing")
  void shouldRejectARequestWhenItsSnapshotIsMissing() {
    var mediaFileId = UUID.randomUUID();

    assertThatNullPointerException()
        .isThrownBy(() -> new KeyframePacketScanTaskRequest(mediaFileId, SOURCE, null, 3, 0))
        .withMessageContaining("snapshot");
  }

  private static KeyframePacketScanTaskRequest.KeyframePacketScanTaskRequestBuilder builder() {
    return KeyframePacketScanTaskRequest.builder()
        .mediaFileId(UUID.randomUUID())
        .filepathUri(SOURCE)
        .snapshot(SNAPSHOT)
        .probeVersion(3);
  }
}
