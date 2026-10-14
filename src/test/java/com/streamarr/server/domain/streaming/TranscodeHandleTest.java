package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture.libx264;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Transcode Handle Tests")
class TranscodeHandleTest {

  @Test
  @DisplayName("Should carry no process id when the producer is a remote dispatch")
  void shouldCarryNoProcessIdWhenTheProducerIsARemoteDispatch() {
    var attemptId = UUID.randomUUID();

    var handle = TranscodeHandle.remoteDispatch(attemptId, TranscodeStatus.ACTIVE, 5);

    assertThat(handle.processId()).isEmpty();
    assertThat(handle.attemptId()).isEqualTo(attemptId);
    assertThat(handle.startSequenceNumber()).isEqualTo(5);
  }

  @Test
  @DisplayName("Should carry the OS process id when the producer is a local process")
  void shouldCarryTheOsProcessIdWhenTheProducerIsALocalProcess() {
    var handle = new TranscodeHandle(1234L, UUID.randomUUID(), TranscodeStatus.ACTIVE, 0);

    assertThat(handle.processId()).hasValue(1234L);
  }

  @Test
  @DisplayName("Should preserve attempt identity and process locus when the status transitions")
  void shouldPreserveAttemptIdentityAndProcessLocusWhenTheStatusTransitions() {
    var remote = TranscodeHandle.remoteDispatch(UUID.randomUUID(), TranscodeStatus.ACTIVE, 3);

    var suspended = remote.withStatus(TranscodeStatus.SUSPENDED);

    assertThat(suspended.attemptId()).isEqualTo(remote.attemptId());
    assertThat(suspended.processId()).isEmpty();
    assertThat(suspended.startSequenceNumber()).isEqualTo(3);
    assertThat(suspended.status()).isEqualTo(TranscodeStatus.SUSPENDED);
  }

  @Test
  @DisplayName("Should keep the dispatched encoder when the status transitions")
  void shouldKeepTheDispatchedEncoderWhenTheStatusTransitions() {
    var dispatched =
        TranscodeHandle.remoteDispatch(UUID.randomUUID(), TranscodeStatus.ACTIVE, 3)
            .withEncoder(libx264());

    var suspended = dispatched.withStatus(TranscodeStatus.SUSPENDED);

    assertThat(suspended.encoder()).contains(libx264());
    assertThat(suspended.startSequenceNumber()).isEqualTo(3);
  }

  @Test
  @DisplayName("Should reject construction when the encoder is missing")
  void shouldRejectConstructionWhenTheEncoderIsMissing() {
    var processId = OptionalLong.empty();
    var attemptId = UUID.randomUUID();

    assertThatNullPointerException()
        .isThrownBy(
            () -> new TranscodeHandle(processId, attemptId, TranscodeStatus.ACTIVE, 0, null))
        .withMessageContaining("encoder");
  }

  @Test
  @DisplayName("Should reject a dispatched encoder when it is missing")
  void shouldRejectDispatchedEncoderWhenItIsMissing() {
    var handle = TranscodeHandle.remoteDispatch(UUID.randomUUID(), TranscodeStatus.ACTIVE, 0);

    assertThatNullPointerException()
        .isThrownBy(() -> handle.withEncoder(null))
        .withMessageContaining("dispatchedEncoder");
  }
}
