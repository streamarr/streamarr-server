package com.streamarr.server.repositories.streaming;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Stream Session Completion Tests")
class StreamSessionCompletionTest {

  @Test
  @DisplayName("Should reject a completion when it names no stream session")
  void shouldRejectACompletionWhenItNamesNoStreamSession() {
    var completion = completionBuilder();

    assertThatThrownBy(() -> completion.streamSessionId(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("streamSessionId");
  }

  @Test
  @DisplayName("Should reject a completion when it names no Profile")
  void shouldRejectACompletionWhenItNamesNoProfile() {
    var completion = completionBuilder();

    assertThatThrownBy(() -> completion.profileId(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("profileId");
  }

  @Test
  @DisplayName("Should reject a completion when it names no title")
  void shouldRejectACompletionWhenItNamesNoTitle() {
    var completion = completionBuilder();

    assertThatThrownBy(() -> completion.collectableId(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("collectableId");
  }

  private static StreamSessionCompletion.StreamSessionCompletionBuilder completionBuilder() {
    return StreamSessionCompletion.builder()
        .streamSessionId(UUID.randomUUID())
        .profileId(UUID.randomUUID())
        .collectableId(UUID.randomUUID())
        .durationSeconds(7200);
  }
}
