package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.fakes.FakeSegmentStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Remote Transcode Executor Tests")
class RemoteTranscodeExecutorTest {

  @Test
  @DisplayName("Should reject a pinned execution-target lookup when its pinned encoder is missing")
  void shouldRejectPinnedExecutionTargetLookupWhenItsPinnedEncoderIsMissing() {
    var executor = unstartedExecutor();

    assertThatNullPointerException()
        .isThrownBy(() -> executor.executionTargets((WorkerVerifiedEncoder) null))
        .withMessageContaining("pinnedEncoder");
  }

  private static RemoteTranscodeExecutor unstartedExecutor() {
    var server =
        new WorkerSessionServer(
            WorkerSessionServerConfiguration.builder().port(0).build(),
            new FakeSegmentStore(),
            new SimpleMeterRegistry());
    return new RemoteTranscodeExecutor(server, UUID.randomUUID(), Path.of("/media"));
  }
}
