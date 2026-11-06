package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.LearnedStreamEndRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Learned Stream End Repository Integration Tests")
class LearnedStreamEndRepositoryIT extends AbstractIntegrationTest
    implements LearnedStreamEndRepositoryContract {

  @Autowired private LearnedStreamEndRepository learnedStreamEnds;
  @Autowired private MediaFileContainerInfoRepository probeOutcomes;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private LibraryRepository libraryRepository;

  private final List<MediaFile> createdFiles = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    mediaFileRepository.deleteAll(createdFiles);
    createdFiles.clear();
  }

  @Override
  public LearnedStreamEndRepository repository() {
    return learnedStreamEnds;
  }

  @Override
  public UUID createMediaFile() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var file =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("learned-end.mkv")
                .filepathUri("file:///library/" + UUID.randomUUID() + ".mkv")
                .size(SNAPSHOT.size())
                .build());
    createdFiles.add(file);
    return file.getId();
  }

  @Test
  @DisplayName(
      "Should keep a learned end time when a new probe of the same file stores its outcome")
  void shouldKeepALearnedEndTimeWhenANewProbeOfTheSameFileStoresItsOutcome() {
    var mediaFileId = createMediaFile();
    probeOutcomes.publish(publication(mediaFileId, ProbeVersion.CURRENT - 1));
    var learned = learnedAudioEnd(mediaFileId);
    learnedStreamEnds.store(learned);

    var republished = probeOutcomes.publish(publication(mediaFileId, ProbeVersion.CURRENT));

    assertThat(republished).isTrue();
    assertThat(learnedStreamEnds.findFor(mediaFileId, SNAPSHOT)).containsExactly(learned);
  }

  @Test
  @DisplayName(
      "Should keep exactly one learned end time when two attempts complete at the same moment")
  void shouldKeepExactlyOneLearnedEndTimeWhenTwoAttemptsCompleteAtTheSameMoment() throws Exception {
    var mediaFileId = createMediaFile();
    var first = learnedAudioEnd(mediaFileId);
    var second = first.toBuilder().endTime(Duration.ofSeconds(85)).build();
    var start = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var storingFirst = threads.submit(() -> storeAfter(start, first));
      var storingSecond = threads.submit(() -> storeAfter(start, second));
      start.countDown();

      var stored = List.of(storingFirst.get(), storingSecond.get());

      assertThat(stored).containsExactlyInAnyOrder(true, false);
      var winner = stored.getFirst() ? first : second;
      assertThat(learnedStreamEnds.findFor(mediaFileId, SNAPSHOT)).containsExactly(winner);
    }
  }

  private boolean storeAfter(CountDownLatch start, LearnedStreamEnd learned)
      throws InterruptedException {
    start.await();
    return learnedStreamEnds.store(learned);
  }

  @Test
  @DisplayName("Should delete a file's learned end times when the media file is deleted")
  void shouldDeleteAFilesLearnedEndTimesWhenTheMediaFileIsDeleted() {
    var mediaFileId = createMediaFile();
    learnedStreamEnds.store(learnedAudioEnd(mediaFileId));

    mediaFileRepository.deleteAllById(List.of(mediaFileId));

    assertThat(learnedStreamEnds.findFor(mediaFileId, SNAPSHOT)).isEmpty();
  }

  private static LearnedStreamEnd learnedAudioEnd(UUID mediaFileId) {
    return LearnedStreamEnd.builder()
        .mediaFileId(mediaFileId)
        .snapshot(SNAPSHOT)
        .streamIndex(1)
        .endTime(Duration.ofSeconds(84))
        .build();
  }

  // A Matroska outcome whose audio header states no end time.
  private static ProbePublication publication(UUID mediaFileId, int probeVersion) {
    return ProbePublication.builder()
        .mediaFileId(mediaFileId)
        .snapshot(SNAPSHOT)
        .probeVersion(probeVersion)
        .outcome(
            new ProbeOutcome.Success(
                ProbeContainer.builder()
                    .format(Optional.of("matroska,webm"))
                    .duration(Optional.of(Duration.ofSeconds(84)))
                    .build(),
                List.of(
                    StreamInfo.builder()
                        .index(0)
                        .codecType("video")
                        .codec(Optional.of("h264"))
                        .build(),
                    StreamInfo.builder()
                        .index(1)
                        .codecType("audio")
                        .codec(Optional.of("aac"))
                        .build()),
                new KeyframeKnowledge.NotReported()))
        .build();
  }
}
