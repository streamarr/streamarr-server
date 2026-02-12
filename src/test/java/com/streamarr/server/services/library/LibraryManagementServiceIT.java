package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.exceptions.LibraryScanInProgressException;
import com.streamarr.server.repositories.LibraryRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("LibraryManagementService Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LibraryManagementServiceIT extends AbstractIntegrationTest {

  @TempDir static Path tempDir;

  @Autowired private LibraryManagementService libraryManagementService;
  @Autowired private LibraryRepository libraryRepository;

  private Library testLibrary;

  @BeforeAll
  void setupLibrary() {
    testLibrary =
        libraryRepository.saveAndFlush(
            Library.builder()
                .name("Race Condition Test Library")
                .filepath(tempDir.toString())
                .backend(LibraryBackend.LOCAL)
                .status(LibraryStatus.HEALTHY)
                .type(MediaType.MOVIE)
                .externalAgentStrategy(ExternalAgentStrategy.TMDB)
                .build());
  }

  @Test
  @DisplayName("Should allow only one scan when concurrent calls race")
  void shouldAllowOnlyOneScanWhenConcurrentCallsRace() {
    var threadCount = 10;
    var executor = Executors.newFixedThreadPool(threadCount);
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(threadCount);
    var scanStartedCount = new AtomicInteger(0);
    var scanRejectedCount = new AtomicInteger(0);
    var unexpectedExceptions = new CopyOnWriteArrayList<Exception>();

    for (int i = 0; i < threadCount; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();
              libraryManagementService.scanLibrary(testLibrary.getId());
              scanStartedCount.incrementAndGet();
            } catch (LibraryScanInProgressException _) {
              scanRejectedCount.incrementAndGet();
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            } catch (Exception e) {
              unexpectedExceptions.add(e);
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(doneLatch.getCount()).isZero();
              assertThat(unexpectedExceptions).isEmpty();
              assertThat(scanStartedCount.get())
                  .as("Exactly one thread should start scanning")
                  .isEqualTo(1);
              assertThat(scanRejectedCount.get())
                  .as("All other threads should be rejected")
                  .isEqualTo(threadCount - 1);
            });

    executor.shutdown();
  }
}
