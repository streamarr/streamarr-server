package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.MediaFileStreamInfo.MEDIA_FILE_STREAM_INFO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.AudioStreamProperties;
import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.support.ConstraintViolations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.NonNull;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL enforcement of the probe version 7 facts that V112 stores. Each case starts from a
 * published outcome whose video stream 0 has no audio fact, whose FLAC stream 1 holds every audio
 * fact, and whose subtitle stream 2 holds its commentary flag, and changes the facts of one stream
 * so that the row breaks one invariant.
 */
@Tag("IntegrationTest")
@DisplayName("Probe Audio Facts Invariants Integration Tests")
class ProbeAudioFactsInvariantsIT extends AbstractIntegrationTest {

  private static final SourceFileSnapshot SNAPSHOT =
      new SourceFileSnapshot(4321, Instant.parse("2026-11-02T10:00:00Z"));
  private static final int VIDEO = 0;
  private static final int FLAC = 1;
  private static final int SUBTITLE = 2;
  private static final List<String> V112_CONSTRAINTS =
      List.of(
          "complete_audio_stream_properties",
          "audio_facts_require_audio_stream_properties",
          "comment_requires_audio_or_subtitle_stream",
          "audio_comment_requires_audio_stream_properties",
          "flac_minimum_block_size_requires_flac",
          "flac_minimum_block_size_fits_streaminfo");

  @Autowired private MediaFileContainerInfoRepository repository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate transactionTemplate;

  private final List<MediaFile> createdFiles = new ArrayList<>();
  private final List<Library> createdLibraries = new ArrayList<>();

