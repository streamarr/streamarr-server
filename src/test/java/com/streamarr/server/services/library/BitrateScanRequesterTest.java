package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.BitrateScanInputs;
import com.streamarr.server.domain.task.BitrateScanKey;
import com.streamarr.server.domain.task.BitrateScanPriority;
import com.streamarr.server.domain.task.BitrateScanTaskRequest;
import com.streamarr.server.fakes.CapturingBitrateScanRequests;
import com.streamarr.server.fakes.FakeBitrateScanRepository;
import com.streamarr.server.services.library.events.KeyframePacketScanPublishedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Bitrate scan requester")
class BitrateScanRequesterTest {

  private static final UUID MEDIA_FILE_ID = UUID.randomUUID();
  private static final String FILEPATH_URI = "file:///media/movie.ts";
  private static final BitrateScanKey KEY =
      new BitrateScanKey(
          new SourceFileSnapshot(1234, Instant.parse("2026-11-11T10:00:00Z")),
          "0a1b",
          StreamMeasurements.CURRENT_VERSION);

  private final FakeBitrateScanRepository scans = new FakeBitrateScanRepository();
  private final CapturingBitrateScanRequests requests = new CapturingBitrateScanRequests();
  private final BitrateScanRequester requester =
      BitrateScanRequester.builder().scans(scans).requests(requests).build();

  @Test
  @DisplayName(
      "Should request the file's bitrate scan on the stored keyframe positions when a keyframe"
          + " packet scan is stored and the stored outcome needs a bitrate scan")
  void shouldRequestFilesBitrateScanOnStoredKeyframePositionsWhenStoredPacketScanNeedsOne() {
    scans.storeOutcomeNeedingScan(MEDIA_FILE_ID, scannedInputs());

    requester.onKeyframePacketScanPublished(
        new KeyframePacketScanPublishedEvent(MEDIA_FILE_ID, FILEPATH_URI));

    assertThat(requests.requests())
        .containsExactly(
            new CapturingBitrateScanRequests.Requested(
                BitrateScanTaskRequest.builder()
                    .mediaFileId(MEDIA_FILE_ID)
                    .filepathUri(FILEPATH_URI)
                    .snapshot(KEY.snapshot())
                    .keyframePositionsDigest(KEY.keyframePositionsDigest())
                    .measurementVersion(KEY.measurementVersion())
                    .build(),
                BitrateScanPriority.UNPROBED_BITRATE));
  }

  @Test
  @DisplayName(
      "Should request no bitrate scan when a keyframe packet scan is stored and the stored outcome"
          + " needs none")
  void shouldRequestNoBitrateScanWhenStoredPacketScanNeedsNone() {
    requester.onKeyframePacketScanPublished(
        new KeyframePacketScanPublishedEvent(MEDIA_FILE_ID, FILEPATH_URI));

    assertThat(requests.requests()).isEmpty();
  }

  // What a bitrate scan reads from an outcome whose keyframe positions a packet scan stored.
  private static BitrateScanInputs scannedInputs() {
    return BitrateScanInputs.builder()
        .key(KEY)
        .positions(
            KeyframePositions.builder()
                .streamIndex(0)
                .timeBaseNumerator(1)
                .timeBaseDenominator(90_000)
                .source(KeyframePositionSource.PACKET_SCAN)
                .keyframes(List.of(new KeyframePosition(0, 0)))
                .build())
        .containerDuration(Optional.empty())
        .audioStreamIndexes(List.of())
        .chosenVideoHasProbedBitrate(false)
        .build();
  }
}
