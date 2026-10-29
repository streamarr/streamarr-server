package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.MediaFileStreamInfo.MEDIA_FILE_STREAM_INFO;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.ChromaticityCoordinates;
import com.streamarr.server.domain.streaming.ColorPrimaries;
import com.streamarr.server.domain.streaming.ColorRange;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionRpuHeader;
import com.streamarr.server.domain.streaming.DolbyVisionRpuPlacement;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MasteringDisplayColorVolume;
import com.streamarr.server.domain.streaming.MatrixCoefficients;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.TransferCharacteristics;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL enforcement of the probe version 5 frame facts that V107 stores. Each case starts from
 * a published video stream row that holds every frame fact, and changes the facts that one
 * invariant guards.
 */
@Tag("IntegrationTest")
@DisplayName("Probe Frame Facts Invariants Integration Tests")
class ProbeFrameFactsInvariantsIT extends AbstractIntegrationTest {

  private static final SourceFileSnapshot SNAPSHOT =
      new SourceFileSnapshot(4321, Instant.parse("2026-10-29T10:00:00Z"));

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
  @MethodSource("brokenFrameFacts")
  @DisplayName("Should refuse a stored video stream when its frame facts break an invariant")
  void shouldRefuseAStoredVideoStreamWhenItsFrameFactsBreakAnInvariant(BrokenFrameFacts broken) {
    var mediaFileId = publishVideoStreamWithEveryFrameFact();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ ->
                        dsl.update(MEDIA_FILE_STREAM_INFO)
                            .set(broken.columns())
                            .where(MEDIA_FILE_STREAM_INFO.MEDIA_FILE_ID.eq(mediaFileId))
                            .and(MEDIA_FILE_STREAM_INFO.STREAM_INDEX.eq(0))
                            .execute()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(ConstraintViolations::constraintName)
        .isEqualTo(broken.constraint());
  }

  static Stream<BrokenFrameFacts> brokenFrameFacts() {
    var stream = MEDIA_FILE_STREAM_INFO;
    return Stream.of(
        broken(
            "a frame read without its mastering display omission flag",
            "complete_video_stream_properties",
            Map.of(stream.MASTERING_DISPLAY_OMITTED, nullOf(stream.MASTERING_DISPLAY_OMITTED))),
        broken(
            "a frame read without its content light level omission flag",
            "complete_video_stream_properties",
            Map.of(stream.CONTENT_LIGHT_LEVEL_OMITTED, nullOf(stream.CONTENT_LIGHT_LEVEL_OMITTED))),
        broken(
            "a codec property on a stream without the probe version 5 flags",
            "complete_video_stream_properties",
            Map.of(
                stream.FIRST_FRAMES_READ, nullOf(stream.FIRST_FRAMES_READ),
                stream.MASTERING_DISPLAY_OMITTED, nullOf(stream.MASTERING_DISPLAY_OMITTED),
                stream.CONTENT_LIGHT_LEVEL_OMITTED, nullOf(stream.CONTENT_LIGHT_LEVEL_OMITTED),
                stream.FIRST_PACKETS_CARRY_PRESENTATION_TIMES,
                    nullOf(stream.FIRST_PACKETS_CARRY_PRESENTATION_TIMES))),
        broken(
            "a codec level and tier without a codec profile",
            "codec_level_and_tier_require_codec_profile",
            Map.of(stream.CODEC_PROFILE, nullOf(stream.CODEC_PROFILE))),
        broken(
            "an omitted mastering display that the row still lists",
            "omitted_mastering_display_is_not_listed",
            Map.of(stream.MASTERING_DISPLAY_OMITTED, DSL.val(true))),
        broken(
            "an omitted content light level that the row still lists",
            "omitted_content_light_level_is_not_listed",
            Map.of(stream.CONTENT_LIGHT_LEVEL_OMITTED, DSL.val(true))),
        broken(
            "a first RPU header without its bit depth",
            "complete_first_rpu_header",
            Map.of(stream.FIRST_RPU_VDR_BIT_DEPTH, nullOf(stream.FIRST_RPU_VDR_BIT_DEPTH))),
        broken(
            "an RPU header and placement beside an absent observed RPU",
            "rpu_details_require_observed_rpu",
            Map.of(stream.DOLBY_VISION_OBSERVED_RPU, DSL.val("ABSENT"))),
        broken(
            "presentation times on a stream whose first frames the probe did not read",
            "presentation_times_require_first_frames_read",
            Map.of(stream.FIRST_FRAMES_READ, DSL.val(false))),
        broken(
            "a codec level of 0",
            "media_file_stream_info_codec_level_check",
            Map.of(stream.CODEC_LEVEL, DSL.val(0))),
        broken(
            "an RPU profile below 0",
            "media_file_stream_info_first_rpu_vdr_rpu_profile_check",
            Map.of(stream.FIRST_RPU_VDR_RPU_PROFILE, DSL.val(-1))),
        broken(
            "an RPU bit depth below 8",
            "media_file_stream_info_first_rpu_vdr_bit_depth_check",
            Map.of(stream.FIRST_RPU_VDR_BIT_DEPTH, DSL.val(7))),
        broken(
            "a codec profile that the contract does not name",
            "media_file_stream_info_codec_profile_check",
            Map.of(stream.CODEC_PROFILE, DSL.val("VP9_PROFILE_0"))),
        broken(
            "a codec tier that the contract does not name",
            "media_file_stream_info_codec_tier_check",
            Map.of(stream.CODEC_TIER, DSL.val("BASELINE"))),
        broken(
            "color primaries that the contract does not name",
            "media_file_stream_info_color_primaries_check",
            Map.of(stream.COLOR_PRIMARIES, DSL.val("BT2100"))),
        broken(
            "a transfer that the contract does not name",
            "media_file_stream_info_transfer_characteristics_check",
            Map.of(stream.TRANSFER_CHARACTERISTICS, DSL.val("PQ"))),
        broken(
            "matrix coefficients that the contract does not name",
            "media_file_stream_info_matrix_coefficients_check",
            Map.of(stream.MATRIX_COEFFICIENTS, DSL.val("RGB"))),
        broken(
            "a color range that the contract does not name",
            "media_file_stream_info_color_range_check",
            Map.of(stream.COLOR_RANGE, DSL.val("NARROW"))),
        broken(
            "an unspecified RPU placement, which the row keeps as NULL",
            "media_file_stream_info_dolby_vision_rpu_placement_check",
            Map.of(stream.DOLBY_VISION_RPU_PLACEMENT, DSL.val("UNSPECIFIED"))));
  }

