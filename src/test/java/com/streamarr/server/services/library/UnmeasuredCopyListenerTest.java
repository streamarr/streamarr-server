package com.streamarr.server.services.library;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.repositories.media.BitrateScanPriorityRepository;
import com.streamarr.server.services.events.library.UnmeasuredCopyStartedEvent;
import com.streamarr.server.support.BoundedTask;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@Tag("UnitTest")
@DisplayName("Unmeasured Copy Listener Tests")
class UnmeasuredCopyListenerTest {

  private static final String MOVED_SCANS_METRIC = "streamarr.probes.bitrate_scans_moved_forward";
  private static final Duration BOUND = Duration.ofSeconds(10);
  // Half the bound of a held raise, so a listener that waited for the raise would still wait.
  private static final Duration RETURN_BOUND = Duration.ofSeconds(5);

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final Map<UUID, BitrateScanPriority> waitingScans = new ConcurrentHashMap<>();
  private final List<UnmeasuredCopyListener> listeners = new ArrayList<>();
  private final List<HeldRaise> heldRaises = new ArrayList<>();
  private final UUID mediaFileId = UUID.randomUUID();

  @AfterEach
  void finishRaises() {
    heldRaises.forEach(HeldRaise::finish);
    listeners.forEach(UnmeasuredCopyListener::close);
  }

  @Test
  @DisplayName(
      "Should move the file's bitrate scan to the copied file's priority when a stream session"
          + " copies the file without a measurement")
  void
      shouldMoveTheFilesBitrateScanToTheCopiedFilesPriorityWhenAStreamSessionCopiesTheFileWithoutAMeasurement() {
    waitingScans.put(mediaFileId, BitrateScanPriority.UNPROBED_BITRATE);
    var listener = listenerOver(this::raiseWaitingScan);

    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    listener.close();

    assertThat(waitingScans)
        .containsEntry(mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION);
    assertThat(countOf("moved")).isOne();
  }

  @Test
  @DisplayName("Should count an unchanged scan when the copied file has no bitrate scan")
  void shouldCountAnUnchangedScanWhenTheCopiedFileHasNoBitrateScan() {
    var listener = listenerOver(this::raiseWaitingScan);

    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    listener.close();

    assertThat(waitingScans).isEmpty();
    assertThat(countOf("unchanged")).isOne();
  }

