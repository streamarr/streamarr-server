package com.streamarr.server.repositories.streaming;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.SessionProgressFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * V047's NOT VALID profile FKs tolerate legacy placeholder rows but must reject every new write
 * for a profile that does not exist — the enforcement half of the data-integrity contract.
 */
@Tag("IntegrationTest")
@DisplayName("Watch Table Profile FK Integration Tests")
class WatchTableProfileFkIT extends AbstractIntegrationTest {

  @Autowired private SessionProgressRepository sessionProgressRepository;

  @Autowired private WatchHistoryRepository watchHistoryRepository;

  @Autowired private LibraryRepository libraryRepository;

  @Autowired private MediaFileRepository mediaFileRepository;

  private UUID libraryId;
  private UUID mediaFileId;

  @AfterEach
  void cleanUp() {
    if (mediaFileId != null) {
      mediaFileRepository.deleteById(mediaFileId);
      libraryRepository.deleteById(libraryId);
    }
  }

  @Test
  @DisplayName("Should reject session progress when profile does not exist")
  void shouldRejectSessionProgressWhenProfileDoesNotExist() {
    seedMediaFile();

    // A real media file isolates the profile FK: it is the only constraint left to violate.
    assertThatThrownBy(
            () ->
                sessionProgressRepository.saveAndFlush(
                    SessionProgressFixture.progressBuilder(UUID.randomUUID(), mediaFileId).build()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_session_progress_profile");
  }

  @Test
  @DisplayName("Should reject watch history when profile does not exist")
  void shouldRejectWatchHistoryWhenProfileDoesNotExist() {
    assertThatThrownBy(
            () ->
                watchHistoryRepository.saveAndFlush(
                    WatchHistory.builder()
                        .profileId(UUID.randomUUID())
                        .collectableId(UUID.randomUUID())
                        .watchedAt(Instant.now())
                        .durationSeconds(3600)
                        .build()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_watch_history_profile");
  }

  private void seedMediaFile() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    libraryId = library.getId();
    var mediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("fk-pin.mkv")
                .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
                .build());
    mediaFileId = mediaFile.getId();
  }
}
