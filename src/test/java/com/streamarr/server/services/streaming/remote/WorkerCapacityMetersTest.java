package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.google.protobuf.Duration;
import com.streamarr.transcode.v1.BackgroundProbeCapacityControl;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Worker Capacity Meters Tests")
class WorkerCapacityMetersTest {

  private static final UUID WORKER_ID = UUID.randomUUID();
  private static final String MISSING_ENCODER_METRIC = "streamarr.streaming.missing_encoder";
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
  @DisplayName(
      "Should report each codec family that the server encodes and the worker has no encoder for when the worker connects")
  void
      shouldReportEachCodecFamilyThatTheServerEncodesAndTheWorkerHasNoEncoderForWhenTheWorkerConnects() {
    meters.connected(WORKER_ID, new FixedSession(1, Set.of("h264", "hevc")));

    assertThat(missingEncoder("av1")).isEqualTo(1);
    assertThat(missingEncoder("h264")).isZero();
    assertThat(meterRegistry.find(MISSING_ENCODER_METRIC).tag("codec_family", "hevc").gauge())
        .isNull();
  }

  @Test
  @DisplayName("Should report no missing encoder when the worker reconnects with every encoder")
  void shouldReportNoMissingEncoderWhenTheWorkerReconnectsWithEveryEncoder() {
    meters.connected(WORKER_ID, new FixedSession(1, Set.of("h264")));

    meters.connected(WORKER_ID, new FixedSession(1, Set.of("h264", "av1")));

    assertThat(missingEncoder("av1")).isZero();
    assertThat(missingEncoder("h264")).isZero();
    assertThat(meterRegistry.find(MISSING_ENCODER_METRIC).gauges()).hasSize(2);
  }

  @Test
  @DisplayName("Should remove the worker's missing encoder meters when the worker disconnects")
  void shouldRemoveTheWorkersMissingEncoderMetersWhenTheWorkerDisconnects() {
    var session = new FixedSession(1, Set.of());
    meters.connected(WORKER_ID, session);

    meters.disconnected(WORKER_ID, session);

    assertThat(meterRegistry.find(MISSING_ENCODER_METRIC).gauges()).isEmpty();
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

  private double missingEncoder(String codecFamily) {
    return meterRegistry
        .get(MISSING_ENCODER_METRIC)
        .tag("worker", WORKER_ID.toString())
        .tag("codec_family", codecFamily)
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

  private record FixedSession(int playbackSlots, Set<String> verifiedVideoCodecFamilies)
      implements WorkerCapacityMeters.Session {

    private FixedSession(int playbackSlots) {
      this(playbackSlots, Set.of("h264", "av1"));
    }

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
