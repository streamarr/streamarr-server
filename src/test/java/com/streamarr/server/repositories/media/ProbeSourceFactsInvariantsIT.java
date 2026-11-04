package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.MediaFileContainerInfo.MEDIA_FILE_CONTAINER_INFO;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.jooq.DSLContext;
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
 * PostgreSQL enforcement of the probe version 7 source facts that V113 stores. Each case starts
 * from a published MP4 outcome whose video stream 0 stores the sample entry hvc1, whose audio
 * stream 1 stores none, and whose container stores the major brand isom, and changes one value so
 * that the row breaks one invariant.
 */
@Tag("IntegrationTest")
@DisplayName("Probe Source Facts Invariants Integration Tests")
class ProbeSourceFactsInvariantsIT extends AbstractIntegrationTest {

  private static final SourceFileSnapshot SNAPSHOT =
      new SourceFileSnapshot(4321, Instant.parse("2026-11-04T10:00:00Z"));
  private static final int VIDEO = 0;
  private static final int AUDIO = 1;
  private static final Map<String, String> V113_CONSTRAINTS =
      Map.of(
          "sample_entry_requires_video_stream", MEDIA_FILE_STREAM_INFO.getName(),
          "sample_entry_has_four_printable_characters", MEDIA_FILE_STREAM_INFO.getName(),
          "major_brand_has_four_printable_characters", MEDIA_FILE_CONTAINER_INFO.getName());

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
  @MethodSource("brokenSourceFacts")
  @DisplayName("Should refuse a stored row when its source fact breaks an invariant")
  void shouldRefuseAStoredRowWhenItsSourceFactBreaksAnInvariant(BrokenSourceFact broken) {
    var mediaFileId = publishMp4Outcome();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ -> broken.update().apply(dsl, mediaFileId).execute()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(ConstraintViolations::constraintName)
        .isEqualTo(broken.constraint());
  }

  // V113 adds each column in the same statement as its constraint, so every existing row is NULL
  // and satisfies the constraint.
  @Test
  @DisplayName("Should leave every source fact constraint unvalidated when V113 adds it")
  void shouldLeaveEverySourceFactConstraintUnvalidatedWhenV113AddsIt() {
    var validated =
        dsl.fetch(
                """
                SELECT conname, conrelid::regclass::text, convalidated FROM pg_constraint
                WHERE conname = ANY(?::text[])
                """,
                (Object) V113_CONSTRAINTS.keySet().toArray(String[]::new))
            .map(row -> List.of(row.get(0), row.get(1), row.get(2)));

    assertThat(validated)
        .containsExactlyInAnyOrderElementsOf(
            V113_CONSTRAINTS.entrySet().stream()
                .map(
                    constraint ->
                        List.<Object>of(constraint.getKey(), constraint.getValue(), false))
                .toList());
  }

  static Stream<BrokenSourceFact> brokenSourceFacts() {
    return Stream.of(
        BrokenSourceFact.builder()
            .description("a sample entry on an audio stream")
            .constraint("sample_entry_requires_video_stream")
            .update(sampleEntry(AUDIO, "mp4a"))
            .build(),
        BrokenSourceFact.builder()
            .description("a three-character sample entry")
            .constraint("sample_entry_has_four_printable_characters")
            .update(sampleEntry(VIDEO, "hvc"))
            .build(),
        BrokenSourceFact.builder()
            .description("a sample entry with a letter beyond ASCII")
            .constraint("sample_entry_has_four_printable_characters")
            .update(sampleEntry(VIDEO, "hvéc"))
            .build(),
        BrokenSourceFact.builder()
            .description("a five-character major brand")
            .constraint("major_brand_has_four_printable_characters")
            .update(majorBrand("isom "))
            .build(),
        BrokenSourceFact.builder()
            .description("a major brand with a tab")
            .constraint("major_brand_has_four_printable_characters")
            .update(majorBrand("qt\t "))
            .build());
  }

  private static SourceFactUpdate sampleEntry(int streamIndex, String sampleEntry) {
    return (dsl, mediaFileId) ->
        dsl.update(MEDIA_FILE_STREAM_INFO)
            .set(MEDIA_FILE_STREAM_INFO.SAMPLE_ENTRY, DSL.val(sampleEntry))
            .where(MEDIA_FILE_STREAM_INFO.MEDIA_FILE_ID.eq(mediaFileId))
            .and(MEDIA_FILE_STREAM_INFO.STREAM_INDEX.eq(streamIndex));
  }

  private static SourceFactUpdate majorBrand(String majorBrand) {
    return (dsl, mediaFileId) ->
        dsl.update(MEDIA_FILE_CONTAINER_INFO)
            .set(MEDIA_FILE_CONTAINER_INFO.MAJOR_BRAND, DSL.val(majorBrand))
            .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(mediaFileId));
  }

  /** One statement that makes the published outcome break exactly one invariant. */
  @Builder
  record BrokenSourceFact(
      @NonNull String description, @NonNull String constraint, @NonNull SourceFactUpdate update) {

    @Override
    public String toString() {
      return description;
    }
  }

  @FunctionalInterface
  interface SourceFactUpdate {
    Query apply(DSLContext dsl, UUID mediaFileId);
  }

  private UUID publishMp4Outcome() {
    var mediaFileId = createMediaFile();
    var video =
        StreamInfo.builder()
            .index(VIDEO)
            .codecType("video")
            .codec(Optional.of("hevc"))
            .sampleEntry(Optional.of("hvc1"))
            .build();
    var audio =
        StreamInfo.builder().index(AUDIO).codecType("audio").codec(Optional.of("aac")).build();
    repository.publish(
        ProbePublication.builder()
            .mediaFileId(mediaFileId)
            .snapshot(SNAPSHOT)
            .probeVersion(ProbeVersion.FIRST_TO_REPORT_SOURCE_FACTS)
            .outcome(
                ProbeOutcome.Success.builder()
                    .container(
                        ProbeContainer.builder()
                            .format(Optional.of("mov,mp4,m4a,3gp,3g2,mj2"))
                            .majorBrand(Optional.of("isom"))
                            .build())
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
                .filename("source-facts.mp4")
                .filepathUri("file:///library/" + UUID.randomUUID() + ".mp4")
                .size(4321)
                .build());
    createdFiles.add(file);
    return file.getId();
  }
}
