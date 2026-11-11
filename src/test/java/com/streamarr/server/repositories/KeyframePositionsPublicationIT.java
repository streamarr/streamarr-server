package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.MediaFileKeyframePositions.MEDIA_FILE_KEYFRAME_POSITIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.PersistedProbeOutcome;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.KeyframePacketScanPublication;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.support.PostgresLockTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Keyframe positions publication")
class KeyframePositionsPublicationIT extends AbstractIntegrationTest {

  private static final SourceFileSnapshot SNAPSHOT_A =
      new SourceFileSnapshot(1234, Instant.parse("2026-10-14T10:00:00Z"));
  private static final SourceFileSnapshot SNAPSHOT_B =
      new SourceFileSnapshot(5678, Instant.parse("2026-10-15T10:00:00Z"));
  // A 90 kHz time base, as MPEG-TS sources use.
  private static final long TICKS_PER_SECOND = 90_000;
  // Two frames at 24 fps: the presentation delay that B-frame reordering gives a keyframe.
  private static final long B_FRAME_PRESENTATION_DELAY = 7_500;
  private static final int EARLIER_VERSION = ProbeVersion.CURRENT - 1;

  @Autowired private MediaFileContainerInfoRepository repository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private PersistedProbeReader reader;
  @Autowired private DSLContext dsl;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;

