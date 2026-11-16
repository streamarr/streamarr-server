package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.SourceStreamEnd;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.KeyframePacketScanPublication;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Learned Stream End Repository Integration Tests")
class LearnedStreamEndRepositoryIT extends AbstractIntegrationTest
    implements LearnedStreamEndRepositoryContract {

  @Autowired private LearnedStreamEndRepository learnedStreamEnds;
  @Autowired private MediaFileContainerInfoRepository probeOutcomes;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private JdbcTemplate jdbc;

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

  @Override
  public void storeProbeOutcome(UUID mediaFileId, SourceFileSnapshot snapshot) {
    assertThat(probeOutcomes.publish(publication(mediaFileId, snapshot, ProbeVersion.CURRENT)))
        .isTrue();
  }

  @Override
  public void storeProbeOutcomeNeedingPacketScan(UUID mediaFileId, SourceFileSnapshot snapshot) {
    assertThat(probeOutcomes.publish(needingPacketScan(mediaFileId, snapshot))).isTrue();
  }

  @Override
  public boolean publishKeyframePacketScan(
      UUID mediaFileId, SourceFileSnapshot snapshot, List<SourceStreamEnd> streamEnds) {
    return probeOutcomes.publishKeyframePacketScan(packetScan(mediaFileId, snapshot, streamEnds));
  }

  // A probe publication locks the media file's row and then replaces the file's outcome row. A
  // scan publication that wrote the outcome row before it took the media file's row lock would
  // wait for that lock while the probe publication waits for the outcome row, and PostgreSQL
  // would end one of the two as a deadlock.
  @Test
  @DisplayName(
      "Should let a probe publication replace the outcome when a keyframe packet scan publishes its"
          + " stream ends at the same moment")
  void shouldLetAProbePublicationReplaceTheOutcomeWhenAKeyframePacketScanPublishesAtTheSameMoment()
      throws Exception {
    var mediaFileId = createMediaFile();
    storeProbeOutcomeNeedingPacketScan(mediaFileId, SNAPSHOT);
    var scan =
        packetScan(mediaFileId, SNAPSHOT, List.of(new SourceStreamEnd(1, Duration.ofSeconds(14))));

    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var publishingScan =
          transactionTemplate.execute(
              _ -> {
                dsl.execute("SELECT 1 FROM media_file WHERE id = ? FOR UPDATE", mediaFileId);
                var holder = dsl.fetchValue(DSL.field("pg_backend_pid()", Integer.class));
                Future<Boolean> pending =
                    threads.submit(() -> probeOutcomes.publishKeyframePacketScan(scan));
                await().atMost(Duration.ofSeconds(10)).until(() -> hasBackendWaitingBehind(holder));
                storeProbeOutcome(mediaFileId, SNAPSHOT);
                return pending;
              });

      assertThat(publishingScan.get(10, TimeUnit.SECONDS)).isFalse();
    }

    assertThat(learnedStreamEnds.findFor(mediaFileId, SNAPSHOT)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep a learned end time when a new probe of the same file stores its outcome")
  void shouldKeepALearnedEndTimeWhenANewProbeOfTheSameFileStoresItsOutcome() {
    var mediaFileId = createMediaFile();
    probeOutcomes.publish(publication(mediaFileId, SNAPSHOT, ProbeVersion.CURRENT - 1));
    var learned = learnedAudioEnd(mediaFileId);
    learnedStreamEnds.store(learned);

    var republished =
        probeOutcomes.publish(publication(mediaFileId, SNAPSHOT, ProbeVersion.CURRENT));

    assertThat(republished).isTrue();
    assertThat(learnedStreamEnds.findFor(mediaFileId, SNAPSHOT)).containsExactly(learned);
  }

  @Test
  @DisplayName(
      "Should keep exactly one learned end time when two attempts complete at the same moment")
  void shouldKeepExactlyOneLearnedEndTimeWhenTwoAttemptsCompleteAtTheSameMoment() throws Exception {
    var mediaFileId = createProbedMediaFile();
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
  @DisplayName(
      "Should keep the changed file's end when an attempt that read the earlier version stores its"
          + " end while the changed file's outcome and end commit")
  void shouldKeepTheChangedFilesEndWhenAnEarlierVersionsEndIsStoredWhileTheChangedFileCommits()
      throws Exception {
    var mediaFileId = createProbedMediaFile();
    var earlier = learnedAudioEnd(mediaFileId);
    var changed =
        earlier.toBuilder().snapshot(CHANGED_SNAPSHOT).endTime(Duration.ofSeconds(91)).build();

    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var storingEarlier =
          transactionTemplate.execute(
              _ -> {
                storeProbeOutcome(mediaFileId, CHANGED_SNAPSHOT);
                assertThat(learnedStreamEnds.store(changed)).isTrue();
                var holder = dsl.fetchValue(DSL.field("pg_backend_pid()", Integer.class));
                Future<Boolean> pending = threads.submit(() -> learnedStreamEnds.store(earlier));
                await().atMost(Duration.ofSeconds(10)).until(() -> hasBackendWaitingBehind(holder));
                return pending;
              });

      assertThat(storingEarlier.get(10, TimeUnit.SECONDS)).isFalse();
    }

    assertThat(learnedStreamEnds.findFor(mediaFileId, CHANGED_SNAPSHOT)).containsExactly(changed);
  }

  private boolean hasBackendWaitingBehind(int holder) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM pg_locks WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid))
            )
            """,
            Boolean.class,
            holder));
  }

  @Test
  @DisplayName("Should delete a file's learned end times when the media file is deleted")
  void shouldDeleteAFilesLearnedEndTimesWhenTheMediaFileIsDeleted() {
    var mediaFileId = createProbedMediaFile();
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
  private static ProbePublication publication(
      UUID mediaFileId, SourceFileSnapshot snapshot, int probeVersion) {
    return publicationBuilder(mediaFileId, snapshot, new KeyframeKnowledge.NotReported())
        .probeVersion(probeVersion)
        .build();
  }

  private static ProbePublication needingPacketScan(UUID mediaFileId, SourceFileSnapshot snapshot) {
    return publicationBuilder(
            mediaFileId,
            snapshot,
            new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX))
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }

  private static ProbePublication.ProbePublicationBuilder publicationBuilder(
      UUID mediaFileId, SourceFileSnapshot snapshot, KeyframeKnowledge keyframes) {
    return ProbePublication.builder()
        .mediaFileId(mediaFileId)
        .snapshot(snapshot)
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
                keyframes));
  }

  private static KeyframePacketScanPublication packetScan(
      UUID mediaFileId, SourceFileSnapshot snapshot, List<SourceStreamEnd> streamEnds) {
    return KeyframePacketScanPublication.builder()
        .mediaFileId(mediaFileId)
        .snapshot(snapshot)
        .probeVersion(ProbeVersion.CURRENT)
        .keyframes(
            new KeyframeKnowledge.Known(
                KeyframePositions.builder()
                    .streamIndex(0)
                    .timeBaseNumerator(1)
                    .timeBaseDenominator(90_000)
                    .source(KeyframePositionSource.PACKET_SCAN)
                    .keyframes(List.of(new KeyframePosition(0, 0)))
                    .build()))
        .streamEnds(streamEnds)
        .build();
  }
}
