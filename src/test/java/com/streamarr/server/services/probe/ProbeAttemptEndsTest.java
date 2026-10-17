package com.streamarr.server.services.probe;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe attempt ends")
class ProbeAttemptEndsTest {

  private final UUID mediaFileId = UUID.randomUUID();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ProbeAttemptEnds attemptEnds = new ProbeAttemptEnds(meterRegistry);

  @Test
  @DisplayName(
      "Should keep a later request's wait when an earlier request closes an end that happened")
  void shouldKeepALaterRequestsWaitWhenAnEarlierRequestClosesAnEndThatHappened() throws Exception {
    var earlierWait = attemptEnds.next(mediaFileId);
    attemptEnds.signalEnd(mediaFileId);
    var laterWait = attemptEnds.next(mediaFileId);

    earlierWait.close();
    var waitingAfterClose = probeWaits();
    attemptEnds.signalEnd(mediaFileId);

    assertThat(waitingAfterClose).isOne();
    assertThat(laterWait.await(Duration.ZERO)).isTrue();
  }

  @Test
  @DisplayName("Should keep the shared end for the other request when one of two requests closes")
  void shouldKeepTheSharedEndForTheOtherRequestWhenOneOfTwoRequestsCloses() throws Exception {
    var firstWait = attemptEnds.next(mediaFileId);
    var secondWait = attemptEnds.next(mediaFileId);

    firstWait.close();
    var waitingAfterClose = probeWaits();
    attemptEnds.signalEnd(mediaFileId);
    secondWait.close();

    assertThat(waitingAfterClose).isOne();
    assertThat(secondWait.await(Duration.ZERO)).isTrue();
    assertThat(probeWaits()).isZero();
  }

  private double probeWaits() {
    return meterRegistry.get("streamarr.playback.probe_waits").gauge().value();
  }
}