  private final List<MediaFile> createdFiles = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    mediaFileRepository.deleteAll(createdFiles);
    createdFiles.clear();
  }

  @Test
  @DisplayName("Should round-trip stored positions when an outcome is published")
  void shouldRoundTripStoredPositionsWhenOutcomeIsPublished() {
    var file = createMediaFile();
    var outcome = success(new KeyframeKnowledge.Known(twoHourSourcePositions()));

    var published = repository.publish(publication(file.getId(), SNAPSHOT_A, outcome));

    assertThat(published).isTrue();
    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(outcome));
  }

  @Test
  @DisplayName("Should store PacketScanRequired without positions")
  void shouldStorePacketScanRequiredWithoutPositions() {
    var file = createMediaFile();
    var outcome =
        success(
            new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX));

    repository.publish(publication(file.getId(), SNAPSHOT_A, outcome));

    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(outcome));
    assertThat(positionsRowCount(file.getId())).isZero();
  }

  @Test
  @DisplayName("Should store unavailable keyframes without positions")
  void shouldStoreUnavailableKeyframesWithoutPositions() {
    var file = createMediaFile();
    var outcome =
        success(
            new KeyframeKnowledge.Unavailable(
                KeyframesUnavailableReason.KEYFRAME_WITHOUT_TIMESTAMP));

    repository.publish(publication(file.getId(), SNAPSHOT_A, outcome));

    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(outcome));
    assertThat(positionsRowCount(file.getId())).isZero();
  }

  @Test
  @DisplayName("Should delete positions when the source snapshot changes")
  void shouldDeletePositionsWhenSourceSnapshotChanges() {
    var file = createMediaFile();
    repository.publish(
        publication(
            file.getId(),
            SNAPSHOT_A,
            success(new KeyframeKnowledge.Known(twoHourSourcePositions()))));

    repository.trySaveProbeRequest(file.getId(), new ProbeInputs(SNAPSHOT_B, 1));

    assertThat(positionsRowCount(file.getId())).isZero();
    assertThat(reader.find(file.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should delete positions when a republished outcome requires a packet scan")
  void shouldDeletePositionsWhenRepublishedOutcomeRequiresPacketScan() {
    var file = createMediaFile();
    repository.publish(
        publication(
            file.getId(),
            SNAPSHOT_A,
            success(new KeyframeKnowledge.Known(twoHourSourcePositions()))));
    var replacement =
        success(
            new KeyframeKnowledge.PacketScanRequired(
                KeyframePacketScanReason.UNREADABLE_LAST_INDEXED_KEYFRAME));

    repository.publish(publication(file.getId(), SNAPSHOT_A, replacement));

    assertThat(positionsRowCount(file.getId())).isZero();
    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(replacement));
  }

  @Test
  @DisplayName("Should store the scanned positions when the stored outcome needs a packet scan")
  void shouldStoreTheScannedPositionsWhenTheStoredOutcomeNeedsAPacketScan() {
    var file = createMediaFile();
    repository.publish(publication(file.getId(), SNAPSHOT_A, packetScanRequired()));
    var scanned = new KeyframeKnowledge.Known(scannedPositions());

    var published =
        repository.publishKeyframePacketScan(packetScan(file.getId(), SNAPSHOT_A, scanned));

    assertThat(published).isTrue();
    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(success(scanned)));
  }

  @Test
  @DisplayName("Should store unavailable keyframes when a packet scan ended in a media error")
  void shouldStoreUnavailableKeyframesWhenAPacketScanEndedInAMediaError() {
    var file = createMediaFile();
    repository.publish(publication(file.getId(), SNAPSHOT_A, packetScanRequired()));
    var unavailable =
        new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.PACKET_SCAN_MEDIA_ERROR);

    var published =
        repository.publishKeyframePacketScan(packetScan(file.getId(), SNAPSHOT_A, unavailable));

    assertThat(published).isTrue();
    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(success(unavailable)));
    assertThat(positionsRowCount(file.getId())).isZero();
  }

  @Test
  @DisplayName("Should discard the scanned positions when the source changed during the scan")
  void shouldDiscardTheScannedPositionsWhenTheSourceChangedDuringTheScan() {
    var file = createMediaFile();
    repository.publish(publication(file.getId(), SNAPSHOT_B, packetScanRequired()));

    var published =
        repository.publishKeyframePacketScan(
            packetScan(file.getId(), SNAPSHOT_A, new KeyframeKnowledge.Known(scannedPositions())));

    assertThat(published).isFalse();
    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(packetScanRequired()));
    assertThat(positionsRowCount(file.getId())).isZero();
  }

  @Test
  @DisplayName("Should discard the scanned positions when the scan used another probe version")
  void shouldDiscardTheScannedPositionsWhenTheScanUsedAnotherProbeVersion() {
    var file = createMediaFile();
    repository.publish(publication(file.getId(), SNAPSHOT_A, packetScanRequired()));
    var scan =
        packetScan(file.getId(), SNAPSHOT_A, new KeyframeKnowledge.Known(scannedPositions()))
            .toBuilder()
            .probeVersion(2)
            .build();

    assertThat(repository.publishKeyframePacketScan(scan)).isFalse();
    assertThat(positionsRowCount(file.getId())).isZero();
  }

  @Test
  @DisplayName("Should keep the stored positions when the outcome no longer needs a packet scan")
  void shouldKeepTheStoredPositionsWhenTheOutcomeNoLongerNeedsAPacketScan() {
    var file = createMediaFile();
    var indexed = success(new KeyframeKnowledge.Known(twoHourSourcePositions()));
    repository.publish(publication(file.getId(), SNAPSHOT_A, indexed));

    var published =
        repository.publishKeyframePacketScan(
            packetScan(file.getId(), SNAPSHOT_A, new KeyframeKnowledge.Known(scannedPositions())));

    assertThat(published).isFalse();
    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(indexed));
  }

  @Test
  @DisplayName("Should read the packet scan inputs only while the stored outcome needs a scan")
  void shouldReadThePacketScanInputsOnlyWhileTheStoredOutcomeNeedsAScan() {
    var file = createMediaFile();
    repository.publish(publication(file.getId(), SNAPSHOT_A, packetScanRequired()));

    var needed = transactionTemplate.execute(_ -> repository.lockPacketScanInputs(file.getId()));
    repository.publishKeyframePacketScan(
        packetScan(file.getId(), SNAPSHOT_A, new KeyframeKnowledge.Known(scannedPositions())));
    var scanned = transactionTemplate.execute(_ -> repository.lockPacketScanInputs(file.getId()));

    assertThat(needed).contains(new ProbeInputs(SNAPSHOT_A, 1));
    assertThat(scanned).isEmpty();
  }

  @Test
  @DisplayName("Should read no packet scan inputs when the media file no longer exists")
  void shouldReadNoPacketScanInputsWhenTheMediaFileNoLongerExists() {
    var missing = UUID.randomUUID();

    var inputs = transactionTemplate.execute(_ -> repository.lockPacketScanInputs(missing));

    assertThat(inputs).isEmpty();
  }

  @Test
  @DisplayName("Should refuse a positions row when the outcome's keyframes are not known")
  void shouldRefusePositionsRowWhenOutcomesKeyframesAreNotKnown() {
    var file = createMediaFile();
    repository.publish(
        publication(
            file.getId(),
            SNAPSHOT_A,
            success(
                new KeyframeKnowledge.PacketScanRequired(
                    KeyframePacketScanReason.NO_CONTAINER_INDEX))));
    var insert =
        dsl.insertInto(MEDIA_FILE_KEYFRAME_POSITIONS)
            .set(MEDIA_FILE_KEYFRAME_POSITIONS.MEDIA_FILE_ID, file.getId())
            .set(MEDIA_FILE_KEYFRAME_POSITIONS.STREAM_INDEX, 0)
            .set(MEDIA_FILE_KEYFRAME_POSITIONS.TIME_BASE_NUMERATOR, 1)
            .set(MEDIA_FILE_KEYFRAME_POSITIONS.TIME_BASE_DENOMINATOR, 90_000)
            .set(MEDIA_FILE_KEYFRAME_POSITIONS.KEYFRAME_COUNT, 1)
            .set(MEDIA_FILE_KEYFRAME_POSITIONS.ENCODED_TIMES, new byte[] {1, 0, 0})
            .set(MEDIA_FILE_KEYFRAME_POSITIONS.SOURCE, KeyframePositionSource.PACKET_SCAN.name());

    assertThatThrownBy(insert::execute).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName(
      "Should find no outcome when the stored keyframe count differs from the encoded keyframes")
  void shouldFindNoOutcomeWhenStoredKeyframeCountDiffersFromEncodedKeyframes() {
    var file = createMediaFile();
    repository.publish(
        publication(
            file.getId(),
            SNAPSHOT_A,
            success(new KeyframeKnowledge.Known(twoHourSourcePositions()))));
    dsl.update(MEDIA_FILE_KEYFRAME_POSITIONS)
        .set(MEDIA_FILE_KEYFRAME_POSITIONS.KEYFRAME_COUNT, 3_599)
        .where(MEDIA_FILE_KEYFRAME_POSITIONS.MEDIA_FILE_ID.eq(file.getId()))
        .execute();

    assertThat(reader.find(file.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should find no outcome when the stored positions name an unknown format")
  void shouldFindNoOutcomeWhenStoredPositionsNameUnknownFormat() {
    var file = createMediaFile();
    repository.publish(
        publication(
            file.getId(),
            SNAPSHOT_A,
            success(new KeyframeKnowledge.Known(twoHourSourcePositions()))));
    dsl.update(MEDIA_FILE_KEYFRAME_POSITIONS)
        .set(MEDIA_FILE_KEYFRAME_POSITIONS.ENCODED_TIMES, new byte[] {2})
        .where(MEDIA_FILE_KEYFRAME_POSITIONS.MEDIA_FILE_ID.eq(file.getId()))
        .execute();

    assertThat(reader.find(file.getId())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should pair the outcome with its own positions when a publication commits between the two"
          + " reads")
  void shouldPairOutcomeWithItsOwnPositionsWhenPublicationCommitsBetweenTheTwoReads()
      throws Exception {
    var file = createMediaFile();
    var original = success(new KeyframeKnowledge.Known(twoHourSourcePositions()));
    repository.publish(publication(file.getId(), SNAPSHOT_A, original));
    var replacement =
        new ProbeOutcome.Success(
            ProbeContainer.builder().format(Optional.of("mp4")).build(),
            List.of(StreamInfo.builder().index(0).codecType("video").build()),
            new KeyframeKnowledge.Known(
                KeyframePositions.builder()
                    .streamIndex(0)
                    .timeBaseNumerator(1)
                    .timeBaseDenominator((int) TICKS_PER_SECOND)
                    .source(KeyframePositionSource.PACKET_SCAN)
                    .keyframes(List.of(new KeyframePosition(0, 0)))
                    .build()));

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var read =
          transactionTemplate.execute(
              _ -> {
                // The reader's first query reads the outcome row. Its keyframe read then waits for
                // this lock until the replacement commits.
                dsl.execute("LOCK TABLE media_file_keyframe_positions IN ACCESS EXCLUSIVE MODE");
                repository.publish(publication(file.getId(), SNAPSHOT_A, replacement));
                var pending = executor.submit(() -> reader.find(file.getId()));
                await()
                    .atMost(Duration.ofSeconds(10))
                    .until(this::hasReaderWaitingForKeyframePositions);
                return pending;
              });

      assertThat(read.get(10, TimeUnit.SECONDS))
          .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(original));
    }
  }

  @Test
  @DisplayName(
      "Should drop the stored positions when their bytes cannot be decoded, so that the new"
          + " outcome is readable")
  void shouldDropStoredPositionsWhenTheirBytesCannotBeDecoded() {
    var file = createMediaFile();
    storeScannedOutcome(file.getId(), EARLIER_VERSION);
    dsl.update(MEDIA_FILE_KEYFRAME_POSITIONS)
        .set(MEDIA_FILE_KEYFRAME_POSITIONS.ENCODED_TIMES, new byte[] {2})
        .where(MEDIA_FILE_KEYFRAME_POSITIONS.MEDIA_FILE_ID.eq(file.getId()))
        .execute();

    repository.publish(
        publicationOf(file.getId(), unscanned()).probeVersion(ProbeVersion.CURRENT).build());

    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(
            stored -> assertThat(keyframesOf(stored)).isEqualTo(unscanned().keyframes()));
    assertThat(positionsRowCount(file.getId())).isZero();
  }

  @Test
  @DisplayName(
      "Should store one of the two serial outcomes when a new probe and a scan publication of the"
          + " same file wait for its row together")
  void shouldStoreOneOfTwoSerialOutcomesWhenNewProbeAndScanPublicationWaitForTheFileTogether()
      throws Exception {
    var file = createMediaFile();
    repository.publish(
        publicationOf(file.getId(), unscanned()).probeVersion(EARLIER_VERSION).build());
    boolean scanPublished;

    try (var writers = Executors.newVirtualThreadPerTaskExecutor();
        var fileRow = PostgresLockTestSupport.lockRow(mediaFileRow(file.getId()))) {
      var scan =
          writers.submit(
              () ->
                  repository.publishKeyframePacketScan(
                      packetScan(
                              file.getId(),
                              SNAPSHOT_A,
                              new KeyframeKnowledge.Known(scannedPositions()))
                          .toBuilder()
                          .probeVersion(EARLIER_VERSION)
                          .build()));
      var probe =
          writers.submit(
              () ->
                  repository.publish(
                      publicationOf(file.getId(), unscanned())
                          .probeVersion(ProbeVersion.CURRENT)
                          .build()));
      PostgresLockTestSupport.awaitWaitersBehind(jdbc, fileRow.backendPid(), 2);
      fileRow.release();

      scanPublished = scan.get(10, TimeUnit.SECONDS);
      assertThat(probe.get(10, TimeUnit.SECONDS)).isTrue();
    }

    var keyframes = repository.findKeyframeKnowledge(file.getId());
    if (scanPublished) {
      assertThat(keyframes)
          .as("The scan published first, and the new probe kept its positions")
          .contains(new KeyframeKnowledge.Known(scannedPositions()));
    } else {
      assertThat(keyframes)
          .as("The new probe published first, and the earlier version's scan was refused")
          .contains(unscanned().keyframes());
      assertThat(positionsRowCount(file.getId())).isZero();
    }

    assertThat(owedPacketScanInputs(file.getId()))
        .contains(new ProbeInputs(SNAPSHOT_A, ProbeVersion.CURRENT));
  }

  @Test
  @DisplayName(
      "Should keep the positions of a scan publication that has not committed when the new probe"
          + " waits behind it")
  void shouldKeepPositionsOfUncommittedScanPublicationWhenNewProbeWaitsBehindIt() throws Exception {
    var file = createMediaFile();
    storeOutcomeKeepingScannedPositions(file.getId());
    var rescanned = new KeyframeKnowledge.Known(rescannedPositions());

    try (var writers = Executors.newVirtualThreadPerTaskExecutor()) {
      var probe =
          publishBehindUncommitted(
              () ->
                  repository.publishKeyframePacketScan(
                      packetScan(file.getId(), SNAPSHOT_A, rescanned).toBuilder()
                          .probeVersion(ProbeVersion.CURRENT)
                          .build()),
              () ->
                  writers.submit(
                      () ->
                          repository.publish(
                              publicationOf(file.getId(), unscanned())
                                  .probeVersion(ProbeVersion.CURRENT)
                                  .build())));

      assertThat(probe.get(10, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(repository.findKeyframeKnowledge(file.getId())).contains(rescanned);
    assertThat(owedPacketScanInputs(file.getId()))
        .as("The new probe still owes its own packet scan")
        .contains(new ProbeInputs(SNAPSHOT_A, ProbeVersion.CURRENT));
  }

  @Test
  @DisplayName(
      "Should replace the kept positions when the scan publication waits behind a new probe that"
          + " has not committed")
  void shouldReplaceKeptPositionsWhenScanPublicationWaitsBehindUncommittedNewProbe()
      throws Exception {
    var file = createMediaFile();
    storeOutcomeKeepingScannedPositions(file.getId());
    var rescanned = new KeyframeKnowledge.Known(rescannedPositions());

    try (var writers = Executors.newVirtualThreadPerTaskExecutor()) {
      var scan =
          publishBehindUncommitted(
              () ->
                  repository.publish(
                      publicationOf(file.getId(), unscanned())
                          .probeVersion(ProbeVersion.CURRENT)
                          .build()),
              () ->
                  writers.submit(
                      () ->
                          repository.publishKeyframePacketScan(
                              packetScan(file.getId(), SNAPSHOT_A, rescanned).toBuilder()
                                  .probeVersion(ProbeVersion.CURRENT)
                                  .build())));

      assertThat(scan.get(10, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(repository.findKeyframeKnowledge(file.getId())).contains(rescanned);
    assertThat(owedPacketScanInputs(file.getId())).isEmpty();
  }

  // Runs the first write in a transaction that stays open until the second write waits for a lock
  // that it holds, so the second write reads the first one's rows only once they are committed.
  private Future<Boolean> publishBehindUncommitted(
      Runnable uncommitted, Supplier<Future<Boolean>> waiting) throws Exception {
    try (var observer = dataSource.getConnection()) {
      return transactionTemplate.execute(
          _ -> {
            uncommitted.run();
            var holder =
                dsl.select(DSL.field("pg_backend_pid()", Integer.class)).fetchSingle().value1();
            var pending = waiting.get();
            await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> PostgresLockTestSupport.waitersBehind(observer, holder, "%") == 1);
            return pending;
          });
    }
  }

  private RowLockTarget mediaFileRow(UUID mediaFileId) {
    return RowLockTarget.builder()
        .dataSource(dataSource)
        .table("media_file")
        .rowId(mediaFileId)
        .build();
  }

  private Optional<ProbeInputs> owedPacketScanInputs(UUID mediaFileId) {
    return transactionTemplate.execute(_ -> repository.lockPacketScanInputs(mediaFileId));
  }

  // An outcome of an earlier probe version whose packet scan has stored its positions.
  private void storeScannedOutcome(UUID mediaFileId, int probeVersion) {
    assertThat(
            repository.publish(
                publicationOf(mediaFileId, unscanned()).probeVersion(probeVersion).build()))
        .isTrue();
    assertThat(
            repository.publishKeyframePacketScan(
                packetScan(mediaFileId, SNAPSHOT_A, new KeyframeKnowledge.Known(scannedPositions()))
                    .toBuilder()
                    .probeVersion(probeVersion)
                    .build()))
        .isTrue();
  }

  // An outcome of the current probe version that keeps the earlier version's scanned positions.
  private void storeOutcomeKeepingScannedPositions(UUID mediaFileId) {
    storeScannedOutcome(mediaFileId, EARLIER_VERSION);
    assertThat(
            repository.publish(
                publicationOf(mediaFileId, unscanned()).probeVersion(ProbeVersion.CURRENT).build()))
        .isTrue();
  }

  private static KeyframeKnowledge keyframesOf(PersistedProbeOutcome stored) {
    return ((ProbeOutcome.Success) stored.outcome()).keyframes();
  }

  private static KeyframePositions rescannedPositions() {
    return KeyframePositions.builder()
        .streamIndex(0)
        .timeBaseNumerator(1)
        .timeBaseDenominator((int) TICKS_PER_SECOND)
        .source(KeyframePositionSource.PACKET_SCAN)
        .keyframes(
            List.of(
                new KeyframePosition(0, 0),
                new KeyframePosition(3 * TICKS_PER_SECOND, 3 * TICKS_PER_SECOND)))
        .build();
  }

  private boolean hasReaderWaitingForKeyframePositions() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM pg_locks AS lock
              JOIN pg_class AS relation ON relation.oid = lock.relation
              WHERE relation.relname = 'media_file_keyframe_positions'
                AND NOT lock.granted
            )
            """,
            Boolean.class));
  }

  private static KeyframePositions scannedPositions() {
    return KeyframePositions.builder()
        .streamIndex(0)
        .timeBaseNumerator(1)
        .timeBaseDenominator((int) TICKS_PER_SECOND)
        .source(KeyframePositionSource.PACKET_SCAN)
        .keyframes(
            List.of(
                new KeyframePosition(0, 0),
                new KeyframePosition(TICKS_PER_SECOND, TICKS_PER_SECOND)))
        .build();
  }

  private static ProbeOutcome.Success packetScanRequired() {
    return success(
        new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX));
  }

  private static ProbeOutcome.Success unscanned() {
    return chosenStreamOutcome(
        new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX));
  }

  // An outcome that names stream 0 as its chosen video stream, as every outcome from probe
  // version 4 does.
  private static ProbeOutcome.Success chosenStreamOutcome(KeyframeKnowledge keyframes) {
    return ProbeOutcome.Success.builder()
        .container(ProbeContainer.builder().build())
        .streams(List.of(StreamInfo.builder().index(0).codecType("video").build()))
        .keyframes(keyframes)
        .chosenVideoStream(chosenStream(0))
        .build();
  }

  private static Optional<ChosenVideoStream> chosenStream(int streamIndex) {
    return Optional.of(
        new ChosenVideoStream(
            streamIndex, VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1))));
  }

  // A publication for SNAPSHOT_A, whose probe version the caller names.
  private static ProbePublication.ProbePublicationBuilder publicationOf(
      UUID mediaFileId, ProbeOutcome outcome) {
    return ProbePublication.builder()
        .mediaFileId(mediaFileId)
        .snapshot(SNAPSHOT_A)
        .outcome(outcome);
  }

  private static KeyframePacketScanPublication packetScan(
      UUID mediaFileId, SourceFileSnapshot snapshot, KeyframeKnowledge keyframes) {
    return KeyframePacketScanPublication.builder()
        .mediaFileId(mediaFileId)
        .snapshot(snapshot)
        .probeVersion(1)
        .keyframes(keyframes)
        .build();
  }

  private static KeyframePositions twoHourSourcePositions() {
    return KeyframePositions.builder()
        .streamIndex(0)
        .timeBaseNumerator(1)
        .timeBaseDenominator((int) TICKS_PER_SECOND)
        .source(KeyframePositionSource.CONTAINER_INDEX)
        .keyframes(
            LongStream.range(0, 3_600)
                .mapToObj(
                    keyframe -> {
                      var decodeTime = keyframe * 2 * TICKS_PER_SECOND - 3_750;
                      return new KeyframePosition(
                          decodeTime + B_FRAME_PRESENTATION_DELAY, decodeTime);
                    })
                .toList())
        .build();
  }

  private MediaFile createMediaFile() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var file =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("keyframes.mkv")
                .filepathUri("file:///library/" + UUID.randomUUID() + ".mkv")
                .size(1234)
                .build());
    createdFiles.add(file);
    return file;
  }

  private int positionsRowCount(UUID mediaFileId) {
    return dsl.fetchCount(
        MEDIA_FILE_KEYFRAME_POSITIONS, MEDIA_FILE_KEYFRAME_POSITIONS.MEDIA_FILE_ID.eq(mediaFileId));
  }

  private static ProbePublication publication(
      UUID mediaFileId, SourceFileSnapshot snapshot, ProbeOutcome outcome) {
    return ProbePublication.builder()
        .mediaFileId(mediaFileId)
        .snapshot(snapshot)
        .probeVersion(1)
        .outcome(outcome)
        .build();
  }

  private static ProbeOutcome.Success success(KeyframeKnowledge keyframes) {
    return new ProbeOutcome.Success(
        ProbeContainer.builder().build(),
        List.of(StreamInfo.builder().index(0).codecType("video").build()),
        keyframes);
  }
}
