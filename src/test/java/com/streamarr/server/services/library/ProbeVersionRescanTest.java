package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.exceptions.LibraryOperationNotScheduledException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeLibraryMutationTransaction;
import com.streamarr.server.fakes.FakeLibraryOperationRepository;
import com.streamarr.server.fakes.FakeLibraryOperationScheduling;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.services.library.LibraryOperationRejection.LibraryBusy;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe version re-scan at startup")
class ProbeVersionRescanTest {

  private final FakeLibraryRepository libraries = new FakeLibraryRepository();
  private final FakeLibraryOperationRepository libraryOperations =
      new FakeLibraryOperationRepository(libraries);
  private final FakeLibraryOperationScheduling scheduling = new FakeLibraryOperationScheduling();
  private final LibraryOperationService libraryOperationService =
      LibraryOperationService.builder()
          .libraryOperations(libraryOperations)
          .scheduling(scheduling)
          .mutationTransactions(
              new MutationTransactions(
                  new FakeTransactionManager(), new ConstraintViolationTranslator()))
          .libraryMutationTransaction(new FakeLibraryMutationTransaction())
          .eventPublisher(new CapturingEventPublisher())
          .metrics(new LibraryOperationMetrics(new SimpleMeterRegistry()))
          .build();

  @Test
  @DisplayName(
      "Should start a scan requested by the System actor for each library probed under an earlier"
          + " version")
  void shouldStartAScanRequestedByTheSystemActorForEachLibraryProbedUnderAnEarlierVersion() {
    var first = libraries.save(LibraryFixtureCreator.buildFakeLibrary()).getId();
    var second = libraries.save(LibraryFixtureCreator.buildFakeLibrary()).getId();

    rescanOf(first, second).onStartup();

    assertThat(libraryOperations.libraryOperations())
        .extracting(
            LibraryOperation::libraryId, LibraryOperation::kind, LibraryOperation::createdBy)
        .containsExactlyInAnyOrder(
            tuple(first, LibraryOperationKind.SCAN, SystemActor.ID),
            tuple(second, LibraryOperationKind.SCAN, SystemActor.ID));
    assertThat(scheduling.scheduledExecutions()).hasSize(2);
  }

  @Test
  @DisplayName("Should keep the running operation when a library is busy at startup")
  void shouldKeepTheRunningOperationWhenALibraryIsBusyAtStartup() {
    var libraryId = libraries.save(LibraryFixtureCreator.buildFakeLibrary()).getId();
    var running = libraryOperationService.startLibraryScanAsSystemActor(libraryId);

    rescanOf(libraryId).onStartup();

    assertThat(running).isInstanceOf(Outcome.Accepted.class);
    assertThat(libraryOperations.libraryOperations()).hasSize(1);
    assertThat(libraryOperationService.startLibraryScanAsSystemActor(libraryId))
        .isEqualTo(
            Outcome.rejected(new LibraryBusy(LibraryStatus.SCANNING, LibraryOperationKind.SCAN)));
  }

  @Test
  @DisplayName("Should start the other libraries' scans when one library cannot start its scan")
  void shouldStartTheOtherLibrariesScansWhenOneLibraryCannotStartItsScan() {
    var failing = libraries.save(LibraryFixtureCreator.buildFakeLibrary()).getId();
    var starting = libraries.save(LibraryFixtureCreator.buildFakeLibrary()).getId();
    scheduling.failNextWith(
        new LibraryOperationNotScheduledException(
            failing, new IllegalStateException("simulated scheduling failure")));

    rescanOf(failing, starting).onStartup();

    assertThat(scheduling.scheduledExecutions())
        .containsExactly(libraryOperations.operationFor(starting).orElseThrow().id());
  }

  private ProbeVersionRescan rescanOf(UUID... libraryIds) {
    return new ProbeVersionRescan(_ -> List.of(libraryIds), libraryOperationService);
  }
}
