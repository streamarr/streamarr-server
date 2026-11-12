package com.streamarr.server.services.library;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.repositories.media.BitrateScanPriorityRepository;
import com.streamarr.server.services.events.library.UnmeasuredCopyStartedEvent;
import com.streamarr.server.support.BoundedTask;
import io.micrometer.core.instrument.MeterRegistry;
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

  // Half the bound of a held raise, so a listener that waited for the raise would still wait.
  private static final Duration RETURN_BOUND = Duration.ofSeconds(5);
  private static final Duration BOUND = Duration.ofSeconds(10);

  private final RaiseOutcomeMeters meters = new RaiseOutcomeMeters();
  private final Map<UUID, BitrateScanPriority> waitingScans = new ConcurrentHashMap<>();
  private final List<UnmeasuredCopyListener> listeners = new ArrayList<>();
  private final List<HeldRaise> heldRaises = new ArrayList<>();
  private final List<RaiseThatOutlivesItsInterrupt> stuckRaises = new ArrayList<>();
  private final UUID mediaFileId = UUID.randomUUID();

  @AfterEach
  void finishRaises() {
    heldRaises.forEach(HeldRaise::finish);
    stuckRaises.forEach(RaiseThatOutlivesItsInterrupt::finish);
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
    meters.awaitCounted("moved");

    assertThat(waitingScans)
        .containsEntry(mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION);
    assertThat(countOf("moved")).isOne();
  }

  @Test
  @DisplayName("Should count an unchanged scan when the copied file has no bitrate scan")
  void shouldCountAnUnchangedScanWhenTheCopiedFileHasNoBitrateScan() {
    var listener = listenerOver(this::raiseWaitingScan);

    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    meters.awaitCounted("unchanged");

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
    meters.awaitCounted("moved");

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
    meters.awaitCounted("failed");

    assertThat(countOf("failed")).isOne();
    assertThat(countOf("cancelled")).isZero();
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
    meters.awaitCounted("moved");

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
    meters.awaitCounted("failed");

    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    meters.awaitCounted("moved");

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
  @DisplayName(
      "Should cancel the waiting raise and close while the scan table still holds it back when"
          + " the server shuts down")
  void shouldCancelTheWaitingRaiseAndCloseWhileTheScanTableStillHoldsItBackWhenTheServerShutsDown()
      throws Exception {
    waitingScans.put(mediaFileId, BitrateScanPriority.UNPROBED_BITRATE);
    var heldRaise = raiseHeldFor(mediaFileId);
    var server = new AnnotationConfigApplicationContext();
    server.registerBean(BitrateScanPriorityRepository.class, () -> heldRaise);
    server.registerBean(MeterRegistry.class, () -> meters);
    server.register(UnmeasuredCopyListener.class);
    server.refresh();
    server.publishEvent(new UnmeasuredCopyStartedEvent(mediaFileId));
    awaitLatch(heldRaise.started);

    BoundedTask.runWithin(RETURN_BOUND, server::close);

    assertThat(countOf("cancelled")).isOne();
    assertThat(countOf("failed")).isZero();
    assertThat(waitingScans).containsEntry(mediaFileId, BitrateScanPriority.UNPROBED_BITRATE);
  }

  @Test
  @DisplayName(
      "Should count the cancelled raise before it closes when the raise takes time to end after"
          + " its interrupt")
  void shouldCountTheCancelledRaiseBeforeItClosesWhenTheRaiseTakesTimeToEndAfterItsInterrupt()
      throws Exception {
    var endingRaise = raiseThatOutlivesItsInterrupt();
    var listener = listenerOver(endingRaise);
    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    awaitLatch(endingRaise.started);

    try (var shutdown = BoundedTask.start(listener::close)) {
      awaitLatch(endingRaise.interrupted);
      // A thread's state has no signal, and a close that does not wait never reaches this state.
      await().atMost(BOUND).until(shutdown::isWaiting);
      endingRaise.finish();
      shutdown.await(RETURN_BOUND);
    }

    assertThat(countOf("cancelled")).isOne();
  }

  @Test
  @DisplayName(
      "Should close while the raise still runs when the raise does not end at its interrupt")
  void shouldCloseWhileTheRaiseStillRunsWhenTheRaiseDoesNotEndAtItsInterrupt() throws Exception {
    var stuckRaise = raiseThatOutlivesItsInterrupt();
    var listener = listenerOver(stuckRaise);
    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    awaitLatch(stuckRaise.started);

    BoundedTask.runWithin(RETURN_BOUND, listener::close);
    var raiseEndedBeforeCloseReturned = stuckRaise.ended.getCount() == 0;
    stuckRaise.finish();
    awaitLatch(stuckRaise.ended);

    assertThat(raiseEndedBeforeCloseReturned).isFalse();
  }

  @Test
  @DisplayName(
      "Should stop waiting for the raise and keep the interrupt when its own close is"
          + " interrupted")
  void shouldStopWaitingForTheRaiseAndKeepTheInterruptWhenItsOwnCloseIsInterrupted()
      throws Exception {
    var stuckRaise = raiseThatOutlivesItsInterrupt();
    var listener = listenerOver(stuckRaise);
    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    awaitLatch(stuckRaise.started);
    boolean closingThreadKeptItsInterrupt;

    try (var shutdown =
        BoundedTask.start(
            () -> {
              Thread.currentThread().interrupt();
              listener.close();
              return Thread.currentThread().isInterrupted();
            })) {
      closingThreadKeptItsInterrupt = shutdown.await(RETURN_BOUND);
    }

    var raiseEndedBeforeCloseReturned = stuckRaise.ended.getCount() == 0;
    stuckRaise.finish();
    awaitLatch(stuckRaise.ended);
    assertThat(closingThreadKeptItsInterrupt).isTrue();
    assertThat(raiseEndedBeforeCloseReturned).isFalse();
  }

  @Test
  @DisplayName(
      "Should count a cancelled raise for each stream session and let it start when the listener"
          + " has closed")
  void shouldCountACancelledRaiseForEachStreamSessionAndLetItStartWhenTheListenerHasClosed() {
    waitingScans.put(mediaFileId, BitrateScanPriority.UNPROBED_BITRATE);
    var listener = listenerOver(this::raiseWaitingScan);
    listener.close();

    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));
    listener.onUnmeasuredCopyStarted(new UnmeasuredCopyStartedEvent(mediaFileId));

    assertThat(countOf("cancelled")).isEqualTo(2);
    assertThat(countOf("failed")).isZero();
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

  private RaiseThatOutlivesItsInterrupt raiseThatOutlivesItsInterrupt() {
    var stuckRaise = new RaiseThatOutlivesItsInterrupt();
    stuckRaises.add(stuckRaise);
    return stuckRaise;
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

  /**
   * A raise that runs on after its interrupt until the test finishes it, and then fails as an
   * interrupted wait for a pooled connection does.
   */
  private static final class RaiseThatOutlivesItsInterrupt
      implements BitrateScanPriorityRepository {

    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final CountDownLatch finish = new CountDownLatch(1);
    private final CountDownLatch ended = new CountDownLatch(1);

    @Override
    public boolean tryRaisePriority(UUID file, BitrateScanPriority priority) {
      started.countDown();
      while (finish.getCount() > 0) {
        try {
          finish.await();
        } catch (InterruptedException _) {
          interrupted.countDown();
        }
      }

      ended.countDown();
      if (interrupted.getCount() > 0) {
        return false;
      }

      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted during connection acquisition");
    }

    private void finish() {
      finish.countDown();
    }
  }

  private double countOf(String outcome) {
    return meters.countOf(outcome);
  }
}
