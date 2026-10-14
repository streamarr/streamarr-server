package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.ProbeVersionRescanRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@Tag("UnitTest")
@SpringBootTest(
    classes = {
      ProbeVersionRescan.class,
      LibraryCrashRecoveryService.class,
      ProbeVersionRescanStartupTest.StartupFakes.class
    })
@DisplayName("Probe version re-scan at startup")
class ProbeVersionRescanStartupTest {

  private static final Library STUCK_SCANNING =
      LibraryFixtureCreator.buildFakeLibrary().toBuilder().status(LibraryStatus.SCANNING).build();

  @Autowired private RefusingBusyLibraries scans;

  @Test
  @DisplayName("Should scan a library that a stopped server left scanning")
  void shouldScanALibraryThatAStoppedServerLeftScanning() {
    assertThat(scans.started()).containsExactly(STUCK_SCANNING.getId());
  }

  /** Starts a scan like LibraryManagementService does: never for a library that is scanning. */
  static final class RefusingBusyLibraries implements LibraryScanTrigger {

    private final LibraryRepository libraries;
    private final List<UUID> started = new CopyOnWriteArrayList<>();

    RefusingBusyLibraries(LibraryRepository libraries) {
      this.libraries = libraries;
    }

    @Override
    public void triggerAsyncScan(UUID libraryId) {
      var library = libraries.findById(libraryId).orElseThrow();
      if (library.getStatus() == LibraryStatus.SCANNING) {
        return;
      }

      started.add(libraryId);
    }

    List<UUID> started() {
      return List.copyOf(started);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class StartupFakes {

    @Bean
    FakeLibraryRepository libraryRepository() {
      var libraries = new FakeLibraryRepository();
      libraries.save(STUCK_SCANNING);
      return libraries;
    }

    @Bean
    RefusingBusyLibraries scanTrigger(FakeLibraryRepository libraryRepository) {
      return new RefusingBusyLibraries(libraryRepository);
    }

    @Bean
    ActiveScanChecker activeScanChecker() {
      return _ -> false;
    }

    @Bean
    ProbeVersionRescanRepository probeVersionRescanRepository() {
      return _ -> List.of(STUCK_SCANNING.getId());
    }
  }
}
