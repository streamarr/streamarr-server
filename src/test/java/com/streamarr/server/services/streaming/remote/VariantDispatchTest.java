package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Variant Dispatch Tests")
class VariantDispatchTest {

  @Test
  @DisplayName("Should reject construction when the execution target is missing")
  void shouldRejectConstructionWhenTheExecutionTargetIsMissing() {
    var encoder = Optional.<WorkerVerifiedEncoder>empty();

    assertThatNullPointerException()
        .isThrownBy(() -> new VariantDispatch(null, encoder))
        .withMessageContaining("target");
  }

  @Test
  @DisplayName("Should reject construction when the encoder is missing")
  void shouldRejectConstructionWhenTheEncoderIsMissing() {
    var target = new ExecutionTargetId("worker-session");

    assertThatNullPointerException()
        .isThrownBy(() -> new VariantDispatch(target, null))
        .withMessageContaining("encoder");
  }
}
