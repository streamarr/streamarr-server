package com.streamarr.server.domain.streaming;

/**
 * Why a format attempt's output differs from its source: why the server encodes the video or the
 * audio, or what a copy leaves out. The stream decision names each reason as it decides, so the
 * reasons always agree with the output (streamarr/streamarr-server#751).
 */
public enum DecisionReason {
  /** No video capability entry for HLS names the source's codec family. */
  VIDEO_CODEC_NOT_DECLARED,
  /** The closest video capability entry does not list the source's codec profile. */
  VIDEO_CODEC_PROFILE_NOT_DECLARED,
  /** The source's codec level or tier is above the closest entry's maximum for its profile. */
  VIDEO_CODEC_LEVEL_NOT_DECLARED,
  /** The source's bit depth is above the closest entry's maximum. */
  VIDEO_BIT_DEPTH_NOT_DECLARED,
  /** The source's picture is larger than the closest entry's maximum. */
  VIDEO_PICTURE_SIZE_NOT_DECLARED,
  /** The source's frame rate is above the closest entry's maximum. */
  VIDEO_FRAME_RATE_NOT_DECLARED,
  /** The closest entry does not declare the source's compatible base, such as PQ or HLG. */
  DYNAMIC_RANGE_NOT_DECLARED,
  /** The closest entry does not declare the source's Dolby Vision profile and level. */
  DOLBY_VISION_NOT_DECLARED,
  /** The closest entry does not declare HDR10+. */
  HDR10_PLUS_NOT_DECLARED,
  /** The source's picture is larger than the fixed quality or the maximum size the viewer sent. */
  PICTURE_ABOVE_VIEWER_LIMIT,
  /** A copy costs more than the viewer's maximum bitrate beside every audio step. */
  BITRATE_ABOVE_VIEWER_LIMIT,
  /** The source is interlaced, and HLS delivery needs progressive video. */
  SOURCE_INTERLACED,
  /** The source is Dolby Vision without a compatible base, which no client copy plays. */
  DOLBY_VISION_WITHOUT_COMPATIBLE_BASE,
  /** The source's HDR base was probed before probe version 5, which reports no codec profile. */
  VIDEO_PROPERTIES_NOT_PROBED,
  /** {@link MediaFileProbePropertyIssue#DOLBY_VISION_COMPATIBILITY_DISAGREES_WITH_TRANSFER}. */
  DOLBY_VISION_COMPATIBILITY_DISAGREES_WITH_TRANSFER,
  /** {@link MediaFileProbePropertyIssue#DOLBY_VISION_RPU_STARTS_SAMPLE}. */
  DOLBY_VISION_RPU_STARTS_SAMPLE,
  /** The source is H.264 at another bit depth or chroma subsampling than 8-bit 4:2:0. */
  H264_NOT_8_BIT_4_2_0,
  /** The source's sample entry is not one under which a playlist describes a copy. */
  SAMPLE_ENTRY_NOT_COPYABLE,
  /** The probe could not name the source's codec profile. */
  VIDEO_CODEC_PROFILE_UNKNOWN,
  /** The probe stored no video facts for a copy job to carry. */
  VIDEO_SOURCE_NOT_COPYABLE,
  /** The source's keyframe positions are not known yet. */
  KEYFRAMES_UNKNOWN,
  /** The source's keyframes leave a segment interval before the last one without a keyframe. */
  KEYFRAMES_TOO_SPARSE,
  /** The source's keyframe timestamps go backwards or out of range. */
  KEYFRAME_TIMESTAMPS_INVALID,
  /** No connected worker delivers the copy's bitstream rewrites. */
  NO_WORKER_DELIVERS_COPY,
  /** No audio capability entry for HLS names the source's audio codec. */
  AUDIO_CODEC_NOT_DECLARED,
  /** The source has more audio channels than the entry for its codec accepts. */
  AUDIO_CHANNELS_NOT_DECLARED,
  /** The source's audio codec string is one that HLS delivery cannot copy, such as AAC Main. */
  AUDIO_SOURCE_NOT_COPYABLE,
  /** A lower audio step keeps the output within the viewer's maximum bitrate. */
  AUDIO_LOWERED_FOR_BITRATE_LIMIT,
  /** An earlier format attempt's direct play failed, so this format attempt plays over HLS. */
  DIRECT_PLAY_FAILED,
  /** An earlier format attempt's copy failed, so this format attempt encodes the video. */
  COPY_FAILED,
  /** An earlier format attempt's encode failed, so this one encodes in another codec family. */
  ENCODE_FAILED
}
