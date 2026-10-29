package com.streamarr.server.domain.streaming;

/** A video stream's codec profile, as the probe reports it. */
public enum VideoCodecProfile {
  H264_CONSTRAINED_BASELINE,
  H264_BASELINE,
  H264_MAIN,
  H264_EXTENDED,
  H264_HIGH,
  H264_HIGH_10,
  H264_HIGH_422,
  H264_HIGH_444_PREDICTIVE,
  HEVC_MAIN,
  HEVC_MAIN_10,
  HEVC_MAIN_STILL_PICTURE,
  HEVC_FORMAT_RANGE_EXTENSIONS,
  AV1_MAIN,
  AV1_HIGH,
  AV1_PROFESSIONAL;

  /** The codec whose profiles include this one. */
  public ProbedVideoCodec codec() {
    return switch (this) {
      case H264_CONSTRAINED_BASELINE,
          H264_BASELINE,
          H264_MAIN,
          H264_EXTENDED,
          H264_HIGH,
          H264_HIGH_10,
          H264_HIGH_422,
          H264_HIGH_444_PREDICTIVE ->
          ProbedVideoCodec.H264;
      case HEVC_MAIN, HEVC_MAIN_10, HEVC_MAIN_STILL_PICTURE, HEVC_FORMAT_RANGE_EXTENSIONS ->
          ProbedVideoCodec.HEVC;
      case AV1_MAIN, AV1_HIGH, AV1_PROFESSIONAL -> ProbedVideoCodec.AV1;
    };
  }
}