  /** Columns set together so that the stored row breaks exactly one invariant. */
  record BrokenFrameFacts(
      @NonNull String description,
      @NonNull String constraint,
      @NonNull Map<? extends Field<?>, ? extends Field<?>> columns) {

    @Override
    public String toString() {
      return description;
    }
  }

  private static BrokenFrameFacts broken(
      String description, String constraint, Map<? extends Field<?>, ? extends Field<?>> columns) {
    return new BrokenFrameFacts(description, constraint, columns);
  }

  private static <T> Field<T> nullOf(Field<T> column) {
    return DSL.val(null, column);
  }

  // An HDR10 stream with Dolby Vision RPUs that start their samples, whose probe named every
  // codec and color property, read its first frames and packets, and omitted no static metadata.
  private UUID publishVideoStreamWithEveryFrameFact() {
    var mediaFileId = createMediaFile();
    var video =
        StreamInfo.builder()
            .index(0)
            .codecType("video")
            .codec(Optional.of("hevc"))
            .width(OptionalInt.of(3840))
            .height(OptionalInt.of(2160))
            .dynamicRange(
                Optional.of(
                    new VideoDynamicRange(
                        CompatibleBase.PQ,
                        Presence.ABSENT,
                        new DolbyVision.Present(
                            Optional.empty(),
                            Presence.PRESENT,
                            Optional.of(
                                DolbyVisionRpuHeader.builder()
                                    .vdrRpuProfile(1)
                                    .baseLayerFullRange(false)
                                    .enhancementLayerSpatialResamplingFilter(false)
                                    .disableResidual(true)
                                    .vdrBitDepth(12)
                                    .build()),
                            DolbyVisionRpuPlacement.STARTS_SAMPLE))))
            .masteringDisplay(
                Optional.of(
                    MasteringDisplayColorVolume.builder()
                        .red(new ChromaticityCoordinates(34_000, 16_000))
                        .green(new ChromaticityCoordinates(13_250, 34_500))
                        .blue(new ChromaticityCoordinates(7_500, 3_000))
                        .whitePoint(new ChromaticityCoordinates(15_635, 16_450))
                        .maxLuminance(10_000_000)
                        .minLuminance(50)
                        .build()))
            .contentLightLevel(Optional.of(new ContentLightLevel(1_000, 400)))
            .videoProperties(
                Optional.of(
                    VideoStreamProperties.builder()
                        .codecProfile(Optional.of(VideoCodecProfile.HEVC_MAIN_10))
                        .codecLevel(OptionalInt.of(51))
                        .codecTier(Optional.of(VideoCodecTier.MAIN))
                        .colorPrimaries(Optional.of(ColorPrimaries.BT2020))
                        .transferCharacteristics(Optional.of(TransferCharacteristics.SMPTE2084))
                        .matrixCoefficients(Optional.of(MatrixCoefficients.BT2020_NCL))
                        .colorRange(Optional.of(ColorRange.LIMITED))
                        .firstFramesRead(true)
                        .firstPacketsCarryPresentationTimes(Optional.of(true))
                        .build()))
            .build();
    repository.publish(
        ProbePublication.builder()
            .mediaFileId(mediaFileId)
            .snapshot(SNAPSHOT)
            .probeVersion(ProbeVersion.FIRST_TO_REPORT_FRAME_FACTS)
            .outcome(
                ProbeOutcome.Success.builder()
                    .container(
                        ProbeContainer.builder().format(Optional.of("matroska,webm")).build())
                    .streams(List.of(video))
                    .keyframes(new KeyframeKnowledge.NotReported())
                    .chosenVideoStream(
                        Optional.of(
                            new ChosenVideoStream(
                                0,
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
                .filename("frame-facts.mkv")
                .filepathUri("file:///library/" + UUID.randomUUID() + ".mkv")
                .size(4321)
                .build());
    createdFiles.add(file);
    return file.getId();
  }
}
