package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.transcode.v1.EncoderKind;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.VideoEncoderBackend;
import com.streamarr.transcode.v1.WorkerVerifiedVideoEncoder;
import java.util.Optional;

// Empty for an entry whose codec family or encoder kind this server does not know.
final class RemoteEncoderBackendMapper {

  private RemoteEncoderBackendMapper() {}

  static Optional<WorkerVerifiedEncoder> workerVerifiedEncoder(WorkerVerifiedVideoEncoder encoder) {
    return encoderBackend(encoder.getBackend())
        .map(
            backend ->
                WorkerVerifiedEncoder.builder()
                    .backend(backend)
                    .keyframeVerified(encoder.getKeyframeVerified())
                    .build());
  }

  static Optional<EncoderBackend> encoderBackend(VideoEncoderBackend backend) {
    var codecFamily = videoCodecFamilyName(backend.getCodecFamily());
    var kind = kind(backend.getKind());
    if (codecFamily.isEmpty() || kind.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        EncoderBackend.builder()
            .codecFamily(codecFamily.get())
            .encoder(backend.getEncoder())
            .kind(kind.get())
            .build());
  }

  private static Optional<String> videoCodecFamilyName(VideoCodecFamily family) {
    return switch (family) {
      case VIDEO_CODEC_FAMILY_H264 -> Optional.of("h264");
      case VIDEO_CODEC_FAMILY_HEVC -> Optional.of("hevc");
      case VIDEO_CODEC_FAMILY_AV1 -> Optional.of("av1");
      case VIDEO_CODEC_FAMILY_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
    };
  }

  private static Optional<EncoderBackend.Kind> kind(EncoderKind kind) {
    return switch (kind) {
      case ENCODER_KIND_HARDWARE -> Optional.of(EncoderBackend.Kind.HARDWARE);
      case ENCODER_KIND_SOFTWARE -> Optional.of(EncoderBackend.Kind.SOFTWARE);
      case ENCODER_KIND_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
    };
  }
}
