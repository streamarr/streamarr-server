package com.streamarr.server.services.library.admission;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import org.springframework.stereotype.Component;

/**
 * Shared state of every admission strategy: issues tickets and holds the numbers behind the
 * {@code poc.scan.*} and {@code poc.admission.limit} meters.
 */
@Component
public class AdmissionRuntime {

  /** Optional hooks for strategies that react to tasks instead of polling. */
  public interface Listener {
    default void onLeftTransit(AdmissionTicket ticket) {}

    default void onEnded(AdmissionTicket ticket) {}
  }

  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger inTransit = new AtomicInteger();
  private final LongAdder worked = new LongAdder();
  private final LongAdder shortCircuited = new LongAdder();
  private final LongAdder failed = new LongAdder();
  private final List<Listener> listeners = new CopyOnWriteArrayList<>();
  private volatile DoubleSupplier admissionLimit = () -> Double.NaN;

  /** Admits one item. The caller must run or end the returned ticket. */
  public AdmissionTicket admit() {
    inFlight.incrementAndGet();
    inTransit.incrementAndGet();
    return new AdmissionTicket(this);
  }

  /** Admitted and not yet finished. */
  public int inFlight() {
    return inFlight.get();
  }

  /** Admitted, not finished, and not yet at the TMDB gate. */
  public int inTransit() {
    return inTransit.get();
  }

  public long completed(TaskOutcome outcome) {
    return switch (outcome) {
      case WORKED -> worked.sum();
      case SHORT_CIRCUITED -> shortCircuited.sum();
    };
  }

  public long failedCount() {
    return failed.sum();
  }

  /** What {@code poc.admission.limit} reports; NaN unless a strategy supplies one. */
  public double admissionLimit() {
    return admissionLimit.getAsDouble();
  }

  public void setAdmissionLimit(DoubleSupplier admissionLimit) {
    this.admissionLimit = admissionLimit;
  }

  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  void leftTransit(AdmissionTicket ticket) {
    inTransit.decrementAndGet();
    listeners.forEach(listener -> listener.onLeftTransit(ticket));
  }

  void completed(AdmissionTicket ticket, TaskOutcome outcome) {
    switch (outcome) {
      case WORKED -> worked.increment();
      case SHORT_CIRCUITED -> shortCircuited.increment();
    }

    ended(ticket);
  }

  void failed(AdmissionTicket ticket) {
    failed.increment();
    ended(ticket);
  }

  private void ended(AdmissionTicket ticket) {
    inFlight.decrementAndGet();
    listeners.forEach(listener -> listener.onEnded(ticket));
  }
}
