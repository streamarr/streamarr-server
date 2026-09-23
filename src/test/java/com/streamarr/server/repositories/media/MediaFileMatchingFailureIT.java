package com.streamarr.server.repositories.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MatchingFailure;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("IntegrationTest")
@DisplayName("Media File Matching Failure Integration Tests")
class MediaFileMatchingFailureIT extends AbstractIntegrationTest {

  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private LibraryRepository libraryRepository;

  @Test
  @DisplayName("Should store the failure status and reason when matching fails")
  void shouldStoreTheFailureStatusAndReasonWhenMatchingFails() {
    var mediaFile = saveMediaFile(MediaFileStatus.UNMATCHED);

    var recorded =
        mediaFileRepository.tryRecordMatchingFailure(
            mediaFile.getId(),
            new MatchingFailure(
                MediaFileStatus.METADATA_UNAVAILABLE, ItemFailureReason.MISCONFIGURED));

    assertThat(recorded).isTrue();
    var stored = mediaFileRepository.findById(mediaFile.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(MediaFileStatus.METADATA_UNAVAILABLE);
    assertThat(stored.getFailureReason()).isEqualTo(ItemFailureReason.MISCONFIGURED);
  }

  @Test
  @DisplayName("Should clear the earlier reason when a later failure has none")
  void shouldClearTheEarlierReasonWhenALaterFailureHasNone() {
    var mediaFile = saveMediaFile(MediaFileStatus.UNMATCHED);
    mediaFileRepository.tryRecordMatchingFailure(
        mediaFile.getId(),
        new MatchingFailure(MediaFileStatus.ENRICHMENT_FAILED, ItemFailureReason.TEMPORARY));

    mediaFileRepository.tryRecordMatchingFailure(
        mediaFile.getId(), MatchingFailure.of(MediaFileStatus.METADATA_NOT_FOUND));

    var stored = mediaFileRepository.findById(mediaFile.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(MediaFileStatus.METADATA_NOT_FOUND);
    assertThat(stored.getFailureReason()).isNull();
  }

  @Test
  @DisplayName("Should reject a stale failure when the file has already been matched")
  void shouldRejectAStaleFailureWhenTheFileHasAlreadyBeenMatched() {
    var mediaFile = saveMediaFile(MediaFileStatus.MATCHED);

    var recorded =
        mediaFileRepository.tryRecordMatchingFailure(
            mediaFile.getId(),
            new MatchingFailure(MediaFileStatus.ENRICHMENT_FAILED, ItemFailureReason.TEMPORARY));

    assertThat(recorded).isFalse();
    var stored = mediaFileRepository.findById(mediaFile.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(MediaFileStatus.MATCHED);
    assertThat(stored.getFailureReason()).isNull();
  }

  @Test
  @DisplayName("Should report nothing recorded when the media file no longer exists")
  void shouldReportNothingRecordedWhenTheMediaFileNoLongerExists() {
    assertThat(
            mediaFileRepository.tryRecordMatchingFailure(
                UUID.randomUUID(), MatchingFailure.of(MediaFileStatus.METADATA_NOT_FOUND)))
        .isFalse();
  }

  @Test
  @DisplayName("Should reject a reason on a status that explains itself")
  void shouldRejectAReasonOnAStatusThatExplainsItself() {
    var mediaFile = saveMediaFile(MediaFileStatus.UNMATCHED);
    var failure =
        new MatchingFailure(MediaFileStatus.METADATA_NOT_FOUND, ItemFailureReason.TEMPORARY);

    assertThatThrownBy(
            () -> mediaFileRepository.tryRecordMatchingFailure(mediaFile.getId(), failure))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Should clear the failure reason when the file is later matched")
  void shouldClearTheFailureReasonWhenTheFileIsLaterMatched() {
    var mediaFile = saveMediaFile(MediaFileStatus.UNMATCHED);
    mediaFileRepository.tryRecordMatchingFailure(
        mediaFile.getId(),
        new MatchingFailure(MediaFileStatus.ENRICHMENT_FAILED, ItemFailureReason.TEMPORARY));

    var reloaded = mediaFileRepository.findById(mediaFile.getId()).orElseThrow();
    reloaded.setStatus(MediaFileStatus.MATCHED);
    reloaded.setFailureReason(null);
    mediaFileRepository.saveAndFlush(reloaded);

    var stored = mediaFileRepository.findById(mediaFile.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(MediaFileStatus.MATCHED);
    assertThat(stored.getFailureReason()).isNull();
  }

  private MediaFile saveMediaFile(MediaFileStatus status) {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    return mediaFileRepository.saveAndFlush(
        MediaFile.builder()
            .libraryId(library.getId())
            .status(status)
            .filename("movie.mkv")
            .filepathUri("file:///library/" + UUID.randomUUID() + "/movie.mkv")
            .build());
  }
}
