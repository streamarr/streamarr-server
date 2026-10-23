package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.google.protobuf.Duration;
import com.streamarr.transcode.v1.BackgroundProbeCapacityControl;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Worker Capacity Meters Tests")
class WorkerCapacityMetersTest {

  private static final UUID WORKER_ID = UUID.randomUUID();
  private static final SegmentUploadMetadata TIMED_SEGMENT =
      SegmentUploadMetadata.newBuilder()
          .setMediaDuration(Duration.newBuilder().setSeconds(6))
          .setProductionTime(Duration.newBuilder().setSeconds(2))
          .build();

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final WorkerCapacityMeters meters = new WorkerCapacityMeters(meterRegistry);

  @Test
  @DisplayName(
      "Should keep the replacing session's meters when the replaced session disconnects late")
  void shouldKeepTheReplacingSessionsMetersWhenTheReplacedSessionDisconnectsLate() {
    var replaced = new FixedSession(1);
    var replacing = new FixedSession(3);
    meters.connected(WORKER_ID, replaced);
    meters.connected(WORKER_ID, replacing);

    meters.disconnected(WORKER_ID, replaced);

    assertThat(playbackSlots()).isEqualTo(3);
  }

  @Test
  @DisplayName("Should report no production speed when the segment's session was replaced")
  void shouldReportNoProductionSpeedWhenTheSegmentsSessionWasReplaced() {
    var replaced = new FixedSession(1);
    meters.connected(WORKER_ID, replaced);
    meters.connected(WORKER_ID, new FixedSession(1));

    meters.segmentPublished(WORKER_ID, replaced, TIMED_SEGMENT);

    assertThat(productionSpeedCount()).isZero();
  }

  @Test
  @DisplayName("Should report no production speed when the worker has no connected session")
  void shouldReportNoProductionSpeedWhenTheWorkerHasNoConnectedSession() {
    var session = new FixedSession(1);
    meters.connected(WORKER_ID, session);
    meters.disconnected(WORKER_ID, session);

    meters.segmentPublished(WORKER_ID, session, TIMED_SEGMENT);

    assertThat(meterRegistry.find("streamarr.streaming.production_speed").summary()).isNull();
  }

  @Test
  @DisplayName("Should report no production speed when a media segment carries no production time")
  void shouldReportNoProductionSpeedWhenAMediaSegmentCarriesNoProductionTime() {
    var session = new FixedSession(1);
    meters.connected(WORKER_ID, session);

    meters.segmentPublished(
        WORKER_ID, session, TIMED_SEGMENT.toBuilder().clearProductionTime().build());

    assertThat(productionSpeedCount()).isZero();
  }

  @Test
  @DisplayName("Should reject a meter call when its worker or session is missing")
  void shouldRejectAMeterCallWhenItsWorkerOrSessionIsMissing() {
    var session = new FixedSession(1);

    assertThatNullPointerException()
        .isThrownBy(() -> meters.connected(null, session))
        .withMessageContaining("workerId");
    assertThatNullPointerException()
        .isThrownBy(() -> meters.disconnected(WORKER_ID, null))
        .withMessageContaining("session");
    assertThatNullPointerException()
        .isThrownBy(() -> meters.segmentPublished(WORKER_ID, session, null))
        .withMessageContaining("metadata");
  }

  private double playbackSlots() {
    return meterRegistry
        .get("streamarr.streaming.playback_slots")
        .tag("worker", WORKER_ID.toString())
        .gauge()
        .value();
  }

  private long productionSpeedCount() {
    return meterRegistry
        .get("streamarr.streaming.production_speed")
        .tag("worker", WORKER_ID.toString())
        .summary()
        .count();
  }

  private record FixedSession(int playbackSlots) implements WorkerCapacityMeters.Session {

    @Override
    public int playbackSlotsInUse() {
      return 0;
    }

    @Override
    public int grantedUnits(ProbeKind kind) {
      return 0;
    }

    @Override
    public int runningProbes(ProbeKind kind) {
      return 0;
    }

    @Override
    public BackgroundProbeCapacityControl backgroundProbeCapacityControl() {
      return BackgroundProbeCapacityControl.BACKGROUND_PROBE_CAPACITY_CONTROL_ADAPTIVE;
    }
  }
}
