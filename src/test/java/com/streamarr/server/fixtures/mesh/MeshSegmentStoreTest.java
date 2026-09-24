package com.streamarr.server.fixtures.mesh;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Mesh Segment Store Tests")
class MeshSegmentStoreTest {

  @Test
  @DisplayName(
      "Should return the initialization and first segments when later segments arrive before it")
  void shouldReturnInitializationAndFirstSegmentsWhenLaterSegmentsArriveBeforeIt()
      throws Exception {
    var store = new MeshSegmentStore();
    var sessionId = UUID.randomUUID();
    store.storeSegment(sessionId, "init.mp4", "init ".getBytes(StandardCharsets.UTF_8));
    store.storeSegment(sessionId, "segment1.m4s", "later".getBytes(StandardCharsets.UTF_8));
    store.storeSegment(sessionId, "segment0.m4s", "first".getBytes(StandardCharsets.UTF_8));

    assertThat(store.awaitFirstDecodableMedia(sessionId))
        .isEqualTo("init first".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("Should discard the completed segment when its session is deleted")
  void shouldDiscardTheCompletedSegmentWhenItsSessionIsDeleted() throws Exception {
    var store = new MeshSegmentStore();
    var sessionId = UUID.randomUUID();
    store.storeSegment(sessionId, "init.mp4", "init ".getBytes(StandardCharsets.UTF_8));
    store.storeSegment(sessionId, "segment0.m4s", "old".getBytes(StandardCharsets.UTF_8));
    store.deleteSession(sessionId);
    store.storeSegment(sessionId, "init.mp4", "init ".getBytes(StandardCharsets.UTF_8));
    store.storeSegment(sessionId, "segment0.m4s", "next".getBytes(StandardCharsets.UTF_8));

    assertThat(store.awaitFirstDecodableMedia(sessionId))
        .isEqualTo("init next".getBytes(StandardCharsets.UTF_8));
  }
}
