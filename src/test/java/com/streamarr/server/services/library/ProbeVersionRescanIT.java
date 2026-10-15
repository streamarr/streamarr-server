package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.ProbeVersionRescanRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Probe version re-scan")
class ProbeVersionRescanIT extends AbstractIntegrationTest {

  private static final int EARLIER_VERSION = ProbeVersion.CURRENT - 1;
  private static final SourceFileSnapshot SNAPSHOT =
      new SourceFileSnapshot(1234, Instant.parse("2026-10-14T12:00:00Z"));

  @Autowired private ProbeVersionRescanRepository rescans;
  @Autowired private LibraryRepository libraries;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private MediaFileContainerInfoRepository outcomes;

  // Other test classes can leave libraries in the shared database, so assertions name their own.
  private final List<UUID> scannedLibraries = new CopyOnWriteArrayList<>();
  private final List<MediaFile> createdFiles = new ArrayList<>();
  private final List<Library> createdLibraries = new ArrayList<>();

  @AfterEach
  void removeLibraries() {
    mediaFiles.deleteAll(createdFiles);
    libraries.deleteAll(createdLibraries);
  }

  @Test
  @DisplayName("Should scan each library that holds a file probed under an earlier probe version")
  void shouldScanEachLibraryThatHoldsAFileProbedUnderAnEarlierProbeVersion() {
    var probedEarlier = createLibrary();
    request(createMediaFile(probedEarlier), EARLIER_VERSION);
    publish(createMediaFile(probedEarlier), EARLIER_VERSION);
    var storedEarlier = createLibrary();
    publish(createMediaFile(storedEarlier), EARLIER_VERSION);
    var requestedNow = createLibrary();
    var pending = createMediaFile(requestedNow);
    publish(pending, EARLIER_VERSION);
    request(pending, ProbeVersion.CURRENT);
    var current = createLibrary();
    request(createMediaFile(current), ProbeVersion.CURRENT);
    publish(createMediaFile(current), ProbeVersion.CURRENT);
    var neverProbed = createLibrary();
    createMediaFile(neverProbed);

    rescan().onStartup();

    assertThat(scannedLibraries)
        .containsOnlyOnce(probedEarlier.getId(), storedEarlier.getId())
        .doesNotContain(requestedNow.getId(), current.getId(), neverProbed.getId());
  }

  @Test
  @DisplayName(
      "Should scan a library again at the next startup when its upgrade scan did not finish")
  void shouldScanALibraryAgainAtTheNextStartupWhenItsUpgradeScanDidNotFinish() {
    var movies = createLibrary();
    request(createMediaFile(movies), EARLIER_VERSION);

    rescan().onStartup();
    rescan().onStartup();

    assertThat(Collections.frequency(scannedLibraries, movies.getId())).isEqualTo(2);
  }

  @Test
  @DisplayName("Should not scan a library again once a scan requested the current probe version")
  void shouldNotScanALibraryAgainOnceAScanRequestedTheCurrentProbeVersion() {
    var movies = createLibrary();
    var file = createMediaFile(movies);
    request(file, EARLIER_VERSION);
    rescan().onStartup();

    request(file, ProbeVersion.CURRENT);
    rescan().onStartup();

    assertThat(Collections.frequency(scannedLibraries, movies.getId())).isOne();
  }

  private ProbeVersionRescan rescan() {
    return new ProbeVersionRescan(rescans, scannedLibraries::add);
  }

  private void request(MediaFile file, int probeVersion) {
    assertThat(outcomes.trySaveProbeRequest(file.getId(), new ProbeInputs(SNAPSHOT, probeVersion)))
        .isTrue();
  }

  private void publish(MediaFile file, int probeVersion) {
    assertThat(
            outcomes.publish(
                ProbePublication.builder()
                    .mediaFileId(file.getId())
                    .snapshot(SNAPSHOT)
                    .probeVersion(probeVersion)
                    .outcome(new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA))
                    .build()))
        .isTrue();
  }

  private Library createLibrary() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    createdLibraries.add(library);
    return library;
  }

  private MediaFile createMediaFile(Library library) {
    var file =
        mediaFiles.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("rescan.mkv")
                .filepathUri("file:///library/" + UUID.randomUUID() + ".mkv")
                .size(SNAPSHOT.size())
                .build());
    createdFiles.add(file);
    return file;
  }
}
