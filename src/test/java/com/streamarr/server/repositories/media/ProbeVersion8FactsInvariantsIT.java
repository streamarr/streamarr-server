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
import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionEnhancementLayer;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.support.ConstraintViolations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
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
 * PostgreSQL enforcement of the probe version 8 facts that V121 stores. Each case starts from a
 * published outcome whose Dolby Vision video stream 0 stores a maximum frame rate of 60 fps, a MEL
 * enhancement layer and level 6 values of 1,000 and 400 cd/m², and whose audio stream 1 stores none
 * of them, and changes one value so that the row breaks one invariant.
 */
@Tag("IntegrationTest")
@DisplayName("Probe Version 8 Facts Invariants Integration Tests")
class ProbeVersion8FactsInvariantsIT extends AbstractIntegrationTest {

  private static final SourceFileSnapshot SNAPSHOT =
      new SourceFileSnapshot(4321, Instant.parse("2026-11-05T10:00:00Z"));
  private static final int VIDEO = 0;
  private static final int AUDIO = 1;
  private static final Set<String> V121_CONSTRAINTS =
      Set.of(
          "maximum_frame_rate_has_positive_terms",
          "complete_maximum_frame_rate",
          "maximum_frame_rate_requires_video_stream",
          "rpu_enhancement_layer_is_classified",
          "rpu_light_levels_in_range",
          "complete_rpu_light_levels",
          "rpu_facts_require_dolby_vision");

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
  @MethodSource("brokenFacts")
  @DisplayName("Should refuse a stored row when its probe version 8 fact breaks an invariant")
  void shouldRefuseAStoredRowWhenItsProbeVersion8FactBreaksAnInvariant(BrokenFact broken) {
    var mediaFileId = publishDolbyVisionOutcome();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ -> broken.update().apply(dsl, mediaFileId).execute()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(ConstraintViolations::constraintName)
        .isEqualTo(broken.constraint());
  }

  // V121 adds each column in the same statement as its constraints, so every existing row is NULL
  // and satisfies them.
  @Test
  @DisplayName("Should leave every probe version 8 constraint unvalidated when V121 adds it")
  void shouldLeaveEveryProbeVersion8ConstraintUnvalidatedWhenV121AddsIt() {
    var validated =
        dsl.fetch(
                """
                SELECT conname, conrelid::regclass::text, convalidated FROM pg_constraint
                WHERE conname = ANY(?::text[])
                """,
                (Object) V121_CONSTRAINTS.toArray(String[]::new))
            .map(row -> List.of(row.get(0), row.get(1), row.get(2)));

    assertThat(validated)
        .containsExactlyInAnyOrderElementsOf(
            V121_CONSTRAINTS.stream()
                .map(
                    constraint ->
                        List.<Object>of(constraint, MEDIA_FILE_STREAM_INFO.getName(), false))
                .toList());
  }