  @Test
  @DisplayName(
      "Should return to the stream session's start while the raise waits when the scan table"
          + " holds the raise back")
  void shouldReturnToTheStreamSessionsStartWhileTheRaiseWaitsWhenTheScanTableHoldsTheRaiseBack()
      throws Exception {
    waitingScans.put(mediaFileId, BitrateScanPriority.UNPROBED_BITRATE);
    var heldRaise = raiseHeldFor(mediaFileId);
    var listener = listenerOver(heldRaise);

    BoundedTask.runWithin(
        RETURN_BOUND,
        () -> listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId)));
    awaitLatch(heldRaise.started);
    var priorityAfterTheListenerReturned = waitingScans.get(mediaFileId);
    heldRaise.finish();
    listener.close();

    assertThat(priorityAfterTheListenerReturned).isEqualTo(BitrateScanPriority.UNPROBED_BITRATE);
    assertThat(waitingScans)
        .containsEntry(mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION);
  }

  @Test
  @DisplayName("Should count the failure when the raise fails")
  void shouldCountTheFailureWhenTheRaiseFails() {
    var listener =
        listenerOver(
            (_, _) -> {
              throw new IllegalStateException("The scan table did not answer");
            });

    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    listener.close();

    assertThat(countOf("failed")).isOne();
  }

  @Test
  @DisplayName(
      "Should start no second raise when another stream session copies the file while the"
          + " file's raise waits")
  void shouldStartNoSecondRaiseWhenAnotherStreamSessionCopiesTheFileWhileTheFilesRaiseWaits() {
    waitingScans.put(mediaFileId, BitrateScanPriority.UNPROBED_BITRATE);
    var heldRaise = raiseHeldFor(mediaFileId);
    var listener = listenerOver(heldRaise);
    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    awaitLatch(heldRaise.started);

    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    var alreadyMovingWhileTheRaiseWaited = countOf("already_moving");
    heldRaise.finish();
    listener.close();

    assertThat(alreadyMovingWhileTheRaiseWaited).isOne();
    assertThat(countOf("moved")).isOne();
    // A second raise would have found the scan already moved.
    assertThat(countOf("unchanged")).isZero();
  }

  @Test
  @DisplayName(
      "Should move the file's bitrate scan when a stream session copies the file after an earlier"
          + " raise failed")
  void shouldMoveTheFilesBitrateScanWhenAStreamSessionCopiesTheFileAfterAnEarlierRaiseFailed() {
    waitingScans.put(mediaFileId, BitrateScanPriority.UNPROBED_BITRATE);
    var scanTableAnswers = new AtomicBoolean();
    var listener =
        listenerOver(
            (file, priority) -> {
              if (!scanTableAnswers.getAndSet(true)) {
                throw new IllegalStateException("The scan table did not answer");
              }

              return raiseWaitingScan(file, priority);
            });
    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    // The count is the only signal that the first raise ended, and the listener stays open.
    await().atMost(BOUND).untilAsserted(() -> assertThat(countOf("failed")).isOne());

    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    listener.close();

    assertThat(waitingScans)
        .containsEntry(mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION);
    assertThat(countOf("moved")).isOne();
  }

  @Test
  @DisplayName("Should move another file's bitrate scan when a file's raise waits")
  void shouldMoveAnotherFilesBitrateScanWhenAFilesRaiseWaits() {
    var otherFileId = UUID.randomUUID();
    waitingScans.put(mediaFileId, BitrateScanPriority.UNPROBED_BITRATE);
    waitingScans.put(otherFileId, BitrateScanPriority.UNPROBED_BITRATE);
    var heldRaise = raiseHeldFor(mediaFileId);
    var listener = listenerOver(heldRaise);
    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    awaitLatch(heldRaise.started);

    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(otherFileId));
    awaitLatch(heldRaise.raisedAnotherFile);

    assertThat(waitingScans)
        .containsEntry(otherFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION)
        .containsEntry(mediaFileId, BitrateScanPriority.UNPROBED_BITRATE);
  }

  @Test
  @DisplayName("Should finish the waiting raise before it closes when the server shuts down")
  void shouldFinishTheWaitingRaiseBeforeItClosesWhenTheServerShutsDown() throws Exception {
    waitingScans.put(mediaFileId, BitrateScanPriority.UNPROBED_BITRATE);
    var heldRaise = raiseHeldFor(mediaFileId);
    var server = new AnnotationConfigApplicationContext();
    server.registerBean(BitrateScanPriorityRepository.class, () -> heldRaise);
    server.registerBean(MeterRegistry.class, () -> meters);
    server.register(UnmeasuredCopyListener.class);
    server.refresh();
    server.publishEvent(new UnmeasuredCopyStartedEvent(mediaFileId));
    awaitLatch(heldRaise.started);

    try (var shutdown = BoundedTask.start(server::close)) {
      await().atMost(BOUND).until(shutdown::isWaiting);
      heldRaise.finish();
      shutdown.await(BOUND);
    }

    assertThat(waitingScans)
        .containsEntry(mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION);
    assertThat(countOf("moved")).isOne();
  }

  @Test
  @DisplayName(
      "Should count a failure for each stream session and let it start when the listener has"
          + " closed")
  void shouldCountAFailureForEachStreamSessionAndLetItStartWhenTheListenerHasClosed() {
    waitingScans.put(mediaFileId, BitrateScanPriority.UNPROBED_BITRATE);
    var listener = listenerOver(this::raiseWaitingScan);
    listener.close();

    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));

    assertThat(countOf("failed")).isEqualTo(2);
    assertThat(waitingScans).containsEntry(mediaFileId, BitrateScanPriority.UNPROBED_BITRATE);
  }

  private UnmeasuredCopyListener listenerOver(BitrateScanPriorityRepository scans) {
    var listener = new UnmeasuredCopyListener(scans, meters);
    listeners.add(listener);
    return listener;
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

  private HeldRaise raiseHeldFor(UUID heldFile) {
    var heldRaise = new HeldRaise(heldFile);
    heldRaises.add(heldRaise);
    return heldRaise;
  }

  /** Holds one file's raise until the test finishes it, as another transaction's row lock does. */
  private final class HeldRaise implements BitrateScanPriorityRepository {

    private final UUID heldFile;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch finish = new CountDownLatch(1);
    private final CountDownLatch raisedAnotherFile = new CountDownLatch(1);

    private HeldRaise(UUID heldFile) {
      this.heldFile = heldFile;
    }

    @Override
    public boolean tryRaisePriority(UUID file, BitrateScanPriority priority) {
      if (file.equals(heldFile)) {
        started.countDown();
        awaitLatch(finish);
        return raiseWaitingScan(file, priority);
      }

      var raised = raiseWaitingScan(file, priority);
      raisedAnotherFile.countDown();
      return raised;
    }

    private void finish() {
      finish.countDown();
    }
  }

  private double countOf(String outcome) {
    return meters.counter(MOVED_SCANS_METRIC, "outcome", outcome).count();
  }
}
