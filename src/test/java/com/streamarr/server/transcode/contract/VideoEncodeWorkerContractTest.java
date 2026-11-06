package com.streamarr.server.transcode.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.Type;
import com.streamarr.transcode.v1.ChromaSubsampling;
import com.streamarr.transcode.v1.ChromaticityCoordinates;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.ContentLightLevel;
import com.streamarr.transcode.v1.DolbyVisionAbsent;
import com.streamarr.transcode.v1.EncoderKind;
import com.streamarr.transcode.v1.MasteringDisplayColorVolume;
import com.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.VideoCodecProfile;
import com.streamarr.transcode.v1.VideoCodecTier;
import com.streamarr.transcode.v1.VideoDecision;
import com.streamarr.transcode.v1.VideoDynamicRange;
import com.streamarr.transcode.v1.VideoEncodeFeature;
import com.streamarr.transcode.v1.VideoEncoderBackend;
import com.streamarr.transcode.v1.VideoMode;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerVerifiedVideoEncoder;
import java.util.Arrays;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// streamarr/streamarr-server#397 and ADR 0013 decide that an encode names its output bit depth and
// chroma subsampling, that a PQ encode carries HDR10 static metadata, and that each worker lists
// the outputs beyond 8-bit 4:2:0 SDR that each verified encoder writes.
@Tag("UnitTest")
@DisplayName("Video Encode Worker Contract Tests")
class VideoEncodeWorkerContractTest {

  @Test
  @DisplayName(
      "Should carry the bit depth, chroma subsampling, PQ range and static HDR metadata when a job"
          + " encodes HDR10")
  void shouldCarryTheBitDepthChromaSubsamplingPqRangeAndStaticHdrMetadataWhenAJobEncodesHdr10()
      throws Exception {
    var encode =
        av1Encode()
            .setOutputBitDepth(10)
            .setOutputChromaSubsampling(ChromaSubsampling.CHROMA_SUBSAMPLING_420)
            .setOutputDynamicRange(range(CompatibleBase.COMPATIBLE_BASE_PQ))
            .setMasteringDisplay(p3D65Display())
            .setContentLightLevel(ContentLightLevel.getDefaultInstance())
            .build();

    var decoded = VideoDecision.parseFrom(encode.toByteArray());

    assertThat(decoded.getOutputBitDepth()).isEqualTo(10);
    assertThat(decoded.getOutputChromaSubsampling())
        .isEqualTo(ChromaSubsampling.CHROMA_SUBSAMPLING_420);
    assertThat(decoded.getOutputDynamicRange().getCompatibleBase())
        .isEqualTo(CompatibleBase.COMPATIBLE_BASE_PQ);
    assertThat(decoded.getMasteringDisplay()).isEqualTo(p3D65Display());
    assertThat(decoded.hasContentLightLevel()).isTrue();
    assertThat(decoded.getContentLightLevel().getMaxContentLightLevel()).isZero();
    assertThat(decoded.getContentLightLevel().getMaxFrameAverageLightLevel()).isZero();
  }

  @Test
  @DisplayName(
      "Should read an encode from an older server as one without a bit depth or chroma"
          + " subsampling when it decodes the job")
  void shouldReadAnEncodeFromAnOlderServerAsOneWithoutABitDepthOrChromaSubsampling()
      throws Exception {
    var olderServerEncode =
        av1Encode().setOutputDynamicRange(range(CompatibleBase.COMPATIBLE_BASE_SDR)).build();

    var decoded = VideoDecision.parseFrom(olderServerEncode.toByteArray());

    assertThat(decoded.hasOutputBitDepth()).isFalse();
    assertThat(decoded.getOutputChromaSubsampling())
        .isEqualTo(ChromaSubsampling.CHROMA_SUBSAMPLING_UNSPECIFIED);
    assertThat(decoded.hasMasteringDisplay()).isFalse();
    assertThat(decoded.hasContentLightLevel()).isFalse();
  }

