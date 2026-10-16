package com.streamarr.server.support;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A WireMock extension that holds the responses of stubs marked with {@link #held} until the test
 * releases them, so that a test orders a response after its own steps instead of after a fixed
 * delay. The owner resets it after each test, which sends every response still held.
 */
public final class HeldResponses implements ServeEventListener {

  private static final String NAME = "held-responses";

  private CountDownLatch release = new CountDownLatch(1);
  private int heldResponses;

  /** Marks the stub so that each of its responses waits until the test releases them. */
  public static MappingBuilder held(MappingBuilder stub) {
    return stub.withServeEventListener(RequestPhase.BEFORE_RESPONSE_SENT, NAME, Parameters.empty());
  }

  /** Waits until at least {@code count} responses are held, failing after {@code bound}. */
  public synchronized void awaitHeldResponses(int count, Duration bound)
      throws InterruptedException {
    var deadline = System.nanoTime() + bound.toNanos();
    while (heldResponses < count) {
      var remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        throw new AssertionError(
            heldResponses + " of " + count + " responses were held within " + bound);
      }

      TimeUnit.NANOSECONDS.timedWait(this, remaining);
    }
  }

  /** Sends every held response, and every later response of a marked stub until {@link #reset}. */
  public synchronized void release() {
    release.countDown();
  }

  /** Sends every held response, then holds the responses of marked stubs again. */
  public synchronized void reset() {
    release.countDown();
    release = new CountDownLatch(1);
  }

  @Override
  public void beforeResponseSent(ServeEvent serveEvent, Parameters parameters) {
    var awaited = holdResponse();
    try {
      awaited.await();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    } finally {
      changeHeldResponses(-1);
    }
  }

  private synchronized CountDownLatch holdResponse() {
    changeHeldResponses(1);
    return release;
  }

  private synchronized void changeHeldResponses(int change) {
    heldResponses += change;
    notifyAll();
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public boolean applyGlobally() {
    return false;
  }
}
