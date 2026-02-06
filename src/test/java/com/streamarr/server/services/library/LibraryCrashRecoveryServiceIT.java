package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.repositories.LibraryRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
  @DisplayName("Should allow library to be scanned after recovery from stuck SCANNING status")
  void shouldAllowLibraryToBeRescannedAfterRecovery() {
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
        .filepath("/test/crashed/" + UUID.randomUUID())
        .backend(LibraryBackend.LOCAL)
        .status(LibraryStatus.SCANNING)
        .type(MediaType.MOVIE)
        .externalAgentStrategy(ExternalAgentStrategy.TMDB)
        .build();
  }
}