  @Test
  @DisplayName(
      "Should list each verified encoder's encode features when a worker registers, and none when"
          + " an older worker registers")
  void shouldListEachVerifiedEncodersEncodeFeaturesWhenAWorkerRegisters() throws Exception {
    var declaring =
        WorkerCapabilities.newBuilder()
            .addVideoEncoders(
                verifiedEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1")
                    .addEncodeFeatures(VideoEncodeFeature.VIDEO_ENCODE_FEATURE_TEN_BIT)
                    .addEncodeFeatures(VideoEncodeFeature.VIDEO_ENCODE_FEATURE_PQ))
            .addVideoEncoders(verifiedEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264"))
            .build();

    var decoded = WorkerCapabilities.parseFrom(declaring.toByteArray());

    assertThat(decoded.getVideoEncodersList())
        .extracting(
            encoder -> encoder.getBackend().getEncoder(),
            WorkerVerifiedVideoEncoder::getEncodeFeaturesList)
        .containsExactly(
            tuple(
                "libsvtav1",
                List.of(
                    VideoEncodeFeature.VIDEO_ENCODE_FEATURE_TEN_BIT,
                    VideoEncodeFeature.VIDEO_ENCODE_FEATURE_PQ)),
            tuple("libx264", List.of()));
  }

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when publishing the"
          + " video encode contract")
  void
      shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingTheVideoEncodeContract() {
    assertThat(
            fields(
                VideoDecision.getDescriptor(),
                "mastering_display",
                "content_light_level",
                "output_bit_depth",
                "output_chroma_subsampling"))
        .containsExactly(
            tuple("mastering_display", 17, Type.MESSAGE, true),
            tuple("content_light_level", 18, Type.MESSAGE, true),
            tuple("output_bit_depth", 20, Type.UINT32, true),
            tuple("output_chroma_subsampling", 21, Type.ENUM, false));
    assertThat(fields(WorkerVerifiedVideoEncoder.getDescriptor(), "encode_features"))
        .containsExactly(tuple("encode_features", 3, Type.ENUM, false));
    assertThat(
            VideoDecision.getDescriptor()
                .findFieldByName("output_chroma_subsampling")
                .getEnumType()
                .getFullName())
        .isEqualTo(ChromaSubsampling.getDescriptor().getFullName());
  }

  @Test
  @DisplayName(
      "Should keep the encode feature values that workers build against when publishing the video"
          + " encode contract")
  void shouldKeepTheEncodeFeatureValuesThatWorkersBuildAgainstWhenPublishingTheContract() {
    assertThat(values(VideoEncodeFeature.getDescriptor()))
        .containsExactly(
            tuple("VIDEO_ENCODE_FEATURE_UNSPECIFIED", 0),
            tuple("VIDEO_ENCODE_FEATURE_TEN_BIT", 1),
            tuple("VIDEO_ENCODE_FEATURE_PQ", 2),
            tuple("VIDEO_ENCODE_FEATURE_HLG", 3),
            tuple("VIDEO_ENCODE_FEATURE_DOLBY_VISION_TO_PQ", 4));
  }

  private static VideoDecision.Builder av1Encode() {
    return VideoDecision.newBuilder()
        .setMode(VideoMode.VIDEO_MODE_ENCODE)
        .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1)
        .setCodecProfile(VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN)
        .setTier(VideoCodecTier.VIDEO_CODEC_TIER_MAIN)
        .setLevel(50)
        .setCodecString("av01.0.12M.10")
        .setSourceDynamicRange(range(CompatibleBase.COMPATIBLE_BASE_PQ));
  }

  private static VideoDynamicRange range(CompatibleBase base) {
    return VideoDynamicRange.newBuilder()
        .setCompatibleBase(base)
        .setHdr10Plus(Presence.PRESENCE_ABSENT)
        .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
        .build();
  }

  private static WorkerVerifiedVideoEncoder.Builder verifiedEncoder(
      VideoCodecFamily family, String encoder) {
    return WorkerVerifiedVideoEncoder.newBuilder()
        .setBackend(
            VideoEncoderBackend.newBuilder()
                .setCodecFamily(family)
                .setEncoder(encoder)
                .setKind(EncoderKind.ENCODER_KIND_SOFTWARE));
  }

  // The fallback display of streamarr/streamarr-server#397: P3 D65 primaries, 1000 cd/m^2 peak and
  // 0.0001 cd/m^2 black, in HEVC SEI units.
  private static MasteringDisplayColorVolume p3D65Display() {
    return MasteringDisplayColorVolume.newBuilder()
        .setRed(ChromaticityCoordinates.newBuilder().setX(34000).setY(16000))
        .setGreen(ChromaticityCoordinates.newBuilder().setX(13250).setY(34500))
        .setBlue(ChromaticityCoordinates.newBuilder().setX(7500).setY(3000))
        .setWhitePoint(ChromaticityCoordinates.newBuilder().setX(15635).setY(16450))
        .setMaxLuminance(10_000_000)
        .setMinLuminance(1)
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

  private static List<Tuple> values(EnumDescriptor descriptor) {
    return descriptor.getValues().stream()
        .map(value -> tuple(value.getName(), value.getNumber()))
        .toList();
  }
}