  static Stream<BrokenFact> brokenFacts() {
    return Stream.of(
        BrokenFact.builder()
            .description("a maximum frame rate of zero")
            .constraint("maximum_frame_rate_has_positive_terms")
            .update(set(VIDEO, MEDIA_FILE_STREAM_INFO.MAXIMUM_FRAME_RATE_NUMERATOR, 0))
            .build(),
        BrokenFact.builder()
            .description("a negative maximum frame rate denominator")
            .constraint("maximum_frame_rate_has_positive_terms")
            .update(set(VIDEO, MEDIA_FILE_STREAM_INFO.MAXIMUM_FRAME_RATE_DENOMINATOR, -1))
            .build(),
        BrokenFact.builder()
            .description("a maximum frame rate without its denominator")
            .constraint("complete_maximum_frame_rate")
            .update(set(VIDEO, MEDIA_FILE_STREAM_INFO.MAXIMUM_FRAME_RATE_DENOMINATOR, null))
            .build(),
        BrokenFact.builder()
            .description("a maximum frame rate on an audio stream")
            .constraint("maximum_frame_rate_requires_video_stream")
            .update(
                (context, mediaFileId) ->
                    context
                        .update(MEDIA_FILE_STREAM_INFO)
                        .set(MEDIA_FILE_STREAM_INFO.MAXIMUM_FRAME_RATE_NUMERATOR, DSL.val(60))
                        .set(MEDIA_FILE_STREAM_INFO.MAXIMUM_FRAME_RATE_DENOMINATOR, DSL.val(1))
                        .where(MEDIA_FILE_STREAM_INFO.MEDIA_FILE_ID.eq(mediaFileId))
                        .and(MEDIA_FILE_STREAM_INFO.STREAM_INDEX.eq(AUDIO)))
            .build(),
        BrokenFact.builder()
            .description("an unspecified enhancement layer, which the server stores as NULL")
            .constraint("rpu_enhancement_layer_is_classified")
            .update(set(VIDEO, MEDIA_FILE_STREAM_INFO.RPU_ENHANCEMENT_LAYER, "UNSPECIFIED"))
            .build(),
        BrokenFact.builder()
            .description("a MaxCLL above 65535 cd/m²")
            .constraint("rpu_light_levels_in_range")
            .update(set(VIDEO, MEDIA_FILE_STREAM_INFO.RPU_MAX_CONTENT_LIGHT_LEVEL, 65_536))
            .build(),
        BrokenFact.builder()
            .description("a negative MaxFALL")
            .constraint("rpu_light_levels_in_range")
            .update(set(VIDEO, MEDIA_FILE_STREAM_INFO.RPU_MAX_FRAME_AVERAGE_LIGHT_LEVEL, -1))
            .build(),
        BrokenFact.builder()
            .description("a MaxCLL without its MaxFALL")
            .constraint("complete_rpu_light_levels")
            .update(set(VIDEO, MEDIA_FILE_STREAM_INFO.RPU_MAX_FRAME_AVERAGE_LIGHT_LEVEL, null))
            .build(),
        BrokenFact.builder()
            .description("an enhancement layer on a stream without Dolby Vision")
            .constraint("rpu_facts_require_dolby_vision")
            .update(set(AUDIO, MEDIA_FILE_STREAM_INFO.RPU_ENHANCEMENT_LAYER, "MEL"))
            .build(),
        BrokenFact.builder()
            .description("level 6 values on a stream without Dolby Vision")
            .constraint("rpu_facts_require_dolby_vision")
            .update(
                (context, mediaFileId) ->
                    context
                        .update(MEDIA_FILE_STREAM_INFO)
                        .set(MEDIA_FILE_STREAM_INFO.RPU_MAX_CONTENT_LIGHT_LEVEL, DSL.val(1_000))
                        .set(MEDIA_FILE_STREAM_INFO.RPU_MAX_FRAME_AVERAGE_LIGHT_LEVEL, DSL.val(400))
                        .where(MEDIA_FILE_STREAM_INFO.MEDIA_FILE_ID.eq(mediaFileId))
                        .and(MEDIA_FILE_STREAM_INFO.STREAM_INDEX.eq(AUDIO)))
            .build());
  }

  private static <T> FactUpdate set(int streamIndex, Field<T> field, T value) {
    return (context, mediaFileId) ->
        context
            .update(MEDIA_FILE_STREAM_INFO)
            .set(field, DSL.val(value, field))
            .where(MEDIA_FILE_STREAM_INFO.MEDIA_FILE_ID.eq(mediaFileId))
            .and(MEDIA_FILE_STREAM_INFO.STREAM_INDEX.eq(streamIndex));
  }

  /** One statement that makes the published outcome break exactly one invariant. */
  @Builder
  record BrokenFact(
      @NonNull String description, @NonNull String constraint, @NonNull FactUpdate update) {

    @Override
    public String toString() {
      return description;
    }
  }

  @FunctionalInterface
  interface FactUpdate {
    Query apply(DSLContext dsl, UUID mediaFileId);
  }

  private UUID publishDolbyVisionOutcome() {
    var mediaFileId = createMediaFile();
    var dolbyVision =
        DolbyVision.Present.builder()
            .observedRpu(Presence.PRESENT)
            .enhancementLayer(DolbyVisionEnhancementLayer.MEL)
            .rpuContentLightLevel(Optional.of(new ContentLightLevel(1_000, 400)))
            .build();
    var video =
        StreamInfo.builder()
            .index(VIDEO)
            .codecType("video")
            .codec(Optional.of("hevc"))
            .dynamicRange(
                Optional.of(new VideoDynamicRange(CompatibleBase.PQ, Presence.ABSENT, dolbyVision)))
            .maximumFrameRate(Optional.of(new Fraction(60, 1)))
            .build();
    var audio =
        StreamInfo.builder().index(AUDIO).codecType("audio").codec(Optional.of("aac")).build();
    repository.publish(
        ProbePublication.builder()
            .mediaFileId(mediaFileId)
            .snapshot(SNAPSHOT)
            .probeVersion(ProbeVersion.FIRST_TO_REPORT_MAXIMUM_FRAME_RATE_AND_RPU_FACTS)
            .outcome(
                ProbeOutcome.Success.builder()
                    .container(
                        ProbeContainer.builder().format(Optional.of("matroska,webm")).build())
                    .streams(List.of(video, audio))
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
                .filename("probe-version-8-facts.mkv")
                .filepathUri("file:///library/" + UUID.randomUUID() + ".mkv")
                .size(4321)
                .build());
    createdFiles.add(file);
    return file.getId();
  }
}
