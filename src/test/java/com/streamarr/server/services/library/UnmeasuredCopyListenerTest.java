package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.streamarr.server.repositories.media.BitrateScanPriorityRepository;
import com.streamarr.server.services.events.library.UnmeasuredCopyStartedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

@Tag("UnitTest")
@DisplayName("Unmeasured Copy Listener Tests")
class UnmeasuredCopyListenerTest {

  private static final String MOVED_SCANS_METRIC = "streamarr.probes.bitrate_scans_moved_forward";

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final Map<UUID, BitrateScanPriority> waitingScans = new HashMap<>();
  private final UUID mediaFileId = UUID.randomUUID();

  @Test
  @DisplayName(
      "Should move the file's bitrate scan to the copied file's priority when a stream session"
          + " copies the file without a measurement")
  void
      shouldMoveTheFilesBitrateScanToTheCopiedFilesPriorityWhenAStreamSessionCopiesTheFileWithoutAMeasurement() {
    waitingScans.put(mediaFileId, BitrateScanPriority.UNPROBED_BITRATE);

    listenerOver(this::raiseWaitingScan)
        .onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));

    assertThat(waitingScans)
        .containsEntry(mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION);
    assertThat(countOf("moved")).isOne();
  }

  @Test
  @DisplayName("Should count an unchanged scan when the copied file has no bitrate scan")
  void shouldCountAnUnchangedScanWhenTheCopiedFileHasNoBitrateScan() {
    listenerOver(this::raiseWaitingScan)
        .onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));

    assertThat(waitingScans).isEmpty();
    assertThat(countOf("unchanged")).isOne();
  }

  @Test
  @DisplayName(
      "Should let the stream session keep running and count the failure when the scan table"
          + " cannot be written")
  void shouldLetTheStreamSessionKeepRunningAndCountTheFailureWhenTheScanTableCannotBeWritten() {
    var listener =
        listenerOver(
            (_, _) -> {
              throw new QueryTimeoutException("The scan table did not answer");
            });

    assertThatCode(
            () -> listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId)))
        .doesNotThrowAnyException();
    assertThat(countOf("failed")).isOne();
  }

  private UnmeasuredCopyListener listenerOver(BitrateScanPriorityRepository scans) {
    return new UnmeasuredCopyListener(scans, meters);
  }

  // The scan table's guarded raise: only a waiting scan of a lower priority changes.
  private boolean raiseWaitingScan(UUID file, BitrateScanPriority priority) {
    var waiting = waitingScans.get(file);
    if (waiting == null || waiting.value() >= priority.value()) {
      return false;
    }

    waitingScans.put(file, priority);
    return true;
  }

  private double countOf(String outcome) {
    return meters.get(MOVED_SCANS_METRIC).tag("outcome", outcome).counter().count();
  }
}
