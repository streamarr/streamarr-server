package com.streamarr.server.transcode.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.Type;
import com.streamarr.transcode.v1.BitstreamRewrite;
import com.streamarr.transcode.v1.ChromaticityCoordinates;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.ContentLightLevel;
import com.streamarr.transcode.v1.DolbyVisionConfiguration;
import com.streamarr.transcode.v1.DolbyVisionOutput;
import com.streamarr.transcode.v1.DolbyVisionPresent;
import com.streamarr.transcode.v1.DolbyVisionSampleEntry;
import com.streamarr.transcode.v1.MasteringDisplayColorVolume;
import com.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.VideoCopyFeature;
import com.streamarr.transcode.v1.VideoDecision;
import com.streamarr.transcode.v1.VideoDynamicRange;
import com.streamarr.transcode.v1.VideoMode;
import com.streamarr.transcode.v1.WorkerCapabilities;
import java.util.Arrays;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// ADR 0013 and ADR 0036 (third amendments, streamarr/streamarr-adr#93) decide what a copy's
// planned output carries and that each worker declares the bitstream rewrites it supports.
@Tag("UnitTest")
@DisplayName("Video Copy Worker Contract Tests")
class VideoCopyWorkerContractTest {

  @Test
  @DisplayName(
      "Should carry the planned range, rewrites, Dolby Vision output and static HDR metadata when"
          + " a job copies a profile 8.1 stream for a client that declares it")
  void
      shouldCarryThePlannedRangeRewritesDolbyVisionOutputAndStaticMetadataWhenAJobCopiesAProfile81Stream()
          throws Exception {
    var copy =
        profile81Copy()
            .addBitstreamRewrites(BitstreamRewrite.BITSTREAM_REWRITE_STRIP_HDR10_PLUS)
            .addBitstreamRewrites(BitstreamRewrite.BITSTREAM_REWRITE_NORMALIZE_DOLBY_VISION_RECORD)
            .setDolbyVisionOutput(
                DolbyVisionOutput.newBuilder()
                    .setProfile(8)
                    .setLevel(6)
                    .setBaseLayerCompatibilityId(1)
                    .setSampleEntry(DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_HVC1))
            .setMasteringDisplay(p3D65Display())
            .setContentLightLevel(
                ContentLightLevel.newBuilder()
                    .setMaxContentLightLevel(1000)
                    .setMaxFrameAverageLightLevel(400))
            .build();

    var decoded = VideoDecision.parseFrom(copy.toByteArray());

    assertThat(decoded.getOutputDynamicRange().getCompatibleBase())
        .isEqualTo(CompatibleBase.COMPATIBLE_BASE_PQ);
    assertThat(decoded.getBitstreamRewritesList())
        .containsExactly(
            BitstreamRewrite.BITSTREAM_REWRITE_STRIP_HDR10_PLUS,
            BitstreamRewrite.BITSTREAM_REWRITE_NORMALIZE_DOLBY_VISION_RECORD);
    assertThat(decoded.getDolbyVisionOutput())
        .extracting(
            DolbyVisionOutput::getProfile,
            DolbyVisionOutput::getLevel,
            DolbyVisionOutput::getBaseLayerCompatibilityId,
            DolbyVisionOutput::getSampleEntry)
        .containsExactly(8, 6, 1, DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_HVC1);
    assertThat(decoded.getMasteringDisplay()).isEqualTo(p3D65Display());
    assertThat(decoded.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
    assertThat(decoded.getContentLightLevel().getMaxFrameAverageLightLevel()).isEqualTo(400);
  }

  @Test
  @DisplayName(
      "Should tell a missing static HDR value apart from a zero when a PQ copy carries only the"
          + " content light level")
  void shouldTellAMissingStaticHdrValueApartFromAZeroWhenAPqCopyCarriesOnlyTheContentLightLevel()
      throws Exception {
    var copy = profile81Copy().setContentLightLevel(ContentLightLevel.getDefaultInstance()).build();

    var decoded = VideoDecision.parseFrom(copy.toByteArray());

    assertThat(decoded.hasContentLightLevel()).isTrue();
    assertThat(decoded.hasMasteringDisplay()).isFalse();
  }

  @Test
  @DisplayName(
      "Should read a copy from an older server as one that lists no rewrite and carries no"
          + " planned output when it decodes the job")
  void shouldReadACopyFromAnOlderServerAsOneThatListsNoRewriteAndCarriesNoPlannedOutput()
      throws Exception {
    var olderServerCopy =
        VideoDecision.newBuilder()
            .setMode(VideoMode.VIDEO_MODE_COPY)
            .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC)
            .setCodecString("hvc1.2.4.L150.90")
            .build();

    var decoded = VideoDecision.parseFrom(olderServerCopy.toByteArray());

    assertThat(decoded.getBitstreamRewritesList()).isEmpty();
    assertThat(decoded.hasOutputDynamicRange()).isFalse();
    assertThat(decoded.hasDolbyVisionOutput()).isFalse();
    assertThat(decoded.hasMasteringDisplay()).isFalse();
    assertThat(decoded.hasContentLightLevel()).isFalse();
  }

