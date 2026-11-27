package com.streamarr.server.services.streaming.remote;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The bytes of probe reports that the server reads at once, apart from the byte budget of segment
 * uploads. A probe report that does not fit waits. The waiting probe reports of playback probes get
 * room before the waiting probe reports of other probes, and within each of the two groups the
 * probe reports get room in the order in which they asked. A small probe report therefore overtakes
 * a large one only when it is the probe report of a playback probe and the large one is not. A
 * probe report keeps the room that it holds until its reservation closes.
 */
final class ProbeReportMemoryBudget {

  private final long budgetBytes;
  private final Deque<Reservation> waitingReportsOfPlaybackProbes = new ArrayDeque<>();
  private final Deque<Reservation> waitingReportsOfOtherProbes = new ArrayDeque<>();
  private long reservedBytes;

  ProbeReportMemoryBudget(long budgetBytes) {
    this.budgetBytes = budgetBytes;
  }

  /**
   * Reserves room for a probe report of this length, of a probe of this kind. The reservation holds
   * its room at once when the bytes fit and no probe report that goes before it waits: none of a
   * playback probe, and none of its own group. Otherwise it waits its turn, and {@code
   * whenGrantedLater} runs once it holds its room, on the thread that released the room.
   */
  synchronized Reservation reserve(ProbeKind kind, long bytes, Runnable whenGrantedLater) {
    if (bytes <= 0 || bytes > budgetBytes) {
      throw new IllegalArgumentException(
          "A probe report of %d bytes can never fit the budget of %d"
              .formatted(bytes, budgetBytes));
    }

    var waitingReportsOfItsGroup =
        kind == ProbeKind.PLAYBACK ? waitingReportsOfPlaybackProbes : waitingReportsOfOtherProbes;
    var grantedAtOnce =
        waitingReportsOfPlaybackProbes.isEmpty()
            && waitingReportsOfItsGroup.isEmpty()
            && fits(bytes);
    var reservation = new Reservation(bytes, whenGrantedLater, grantedAtOnce);
    if (grantedAtOnce) {
      reservedBytes += bytes;
      return reservation;
    }

    waitingReportsOfItsGroup.add(reservation);
    return reservation;
  }

  private boolean fits(long bytes) {
    return reservedBytes <= budgetBytes - bytes;
  }

  // A reservation that stops waiting can let the one behind it fit, as released room can.
  private synchronized List<Reservation> closeAndGrantWaiting(Reservation closing) {
    if (closing.closed) {
      return List.of();
    }

    closing.closed = true;
    if (closing.granted) {
      reservedBytes -= closing.bytes;
    } else {
      // It waits in one of the two groups.
      waitingReportsOfPlaybackProbes.remove(closing);
      waitingReportsOfOtherProbes.remove(closing);
    }

    var granted = new ArrayList<Reservation>();
    grantWhileFirstFits(waitingReportsOfPlaybackProbes, granted);
    // While the probe report of a playback probe waits, no other probe report gets room.
    if (waitingReportsOfPlaybackProbes.isEmpty()) {
      grantWhileFirstFits(waitingReportsOfOtherProbes, granted);
    }

    return granted;
  }

  private void grantWhileFirstFits(Deque<Reservation> waiting, List<Reservation> granted) {
    while (!waiting.isEmpty() && fits(waiting.peek().bytes)) {
      var next = waiting.remove();
      next.granted = true;
      reservedBytes += next.bytes;
      granted.add(next);
    }
  }

  /** The room of one probe report, or its place among the probe reports that wait for room. */
  final class Reservation implements AutoCloseable {

    private final long bytes;
    private final Runnable whenGrantedLater;
    private final boolean grantedAtOnce;
    // Both guarded by the budget's monitor.
    private boolean granted;
    private boolean closed;

    private Reservation(long bytes, Runnable whenGrantedLater, boolean grantedAtOnce) {
      this.bytes = bytes;
      this.whenGrantedLater = whenGrantedLater;
      this.grantedAtOnce = grantedAtOnce;
      granted = grantedAtOnce;
    }

    /** Whether the reservation held its room when it was made, so that no callback follows. */
    boolean isGrantedAtOnce() {
      return grantedAtOnce;
    }

    /**
     * Releases the room, or stops waiting, and gives room to the probe reports whose turn it is and
     * that now fit. Their callbacks run outside the budget's monitor. Closing again changes
     * nothing.
     */
    @Override
    public void close() {
      closeAndGrantWaiting(this).forEach(granted -> granted.whenGrantedLater.run());
    }
  }
}
