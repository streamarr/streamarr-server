package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;

/** Worker-verified encoders with the keyframe-verified flags that ADR 0037 lists. */
public final class WorkerVerifiedEncoderFixture {

  private WorkerVerifiedEncoderFixture() {}

  public static WorkerVerifiedEncoder libx264() {
    return encoder("h264", "libx264", EncoderBackend.Kind.SOFTWARE).keyframeVerified(true).build();
  }

  /** libx264 from a worker that does not list it as keyframe-verified, such as an older image. */
  public static WorkerVerifiedEncoder libx264NotKeyframeVerified() {
    return encoder("h264", "libx264", EncoderBackend.Kind.SOFTWARE).build();
  }

  public static WorkerVerifiedEncoder h264Nvenc() {
    return encoder("h264", "h264_nvenc", EncoderBackend.Kind.HARDWARE).build();
  }

  public static WorkerVerifiedEncoder av1Nvenc() {
    return encoder("av1", "av1_nvenc", EncoderBackend.Kind.HARDWARE).build();
  }

  public static WorkerVerifiedEncoder libx265() {
    return encoder("hevc", "libx265", EncoderBackend.Kind.SOFTWARE).build();
  }

  public static WorkerVerifiedEncoder libsvtav1() {
    return encoder("av1", "libsvtav1", EncoderBackend.Kind.SOFTWARE).keyframeVerified(true).build();
  }

  private static WorkerVerifiedEncoder.WorkerVerifiedEncoderBuilder encoder(
      String codecFamily, String encoder, EncoderBackend.Kind kind) {
    return WorkerVerifiedEncoder.builder()
        .backend(
            EncoderBackend.builder().codecFamily(codecFamily).encoder(encoder).kind(kind).build());
  }
}