  @Test
  @DisplayName(
      "Should list the worker's bitstream rewrites and copy features when it registers, and none"
          + " when an older worker registers")
  void shouldListTheWorkersBitstreamRewritesAndCopyFeaturesWhenItRegisters() throws Exception {
    var declaring =
        WorkerCapabilities.newBuilder()
            .addBitstreamRewrites(BitstreamRewrite.BITSTREAM_REWRITE_STRIP_DOLBY_VISION)
            .addVideoCopyFeatures(VideoCopyFeature.VIDEO_COPY_FEATURE_PLANNED_OUTPUT)
            .addVideoCopyFeatures(VideoCopyFeature.VIDEO_COPY_FEATURE_DVH1_SAMPLE_ENTRY)
            .build();
    var older = WorkerCapabilities.newBuilder().addProbeVersions(7).build();

    var decodedDeclaring = WorkerCapabilities.parseFrom(declaring.toByteArray());
    var decodedOlder = WorkerCapabilities.parseFrom(older.toByteArray());

    assertThat(decodedDeclaring.getBitstreamRewritesList())
        .containsExactly(BitstreamRewrite.BITSTREAM_REWRITE_STRIP_DOLBY_VISION);
    assertThat(decodedDeclaring.getVideoCopyFeaturesList())
        .containsExactly(
            VideoCopyFeature.VIDEO_COPY_FEATURE_PLANNED_OUTPUT,
            VideoCopyFeature.VIDEO_COPY_FEATURE_DVH1_SAMPLE_ENTRY);
    assertThat(decodedOlder.getBitstreamRewritesList()).isEmpty();
    assertThat(decodedOlder.getVideoCopyFeaturesList()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when publishing the"
          + " video copy contract")
  void
      shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingTheVideoCopyContract() {
    assertThat(fields(VideoDecision.getDescriptor(), "output_dynamic_range"))
        .containsExactly(tuple("output_dynamic_range", 8, Type.MESSAGE, true));
    assertThat(
            fields(
                VideoDecision.getDescriptor(),
                "bitstream_rewrites",
                "dolby_vision_output",
                "mastering_display",
                "content_light_level"))
        .containsExactly(
            tuple("bitstream_rewrites", 15, Type.ENUM, false),
            tuple("dolby_vision_output", 16, Type.MESSAGE, true),
            tuple("mastering_display", 17, Type.MESSAGE, true),
            tuple("content_light_level", 18, Type.MESSAGE, true));
    assertThat(
            fields(WorkerCapabilities.getDescriptor(), "bitstream_rewrites", "video_copy_features"))
        .containsExactly(
            tuple("bitstream_rewrites", 5, Type.ENUM, false),
            tuple("video_copy_features", 6, Type.ENUM, false));
    assertThat(
            fields(
                DolbyVisionOutput.getDescriptor(),
                "profile",
                "level",
                "base_layer_compatibility_id",
                "sample_entry"))
        .containsExactly(
            tuple("profile", 1, Type.UINT32, false),
            tuple("level", 2, Type.UINT32, false),
            tuple("base_layer_compatibility_id", 3, Type.UINT32, false),
            tuple("sample_entry", 4, Type.ENUM, false));
    assertThat(
            VideoDecision.getDescriptor()
                .findFieldByName("bitstream_rewrites")
                .getEnumType()
                .getFullName())
        .isEqualTo(BitstreamRewrite.getDescriptor().getFullName());
    assertThat(
            WorkerCapabilities.getDescriptor()
                .findFieldByName("bitstream_rewrites")
                .getEnumType()
                .getFullName())
        .isEqualTo(BitstreamRewrite.getDescriptor().getFullName());
  }

  @Test
  @DisplayName(
      "Should keep the rewrite, copy feature and sample entry values that workers build against"
          + " when publishing the video copy contract")
  void shouldKeepTheRewriteCopyFeatureAndSampleEntryValuesWhenPublishingTheVideoCopyContract() {
    assertThat(values(BitstreamRewrite.getDescriptor()))
        .containsExactly(
            tuple("BITSTREAM_REWRITE_UNSPECIFIED", 0),
            tuple("BITSTREAM_REWRITE_STRIP_DOLBY_VISION", 1),
            tuple("BITSTREAM_REWRITE_STRIP_HDR10_PLUS", 2),
            tuple("BITSTREAM_REWRITE_NORMALIZE_DOLBY_VISION_RECORD", 3),
            tuple("BITSTREAM_REWRITE_CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1", 4));
    assertThat(values(VideoCopyFeature.getDescriptor()))
        .containsExactly(
            tuple("VIDEO_COPY_FEATURE_UNSPECIFIED", 0),
            tuple("VIDEO_COPY_FEATURE_PLANNED_OUTPUT", 1),
            tuple("VIDEO_COPY_FEATURE_DVH1_SAMPLE_ENTRY", 2),
            tuple("VIDEO_COPY_FEATURE_DAV1_SAMPLE_ENTRY", 3));
    assertThat(values(DolbyVisionSampleEntry.getDescriptor()))
        .containsExactly(
            tuple("DOLBY_VISION_SAMPLE_ENTRY_UNSPECIFIED", 0),
            tuple("DOLBY_VISION_SAMPLE_ENTRY_HVC1", 1),
            tuple("DOLBY_VISION_SAMPLE_ENTRY_DVH1", 2),
            tuple("DOLBY_VISION_SAMPLE_ENTRY_AV01", 3),
            tuple("DOLBY_VISION_SAMPLE_ENTRY_DAV1", 4));
  }

  private static VideoDecision.Builder profile81Copy() {
    var dolbyVision =
        DolbyVisionPresent.newBuilder()
            .setDeclared(
                DolbyVisionConfiguration.newBuilder()
                    .setProfile(8)
                    .setLevel(6)
                    .setBaseLayerCompatibilityId(1)
                    .setBaseLayer(true)
                    .setRpu(true))
            .setObservedRpu(Presence.PRESENCE_PRESENT);
    return VideoDecision.newBuilder()
        .setMode(VideoMode.VIDEO_MODE_COPY)
        .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC)
        .setCodecString("hvc1.2.4.L150.90")
        .setSourceDynamicRange(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                .setHdr10Plus(Presence.PRESENCE_PRESENT)
                .setDolbyVisionPresent(dolbyVision))
        .setOutputDynamicRange(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                .setHdr10Plus(Presence.PRESENCE_ABSENT)
                .setDolbyVisionPresent(dolbyVision));
  }

  // A P3 D65 display of 1000 cd/m^2 peak and 0.005 cd/m^2 black, in HEVC SEI units.
  private static MasteringDisplayColorVolume p3D65Display() {
    return MasteringDisplayColorVolume.newBuilder()
        .setRed(ChromaticityCoordinates.newBuilder().setX(34000).setY(16000))
        .setGreen(ChromaticityCoordinates.newBuilder().setX(13250).setY(34500))
        .setBlue(ChromaticityCoordinates.newBuilder().setX(7500).setY(3000))
        .setWhitePoint(ChromaticityCoordinates.newBuilder().setX(15635).setY(16450))
        .setMaxLuminance(10_000_000)
        .setMinLuminance(50)
        .build();
  }

  private static List<Tuple> fields(Descriptor message, String... names) {
    return Arrays.stream(names)
        .map(message::findFieldByName)
        .map(
            field ->
                tuple(field.getName(), field.getNumber(), field.getType(), field.hasPresence()))
        .toList();
  }

  private static List<Tuple> values(EnumDescriptor type) {
    return type.getValues().stream()
        .map(value -> tuple(value.getName(), value.getNumber()))
        .toList();
  }
}
