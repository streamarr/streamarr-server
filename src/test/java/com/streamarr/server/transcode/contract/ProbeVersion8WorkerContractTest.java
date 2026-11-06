package com.streamarr.server.transcode.contract;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.Type;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.ContentLightLevel;
import com.streamarr.transcode.v1.DolbyVisionConfiguration;
import com.streamarr.transcode.v1.DolbyVisionEnhancementLayer;
import com.streamarr.transcode.v1.DolbyVisionPresent;
import com.streamarr.transcode.v1.Fraction;
import com.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.v1.VideoDynamicRange;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe Version 8 Worker Contract Tests")
class ProbeVersion8WorkerContractTest {

  @Test
  @DisplayName(
      "Should carry the maximum frame rate, the enhancement layer and the level 6 light levels"
          + " when returning a probe version 8 result for a profile 7 stream")
  void shouldCarryTheVersion8FactsWhenReturningAProbeVersion8ResultForAProfile7Stream()
      throws Exception {
    var result =
        probeVersion8Result(
            profile7Stream(Fraction.newBuilder().setNumerator(60000).setDenominator(1001)));

    var decoded = ProbeAttemptResult.parseFrom(result.toByteArray());

    var stream = decoded.getMedia().getStreams(0);
    var dolbyVision = stream.getDynamicRange().getDolbyVisionPresent();
    assertThat(stream.getMaximumFrameRate())
        .isEqualTo(Fraction.newBuilder().setNumerator(60000).setDenominator(1001).build());
    assertThat(dolbyVision.getEnhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_FEL);
    assertThat(dolbyVision.getRpuContentLightLevel())
        .isEqualTo(
            ContentLightLevel.newBuilder()
                .setMaxContentLightLevel(1000)
                .setMaxFrameAverageLightLevel(400)
                .build());
  }

  @Test
  @DisplayName(
      "Should tell an unmeasured maximum frame rate and a missing level 6 block apart from zero"
          + " light levels when returning a probe version 8 result")
  void shouldTellUnmeasuredMaximumAndMissingLevel6ApartFromZeroLightLevels() throws Exception {
    var withoutMaximum =
        ProbeStreamInfo.newBuilder()
            .setIndex(0)
            .setCodecType("video")
            .setDynamicRange(
                VideoDynamicRange.newBuilder()
                    .setDolbyVisionPresent(DolbyVisionPresent.newBuilder()));
    var withZeroLightLevels =
        ProbeStreamInfo.newBuilder()
            .setIndex(1)
            .setCodecType("video")
            .setDynamicRange(
                VideoDynamicRange.newBuilder()
                    .setDolbyVisionPresent(
                        DolbyVisionPresent.newBuilder()
                            .setRpuContentLightLevel(ContentLightLevel.getDefaultInstance())));
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(8)
            .setMedia(
                ProbeMediaInfo.newBuilder()
                    .addStreams(withoutMaximum)
                    .addStreams(withZeroLightLevels))
            .build();

    var decoded = ProbeAttemptResult.parseFrom(result.toByteArray()).getMedia();

    assertThat(decoded.getStreams(0).hasMaximumFrameRate()).isFalse();
    assertThat(
            decoded
                .getStreams(0)
                .getDynamicRange()
                .getDolbyVisionPresent()
                .hasRpuContentLightLevel())
        .isFalse();
    var zeroLevels = decoded.getStreams(1).getDynamicRange().getDolbyVisionPresent();
    assertThat(zeroLevels.hasRpuContentLightLevel()).isTrue();
    assertThat(zeroLevels.getRpuContentLightLevel().getMaxContentLightLevel()).isZero();
  }