  @AfterEach
  void removeCreatedFiles() {
    mediaFileRepository.deleteAll(createdFiles);
    libraryRepository.deleteAll(createdLibraries);
    createdFiles.clear();
    createdLibraries.clear();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("brokenAudioFacts")
  @DisplayName("Should refuse a stored stream when its audio facts break an invariant")
  void shouldRefuseAStoredStreamWhenItsAudioFactsBreakAnInvariant(BrokenAudioFacts broken) {
    var mediaFileId = publishOutcomeWithEveryAudioFact();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ ->
                        dsl.update(MEDIA_FILE_STREAM_INFO)
                            .set(broken.columns())
                            .where(MEDIA_FILE_STREAM_INFO.MEDIA_FILE_ID.eq(mediaFileId))
                            .and(MEDIA_FILE_STREAM_INFO.STREAM_INDEX.eq(broken.streamIndex()))
                            .execute()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(ConstraintViolations::constraintName)
        .isEqualTo(broken.constraint());
  }

  // V112 adds each column in the same statement as its constraint, so every existing row is NULL
  // and satisfies the constraint. Validating them would only scan the table under ACCESS
  // EXCLUSIVE.
  @Test
  @DisplayName("Should leave every audio fact constraint unvalidated when V112 adds it")
  void shouldLeaveEveryAudioFactConstraintUnvalidatedWhenV112AddsIt() {
    var validated =
        dsl.fetch(
                """
                SELECT conname, convalidated FROM pg_constraint
                WHERE conrelid = ?::regclass AND conname = ANY(?::text[])
                """,
                MEDIA_FILE_STREAM_INFO.getName(),
                V112_CONSTRAINTS.toArray(String[]::new))
            .intoMap(record -> record.get(0, String.class), record -> record.get(1, Boolean.class));

    assertThat(validated)
        .containsOnlyKeys(V112_CONSTRAINTS)
        .allSatisfy((_, isValidated) -> assertThat(isValidated).isFalse());
  }

  static Stream<BrokenAudioFacts> brokenAudioFacts() {
    var stream = MEDIA_FILE_STREAM_INFO;
    return Stream.of(
        broken(
            "one audio disposition missing beside the other five",
            "complete_audio_stream_properties",
            FLAC,
            Map.of(stream.DUB, nullOf(stream.DUB))),
        broken(
            "audio dispositions without the commentary flag",
            "complete_audio_stream_properties",
            FLAC,
            Map.of(stream.COMMENT, nullOf(stream.COMMENT))),
        broken(
            "audio dispositions on a subtitle stream",
            "complete_audio_stream_properties",
            SUBTITLE,
            everyAudioDisposition(DSL.val(false))),
        broken(
            "a channel layout on a stream without audio dispositions",
            "audio_facts_require_audio_stream_properties",
            SUBTITLE,
            Map.of(stream.CHANNEL_LAYOUT, DSL.val("stereo"))),
        broken(
            "a sample format on a stream without audio dispositions",
            "audio_facts_require_audio_stream_properties",
            SUBTITLE,
            Map.of(stream.SAMPLE_FORMAT, DSL.val("s16"))),
        broken(
            "a commentary flag on a video stream",
            "comment_requires_audio_or_subtitle_stream",
            VIDEO,
            Map.of(stream.COMMENT, DSL.val(false))),
        broken(
            "an audio stream's commentary flag without its other dispositions",
            "audio_comment_requires_audio_stream_properties",
            FLAC,
            audioFactsWithoutDispositions()),
        broken(
            "a FLAC minimum block size on an AAC stream",
            "flac_minimum_block_size_requires_flac",
            FLAC,
            Map.of(stream.CODEC, DSL.val("aac"))),
        broken(
            "a FLAC minimum block size on a stream without a codec",
            "flac_minimum_block_size_requires_flac",
            FLAC,
            Map.of(stream.CODEC, nullOf(stream.CODEC))),
        broken(
            "a FLAC minimum block size beyond 16 bits",
            "flac_minimum_block_size_fits_streaminfo",
            FLAC,
            Map.of(stream.FLAC_MINIMUM_BLOCK_SIZE_SAMPLES, DSL.val(65_536))),
        broken(
            "a negative FLAC minimum block size",
            "flac_minimum_block_size_fits_streaminfo",
            FLAC,
            Map.of(stream.FLAC_MINIMUM_BLOCK_SIZE_SAMPLES, DSL.val(-1))));
  }

  private static Map<Field<?>, Field<?>> everyAudioDisposition(Field<Boolean> value) {
    var stream = MEDIA_FILE_STREAM_INFO;
    var columns = new HashMap<Field<?>, Field<?>>();
    Stream.of(
            stream.VISUAL_IMPAIRED,
            stream.DESCRIPTIONS,
            stream.ORIGINAL,
            stream.DUB,
            stream.CLEAN_EFFECTS,
            stream.DEPENDENT)
        .forEach(column -> columns.put(column, value));
    return columns;
  }

  // Clears every audio fact but the commentary flag, so that no other constraint refuses the row.
  private static Map<Field<?>, Field<?>> audioFactsWithoutDispositions() {
    var stream = MEDIA_FILE_STREAM_INFO;
    var columns = everyAudioDisposition(nullOf(stream.DUB));
    columns.put(stream.CHANNEL_LAYOUT, nullOf(stream.CHANNEL_LAYOUT));
    columns.put(stream.SAMPLE_FORMAT, nullOf(stream.SAMPLE_FORMAT));
    columns.put(
        stream.FLAC_MINIMUM_BLOCK_SIZE_SAMPLES, nullOf(stream.FLAC_MINIMUM_BLOCK_SIZE_SAMPLES));
    return columns;
  }

  /** Columns of one stream, set together so that the stored row breaks exactly one invariant. */
  record BrokenAudioFacts(
      @NonNull String description,
      @NonNull String constraint,
      int streamIndex,
      @NonNull Map<? extends Field<?>, ? extends Field<?>> columns) {

    @Override
    public String toString() {
      return description;
    }
  }

  private static BrokenAudioFacts broken(
      String description,
      String constraint,
      int streamIndex,
      Map<? extends Field<?>, ? extends Field<?>> columns) {
    return new BrokenAudioFacts(description, constraint, streamIndex, columns);
  }

  private static <T> Field<T> nullOf(Field<T> column) {
    return DSL.val(null, column);
  }

  private UUID publishOutcomeWithEveryAudioFact() {
    var mediaFileId = createMediaFile();
    var video =
        StreamInfo.builder()
            .index(VIDEO)
            .codecType("video")
            .codec(Optional.of("h264"))
            .width(OptionalInt.of(1920))
            .height(OptionalInt.of(1080))
            .build();
    var flac =
        StreamInfo.builder()
            .index(FLAC)
            .codecType("audio")
            .codec(Optional.of("flac"))
            .channels(OptionalInt.of(4))
            .bitDepth(OptionalInt.of(24))
            .comment(Optional.of(false))
            .audioProperties(
                Optional.of(
                    AudioStreamProperties.builder()
                        .channelLayout(Optional.of("quad"))
                        .sampleFormat(Optional.of("s32"))
                        .original(true)
                        .flacMinimumBlockSizeSamples(OptionalInt.of(4_608))
                        .build()))
            .build();
    var subtitle =
        StreamInfo.builder()
            .index(SUBTITLE)
            .codecType("subtitle")
            .codec(Optional.of("subrip"))
            .comment(Optional.of(true))
            .build();
    repository.publish(
        ProbePublication.builder()
            .mediaFileId(mediaFileId)
            .snapshot(SNAPSHOT)
            .probeVersion(ProbeVersion.FIRST_TO_REPORT_AUDIO_FACTS)
            .outcome(
                ProbeOutcome.Success.builder()
                    .container(
                        ProbeContainer.builder().format(Optional.of("matroska,webm")).build())
                    .streams(List.of(video, flac, subtitle))
                    .keyframes(new KeyframeKnowledge.NotReported())
                    .chosenVideoStream(
                        Optional.of(
                            new ChosenVideoStream(
                                VIDEO,
                                VideoPlaybackFactsFixture.declaredRateRule(
                                    VideoPlaybackFactsFixture.FILM_RATE))))
                    .build())
            .build());
    return mediaFileId;
  }

  private UUID createMediaFile() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    createdLibraries.add(library);
    var file =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("audio-facts.mkv")
                .filepathUri("file:///library/" + UUID.randomUUID() + ".mkv")
                .size(4321)
                .build());
    createdFiles.add(file);
    return file.getId();
  }
}
