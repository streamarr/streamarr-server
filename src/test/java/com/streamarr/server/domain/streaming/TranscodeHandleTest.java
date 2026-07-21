package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

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
}
