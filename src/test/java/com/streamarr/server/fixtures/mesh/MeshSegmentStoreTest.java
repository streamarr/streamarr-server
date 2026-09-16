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
  @DisplayName("Should return the first segment when later segments arrive before it")
  void shouldReturnTheFirstSegmentWhenLaterSegmentsArriveBeforeIt() throws Exception {
    var store = new MeshSegmentStore();
    var sessionId = UUID.randomUUID();
    store.storeSegment(sessionId, "segment1.ts", "later".getBytes(StandardCharsets.UTF_8));
    var first = "first".getBytes(StandardCharsets.UTF_8);
    store.storeSegment(sessionId, "segment0.ts", first);

    assertThat(store.awaitFirstSegment(sessionId)).isEqualTo(first);
  }

  @Test
  @DisplayName("Should discard the completed segment when its session is deleted")
  void shouldDiscardTheCompletedSegmentWhenItsSessionIsDeleted() throws Exception {
    var store = new MeshSegmentStore();
    var sessionId = UUID.randomUUID();
    store.storeSegment(sessionId, "segment0.ts", "old".getBytes(StandardCharsets.UTF_8));
    store.deleteSession(sessionId);
    var next = "next".getBytes(StandardCharsets.UTF_8);
    store.storeSegment(sessionId, "segment0.ts", next);

    assertThat(store.awaitFirstSegment(sessionId)).isEqualTo(next);
  }
}
