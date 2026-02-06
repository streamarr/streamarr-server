package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.repositories.LibraryRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Library Crash Recovery Service Tests")
class LibraryCrashRecoveryServiceTest {

  private final LibraryRepository fakeLibraryRepository = new FakeLibraryRepository();
  private final LibraryCrashRecoveryService recoveryService =
      new LibraryCrashRecoveryService(fakeLibraryRepository, id -> false);

  @Test
  @DisplayName(
      "Should reset SCANNING library to UNHEALTHY when recovering without affecting other libraries")
  void shouldResetScanningLibraryToUnhealthyWhenRecovering() {
    var scanningLibrary = fakeLibraryRepository.save(buildLibrary(LibraryStatus.SCANNING));
    var healthyLibrary = fakeLibraryRepository.save(buildLibrary(LibraryStatus.HEALTHY));
    var unhealthyLibrary = fakeLibraryRepository.save(buildLibrary(LibraryStatus.UNHEALTHY));

    recoveryService.onStartup();

    assertThat(fakeLibraryRepository.findById(scanningLibrary.getId()))
        .isPresent()
        .get()
        .satisfies(lib -> assertThat(lib.getStatus()).isEqualTo(LibraryStatus.UNHEALTHY));

    assertThat(fakeLibraryRepository.findById(healthyLibrary.getId()))
        .isPresent()
        .get()
        .satisfies(lib -> assertThat(lib.getStatus()).isEqualTo(LibraryStatus.HEALTHY));

    assertThat(fakeLibraryRepository.findById(unhealthyLibrary.getId()))
        .isPresent()
        .get()
        .satisfies(lib -> assertThat(lib.getStatus()).isEqualTo(LibraryStatus.UNHEALTHY));
  }

  @Test
  @DisplayName("Should not modify libraries when no libraries are in SCANNING status")
  void shouldNotThrowWhenNoLibrariesAreInScanningStatus() {
    var healthyLibrary = fakeLibraryRepository.save(buildLibrary(LibraryStatus.HEALTHY));

    recoveryService.onStartup();

    assertThat(fakeLibraryRepository.findById(healthyLibrary.getId()).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName("Should set scanCompletedOn timestamp when recovering library")
  void shouldSetScanCompletedOnWhenRecoveringLibrary() {
    var scanningLibrary = fakeLibraryRepository.save(buildLibrary(LibraryStatus.SCANNING));

    recoveryService.onStartup();

    assertThat(fakeLibraryRepository.findById(scanningLibrary.getId()).orElseThrow())
        .satisfies(lib -> assertThat(lib.getScanCompletedOn()).isNotNull());
  }

  @Test
  @DisplayName("Should be idempotent when called twice consecutively")
  void shouldBeIdempotentWhenCalledTwice() {
    fakeLibraryRepository.save(buildLibrary(LibraryStatus.SCANNING));

    recoveryService.onStartup();
    recoveryService.onStartup();

    assertThat(fakeLibraryRepository.findAllByStatus(LibraryStatus.SCANNING)).isEmpty();
    assertThat(fakeLibraryRepository.findAllByStatus(LibraryStatus.UNHEALTHY)).hasSize(1);
  }

  @Test
  @DisplayName("Should not modify libraries when shutdown called with no scanning libraries")
  void shouldNotThrowWhenShutdownCalledWithNoScanningLibraries() {
    var healthyLibrary = fakeLibraryRepository.save(buildLibrary(LibraryStatus.HEALTHY));

    recoveryService.onShutdown();

    assertThat(fakeLibraryRepository.findById(healthyLibrary.getId()).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName("Should not reset actively scanned library when recovering on startup")
  void shouldNotResetActivelyScannedLibraryWhenRecoveringOnStartup() {
    var orphaned = fakeLibraryRepository.save(buildLibrary(LibraryStatus.SCANNING));
    var active = fakeLibraryRepository.save(buildLibrary(LibraryStatus.SCANNING));

    ActiveScanChecker checker = id -> id.equals(active.getId());
    var service = new LibraryCrashRecoveryService(fakeLibraryRepository, checker);

    service.onStartup();

    assertThat(fakeLibraryRepository.findById(orphaned.getId()).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(fakeLibraryRepository.findById(active.getId()).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.SCANNING);
  }

  @Test
  @DisplayName(
      "Should reset all SCANNING libraries when shutting down including actively scanned ones")
  void shouldResetAllScanningLibrariesWhenShuttingDownIncludingActiveOnes() {
    var active = fakeLibraryRepository.save(buildLibrary(LibraryStatus.SCANNING));

    ActiveScanChecker allActive = id -> true;
    var service = new LibraryCrashRecoveryService(fakeLibraryRepository, allActive);

    service.onShutdown();

    assertThat(fakeLibraryRepository.findById(active.getId()).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.UNHEALTHY);
  }

  private static Library buildLibrary(LibraryStatus status) {
    return Library.builder()
        .name("Test Library")
        .backend(LibraryBackend.LOCAL)
        .status(status)
        .filepath("/library/" + UUID.randomUUID())
        .externalAgentStrategy(ExternalAgentStrategy.TMDB)
        .type(MediaType.MOVIE)
        .build();
  }
}
