package com.streamarr.server.services.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Mutex Factory Tests")
class MutexFactoryTest {

  private static final String KEY1 = "key1";
  private static final String KEY2 = "key2";

  private final MutexFactory<String> mutexFactory = new MutexFactory<>();
  private volatile int counter = 0;

  @Test
  @DisplayName("Should return the same lock when using the same key")
  public void testGetMutexReturnsSameLockForSameKey() {
    ReentrantLock mutex1 = mutexFactory.getMutex(KEY1);
    ReentrantLock mutex2 = mutexFactory.getMutex(KEY1);

    assertSame(mutex1, mutex2, "Expected mutex1 and mutex2 to be the same object");
  }

  @Test
  @DisplayName("Should return different locks when using different keys")
  public void testGetMutexReturnsDifferentLocksForDifferentKeys() {
    ReentrantLock mutex1 = mutexFactory.getMutex(KEY1);
    ReentrantLock mutex2 = mutexFactory.getMutex(KEY2);

    assertNotSame(mutex1, mutex2, "Expected mutex1 and mutex2 to be different objects");
  }

  @Test
  @DisplayName("Should work across multiple threads when attempting to increment shared counter")
  public void testGetMutexWorksAcrossMultipleThreads() {
    // Create two threads that will each try to acquire the same lock and increment a counter
    Thread thread1 = new Thread(this::incrementCounter);
    Thread thread2 = new Thread(this::incrementCounter);

    // Start both threads
    thread1.start();
    thread2.start();

    // Use Awaitility to wait for both threads to complete
    await().atMost(2, TimeUnit.SECONDS).until(() -> !thread1.isAlive() && !thread2.isAlive());

    assertEquals(2, counter, "Expected counter to be incremented twice");
  }

  @Test
  @DisplayName("Should answer that the current thread holds a mutex while it holds one")
  void shouldAnswerThatTheCurrentThreadHoldsAMutexWhileItHoldsOne() {
    var mutex = mutexFactory.getMutex(KEY1);
    mutexFactory.getMutex(KEY2);

    mutex.lock();
    try {
      assertThat(mutexFactory.isAnyMutexHeldByCurrentThread()).isTrue();
    } finally {
      mutex.unlock();
    }

    assertThat(mutexFactory.isAnyMutexHeldByCurrentThread()).isFalse();
  }

  @Test
  @DisplayName("Should answer that the current thread holds no mutex when another thread holds one")
  void shouldAnswerThatTheCurrentThreadHoldsNoMutexWhenAnotherThreadHoldsOne() throws Exception {
    var mutex = mutexFactory.getMutex(KEY1);
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var holder =
          executor.submit(
              () -> {
                mutex.lock();
                try {
                  held.countDown();
                  return release.await(5, TimeUnit.SECONDS);
                } finally {
                  mutex.unlock();
                }
              });
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(mutexFactory.isAnyMutexHeldByCurrentThread()).isFalse();
      release.countDown();
      assertThat(holder.get(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void incrementCounter() {
    // Get the mutex for the specified key
    var mutex = mutexFactory.getMutex(KEY1);

    // Acquire the lock
    mutex.lock();

    try {
      // Increment the counter
      counter++;
    } finally {
      // Release the lock
      mutex.unlock();
    }
  }
}