  @Test
  @DisplayName(
      "Should read no maximum, an unspecified enhancement layer and no level 6 light levels when"
          + " an older worker returns a probe version 7 result")
  void shouldReadNoVersion8FactsWhenAnOlderWorkerReturnsAProbeVersion7Result() throws Exception {
    var olderStream =
        ProbeStreamInfo.newBuilder()
            .setIndex(0)
            .setCodecType("video")
            .setFramerate(23.976)
            .setDynamicRange(
                VideoDynamicRange.newBuilder()
                    .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                    .setDolbyVisionPresent(
                        DolbyVisionPresent.newBuilder().setObservedRpu(Presence.PRESENCE_PRESENT)));
    var older =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(7)
            .setMedia(ProbeMediaInfo.newBuilder().addStreams(olderStream))
            .build();

    var decoded = ProbeAttemptResult.parseFrom(older.toByteArray()).getMedia().getStreams(0);

    var dolbyVision = decoded.getDynamicRange().getDolbyVisionPresent();
    assertThat(decoded.hasMaximumFrameRate()).isFalse();
    assertThat(dolbyVision.getEnhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_UNSPECIFIED);
    assertThat(dolbyVision.hasRpuContentLightLevel()).isFalse();
  }

  @Test
  @DisplayName(
      "Should keep the field numbers, types and enum values that workers build against when"
          + " publishing probe version 8")
  void shouldKeepTheFieldNumbersTypesAndValuesThatWorkersBuildAgainstWhenPublishingProbeVersion8() {
    assertThat(fields(ProbeStreamInfo.getDescriptor(), "maximum_frame_rate"))
        .containsExactly(tuple("maximum_frame_rate", 49, Type.MESSAGE, true));
    assertThat(
            ProbeStreamInfo.getDescriptor()
                .findFieldByName("maximum_frame_rate")
                .getMessageType()
                .getFullName())
        .isEqualTo(Fraction.getDescriptor().getFullName());
    assertThat(
            fields(
                DolbyVisionPresent.getDescriptor(), "enhancement_layer", "rpu_content_light_level"))
        .containsExactly(
            tuple("enhancement_layer", 5, Type.ENUM, false),
            tuple("rpu_content_light_level", 6, Type.MESSAGE, true));
    assertThat(
            DolbyVisionPresent.getDescriptor()
                .findFieldByName("rpu_content_light_level")
                .getMessageType()
                .getFullName())
        .isEqualTo(ContentLightLevel.getDescriptor().getFullName());
    assertThat(values(DolbyVisionEnhancementLayer.getDescriptor()))
        .containsExactly(
            tuple("DOLBY_VISION_ENHANCEMENT_LAYER_UNSPECIFIED", 0),
            tuple("DOLBY_VISION_ENHANCEMENT_LAYER_NONE", 1),
            tuple("DOLBY_VISION_ENHANCEMENT_LAYER_MEL", 2),
            tuple("DOLBY_VISION_ENHANCEMENT_LAYER_FEL", 3));
  }

  // A single-track profile 7 stream of an Ultra HD Blu-ray remux, with a full enhancement layer.
  private static ProbeStreamInfo.Builder profile7Stream(Fraction.Builder maximumFrameRate) {
    var dolbyVision =
        DolbyVisionPresent.newBuilder()
            .setDeclared(
                DolbyVisionConfiguration.newBuilder()
                    .setProfile(7)
                    .setLevel(6)
                    .setBaseLayerCompatibilityId(6)
                    .setBaseLayer(true)
                    .setEnhancementLayer(true)
                    .setRpu(true))
            .setObservedRpu(Presence.PRESENCE_PRESENT)
            .setEnhancementLayer(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_FEL)
            .setRpuContentLightLevel(
                ContentLightLevel.newBuilder()
                    .setMaxContentLightLevel(1000)
                    .setMaxFrameAverageLightLevel(400));
    return ProbeStreamInfo.newBuilder()
        .setIndex(0)
        .setCodecType("video")
        .setCodec("hevc")
        .setMaximumFrameRate(maximumFrameRate)
        .setDynamicRange(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                .setDolbyVisionPresent(dolbyVision));
  }

  private static ProbeAttemptResult probeVersion8Result(ProbeStreamInfo.Builder stream) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(8)
        .setMedia(ProbeMediaInfo.newBuilder().addStreams(stream).setChosenVideoStreamIndex(0))
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
