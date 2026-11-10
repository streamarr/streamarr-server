package com.streamarr.server.services.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("UnitTest")
@Timeout(60)
@DisplayName("Mutex Factory Held Lock Identity Tests")
class MutexFactoryHeldLockIdentityTest {

  private static final String KEY = "key";

  private final MutexFactory<String> mutexFactory = new MutexFactory<>();

  @Test
  @DisplayName("Should return the held lock when soft references are cleared while it is held")
  void shouldReturnTheHeldLockWhenSoftReferencesAreClearedWhileItIsHeld() {
    var held = mutexFactory.getMutex(KEY);
    held.lock();
    try {
      clearSoftReferences();

      assertThat(mutexFactory.getMutex(KEY)).isSameAs(held);
    } finally {
      held.unlock();
    }
  }

  @Test
  @DisplayName(
      "Should keep another thread out of a key's mutex when soft references are cleared while it"
          + " is held")
  void shouldKeepAnotherThreadOutOfAKeysMutexWhenSoftReferencesAreClearedWhileItIsHeld()
      throws Exception {
    var held = mutexFactory.getMutex(KEY);
    held.lock();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      clearSoftReferences();

      var acquiredByAnotherThread =
          executor.submit(() -> mutexFactory.getMutex(KEY).tryLock()).get(5, TimeUnit.SECONDS);

      assertThat(acquiredByAnotherThread).isFalse();
    } finally {
      held.unlock();
    }
  }

  // The JVM clears every softly reachable reference before it throws an OutOfMemoryError. A
  // request for more than the whole heap fails without committing any memory, so this neither
  // fills the heap nor starves other threads. On a heap above 16 GB the largest array fits, and
  // the loop keeps arrays until one does not.
  private static void clearSoftReferences() {
    var canary = new SoftReference<>(new Object());
    var wholeHeap = Runtime.getRuntime().maxMemory() / Long.BYTES + 1;
    var length = (int) Math.min(Integer.MAX_VALUE - 8L, wholeHeap);
    var kept = new ArrayList<long[]>();
    try {
      while (true) {
        kept.add(new long[length]);
      }
    } catch (OutOfMemoryError _) {
      kept.clear();
    }

    assertThat(canary.refersTo(null)).as("the JVM cleared its soft references").isTrue();
  }
}
