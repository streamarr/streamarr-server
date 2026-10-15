package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.hardwareEncoder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.softwareEncoder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture;
import com.streamarr.transcode.v1.EncoderKind;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.VideoEncoderBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Remote Encoder Backend Mapper Tests")
class RemoteEncoderBackendMapperTest {

  private static final int UNKNOWN_ENUM_NUMBER = 99;

  @Test
  @DisplayName("Should map an advertised encoder when its codec family and kind are known")
  void shouldMapAdvertisedEncoderWhenItsCodecFamilyAndKindAreKnown() {
    var advertised =
        softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1").toBuilder()
            .setKeyframeVerified(true)
            .build();

    assertThat(RemoteEncoderBackendMapper.workerVerifiedEncoder(advertised))
        .contains(WorkerVerifiedEncoderFixture.libsvtav1());
    assertThat(
            RemoteEncoderBackendMapper.workerVerifiedEncoder(
                hardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "h264_nvenc")))
        .contains(WorkerVerifiedEncoderFixture.h264Nvenc());
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "VIDEO_CODEC_FAMILY_H264, h264",
    "VIDEO_CODEC_FAMILY_HEVC, hevc",
    "VIDEO_CODEC_FAMILY_AV1, av1"
  })
  @DisplayName("Should name the codec family when the worker reports a known codec family")
  void shouldNameCodecFamilyWhenWorkerReportsKnownCodecFamily(
      VideoCodecFamily reported, String codecFamily) {
    var backend = softwareBackend().setCodecFamily(reported).build();

    assertThat(RemoteEncoderBackendMapper.encoderBackend(backend))
        .map(EncoderBackend::codecFamily)
        .contains(codecFamily);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({"ENCODER_KIND_HARDWARE, HARDWARE", "ENCODER_KIND_SOFTWARE, SOFTWARE"})
  @DisplayName("Should name the encoder kind when the worker reports a known encoder kind")
  void shouldNameEncoderKindWhenWorkerReportsKnownEncoderKind(
      EncoderKind reported, EncoderBackend.Kind kind) {
    var backend = softwareBackend().setKind(reported).build();

    assertThat(RemoteEncoderBackendMapper.encoderBackend(backend))
        .map(EncoderBackend::kind)
        .contains(kind);
  }

  @Test
  @DisplayName("Should map nothing when the advertised encoder kind is unspecified")
  void shouldMapNothingWhenAdvertisedEncoderKindIsUnspecified() {
    var advertised =
        softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264").toBuilder();
    advertised.getBackendBuilder().setKind(EncoderKind.ENCODER_KIND_UNSPECIFIED);

    assertThat(RemoteEncoderBackendMapper.workerVerifiedEncoder(advertised.build())).isEmpty();
  }

  @Test
  @DisplayName("Should map nothing when the worker reports an encoder kind newer than the server")
  void shouldMapNothingWhenWorkerReportsEncoderKindNewerThanServer() {
    var backend = softwareBackend().setKindValue(UNKNOWN_ENUM_NUMBER).build();

    assertThat(RemoteEncoderBackendMapper.encoderBackend(backend)).isEmpty();
  }

  @Test
  @DisplayName("Should map nothing when the advertised codec family is unspecified")
  void shouldMapNothingWhenAdvertisedCodecFamilyIsUnspecified() {
    var advertised = softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_UNSPECIFIED, "libvpx-vp9");

    assertThat(RemoteEncoderBackendMapper.workerVerifiedEncoder(advertised)).isEmpty();
  }

  @Test
  @DisplayName("Should map nothing when the worker reports a codec family newer than the server")
  void shouldMapNothingWhenWorkerReportsCodecFamilyNewerThanServer() {
    var backend = softwareBackend().setCodecFamilyValue(UNKNOWN_ENUM_NUMBER).build();

    assertThat(RemoteEncoderBackendMapper.encoderBackend(backend)).isEmpty();
  }

  private static VideoEncoderBackend.Builder softwareBackend() {
    return softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")
        .getBackend()
        .toBuilder();
  }
}
