package com.streamarr.server.services.library;

import static com.streamarr.server.support.TestFixtureAuditActorConfiguration.TEST_FIXTURE_ACTOR_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.repositories.LibraryRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;

// Shutdown recovery resets every busy library, including libraries that other tests are scanning.
@Isolated
@Tag("IntegrationTest")
@DisplayName("Library Crash Recovery Service Integration Tests")
class LibraryCrashRecoveryServiceIT extends AbstractIntegrationTest {

  @Autowired private LibraryCrashRecoveryService recoveryService;
  @Autowired private LibraryManagementService libraryManagementService;
  @Autowired private LibraryRepository libraryRepository;

  @Test
  @DisplayName(
      "Should persist status change and scanCompletedOn timestamp when recovering stuck library")
  void shouldPersistStatusChangeWhenRecoveringStuckLibrary() {
    var stuckLibrary = libraryRepository.saveAndFlush(buildScanningLibrary());

    recoveryService.onStartup();

    var recovered = libraryRepository.findById(stuckLibrary.getId()).orElseThrow();
    assertThat(recovered.getStatus()).isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(recovered.getScanCompletedOn()).isNotNull();
  }

  @Test
  @DisplayName("Should name the System actor when resetting an interrupted library")
  void shouldNameSystemActorWhenResettingInterruptedLibrary() {
    var stuckLibrary = libraryRepository.saveAndFlush(buildScanningLibrary());

    recoveryService.onStartup();

    var recovered = libraryRepository.findById(stuckLibrary.getId()).orElseThrow();
    assertThat(recovered.getCreatedBy()).isEqualTo(TEST_FIXTURE_ACTOR_ID);
    assertThat(recovered.getLastModifiedBy()).isEqualTo(SystemActor.ID);
  }

  @Test
  @DisplayName("Should name the System actor when shutdown resets a busy library")
  void shouldNameSystemActorWhenShutdownResetsBusyLibrary() {
    var busyLibrary = libraryRepository.saveAndFlush(buildScanningLibrary());

    recoveryService.onShutdown();

    var reset = libraryRepository.findById(busyLibrary.getId()).orElseThrow();
    assertThat(reset.getStatus()).isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(reset.getCreatedBy()).isEqualTo(TEST_FIXTURE_ACTOR_ID);
    assertThat(reset.getLastModifiedBy()).isEqualTo(SystemActor.ID);
  }

  @Test
  @DisplayName("Should allow library to be rescanned when recovered from stuck SCANNING status")
  void shouldAllowLibraryToBeRescannedWhenRecoveredFromStuckStatus() {
    var stuckLibrary = libraryRepository.saveAndFlush(buildScanningLibrary());

    recoveryService.onStartup();

    assertThatCode(() -> libraryManagementService.scanLibrary(stuckLibrary.getId()))
        .doesNotThrowAnyException();

    var scanned = libraryRepository.findById(stuckLibrary.getId()).orElseThrow();
    assertThat(scanned.getStatus())
        .as("Status should be UNHEALTHY since test filepath doesn't exist on disk")
        .isEqualTo(LibraryStatus.UNHEALTHY);
  }

  private static Library buildScanningLibrary() {
    return Library.builder()
        .name("Crashed Library")
        .filepathUri("file:///test/crashed/" + UUID.randomUUID())
        .backend(LibraryBackend.LOCAL)
        .status(LibraryStatus.SCANNING)
        .type(MediaType.MOVIE)
        .externalAgentStrategy(ExternalAgentStrategy.TMDB)
        .build();
  }
}
