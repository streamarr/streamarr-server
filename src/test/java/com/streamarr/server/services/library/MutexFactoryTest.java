package com.streamarr.server.services.library;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("UnitTest")
@DisplayName("Mutex Factory Tests")
public class MutexFactoryTest {

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
