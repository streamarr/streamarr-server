package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.ProbeVersionRescanRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Probe version re-scan")
class ProbeVersionRescanIT extends AbstractIntegrationTest {

  private static final Instant STARTED_ON = Instant.parse("2026-10-14T12:00:00Z");

  @Autowired private ProbeVersionRescanRepository rescans;
  @Autowired private LibraryRepository libraries;
  @Autowired private DSLContext dsl;

  private final List<UUID> scannedLibraries = new CopyOnWriteArrayList<>();
  private final List<Library> createdLibraries = new ArrayList<>();

  @BeforeEach
  void forgetQueuedRescans() {
    dsl.deleteFrom(DSL.table("probe_version_rescan")).execute();
  }

  @AfterEach
  void removeLibraries() {
    libraries.deleteAll(createdLibraries);
  }

  @Test
  @DisplayName(
      "Should queue one scan of every library when the probe version increases, and none after a"
          + " restart")
  void shouldQueueOneScanOfEveryLibraryWhenTheProbeVersionIncreasesAndNoneAfterARestart() {
    var movies = createLibrary();
    var shows = createLibrary();

    rescan().onStartup();
    var afterUpgrade = List.copyOf(scannedLibraries);
    rescan().onStartup();

    assertThat(afterUpgrade).contains(movies.getId(), shows.getId()).doesNotHaveDuplicates();
    assertThat(scannedLibraries).as("A restart queues no second re-scan").isEqualTo(afterUpgrade);
    assertThat(
            dsl.fetchValue(
                "SELECT queued_on FROM probe_version_rescan WHERE probe_version = ?",
                ProbeVersion.CURRENT))
        .isNotNull();
  }

  @Test
  @DisplayName("Should queue the re-scan from one server when two servers start together")
  void shouldQueueTheRescanFromOneServerWhenTwoServersStartTogether() throws Exception {
    var start = new CountDownLatch(1);
    Callable<Boolean> startup =
        () -> {
          assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
          return rescans.tryMarkQueued(ProbeVersion.CURRENT, STARTED_ON);
        };

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(startup);
      var second = executor.submit(startup);
      start.countDown();

      assertThat(List.of(result(first), result(second))).containsExactlyInAnyOrder(true, false);
    }
  }

  private ProbeVersionRescan rescan() {
    return new ProbeVersionRescan(
        rescans, libraries, scannedLibraries::add, Clock.fixed(STARTED_ON, ZoneOffset.UTC));
  }

  private Library createLibrary() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    createdLibraries.add(library);
    return library;
  }

  private static boolean result(Future<Boolean> future) throws Exception {
    return future.get(10, TimeUnit.SECONDS);
  }
}
