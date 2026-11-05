package com.streamarr.server.domain.streaming;

/**
 * Why a format attempt's output differs from its source: why the source file does not play
 * directly, why the server encodes the video or the audio, or what a copy leaves out. The stream
 * decision names each reason as it decides, so the reasons always agree with the output
 * (streamarr/streamarr-server#751).
 *
 * <p>When no entry accepts a copy, "the closest entry" is the video capability entry for HLS of the
 * source's codec family that refuses the fewest properties. When an entry accepts a copy only after
 * the copy leaves out Dolby Vision or HDR10+, it is that accepting entry, and the reasons name what
 * it refused in the copies that kept more.
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
  /** The probe reported no bit depth for the source's video, so no entry can accept a copy. */
  VIDEO_BIT_DEPTH_UNKNOWN,
  /** The source's picture is larger than the closest entry's maximum. */
  VIDEO_PICTURE_SIZE_NOT_DECLARED,
  /** The source's frame rate is above the closest entry's maximum. */
  VIDEO_FRAME_RATE_NOT_DECLARED,
  /** The closest entry does not declare the source's compatible base: SDR, PQ or HLG. */
  DYNAMIC_RANGE_NOT_DECLARED,
  /** The closest entry does not declare the source's Dolby Vision profile and level. */
  DOLBY_VISION_NOT_DECLARED,
  /**
   * The probe's facts give no Dolby Vision profile or level for the source, so no entry can accept
   * its Dolby Vision.
   */
  DOLBY_VISION_PROFILE_OR_LEVEL_UNKNOWN,
  /** The closest entry does not declare HDR10+. */
  HDR10_PLUS_NOT_DECLARED,
  /**
   * A copy that keeps Dolby Vision strips the source's HDR10+ information, whatever the client
   * declares (ADR 0036).
   */
  HDR10_PLUS_STRIPPED_BESIDE_DOLBY_VISION,
  /** The source's picture is larger than the fixed quality or the maximum size the viewer sent. */
  PICTURE_ABOVE_VIEWER_LIMIT,
  /** A copy costs more than the viewer's maximum bitrate beside every audio step. */
  BITRATE_ABOVE_VIEWER_LIMIT,
  /**
   * The source is interlaced. HLS delivery needs progressive video, and no player's direct play of
   * an interlaced file was measured.
   */
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
  /** The probe reported no chroma subsampling for the source's H.264 video. */
  VIDEO_CHROMA_SUBSAMPLING_UNKNOWN,
  /** The source's sample entry is not one under which a playlist describes a copy. */
  SAMPLE_ENTRY_NOT_COPYABLE,
  /** The probe stored no codec string for the source's video, which a playlist needs for a copy. */
  VIDEO_CODEC_STRING_UNKNOWN,
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
  /**
   * The probe reported no channel count for the source's audio, and the entry for its codec accepts
   * fewer channels than the stereo track the decision then assumes.
   */
  AUDIO_CHANNELS_UNKNOWN,
  /** The source's audio codec string is one that HLS delivery cannot copy, such as AAC Main. */
  AUDIO_SOURCE_NOT_COPYABLE,
  /** The probe stored no codec string for the source's audio, which a playlist needs for a copy. */
  AUDIO_CODEC_STRING_UNKNOWN,
  /**
   * The viewer's maximum bitrate takes a lower audio step. The output can still exceed a maximum
   * below the lowest audio and video rates that the ladder encodes.
   */
  AUDIO_LOWERED_FOR_BITRATE_LIMIT,
  /** The probe's facts name no single source container that a declaration can list. */
  SOURCE_CONTAINER_UNKNOWN,
  /**
   * No video capability entry of the source's codec family lists the file's source container, or,
   * for a file with audio, no audio capability entry lists it.
   */
  DIRECT_PLAY_CONTAINER_NOT_DECLARED,
  /**
   * No video capability entry that lists the MP4 or MOV container lists the video stream's sample
   * entry, or the probe stored no sample entry.
   */
  DIRECT_PLAY_SAMPLE_ENTRY_NOT_DECLARED,
  /**
   * No video capability entry that lists the file's source container, and its sample entry in MP4
   * or MOV, accepts every property of the video stream as it is, or the probe left one of those
   * properties unknown.
   */
  DIRECT_PLAY_VIDEO_NOT_DECLARED,
  /**
   * An audio track of the file is one that no audio capability entry listing the file's source
   * container accepts. Every track counts, because the player may start any of them.
   */
  DIRECT_PLAY_AUDIO_NOT_DECLARED,
  /**
   * A forced image subtitle track, PGS, VobSub or DVB, is in the language of an audio track
   * (streamarr/streamarr-server#506).
   */
  FORCED_IMAGE_SUBTITLE_IN_AUDIO_LANGUAGE,
  /** An earlier format attempt's direct play failed, so this format attempt plays over HLS. */
  DIRECT_PLAY_FAILED,
  /** An earlier format attempt's copy failed, so this format attempt encodes the video. */
  COPY_FAILED,
  /** An earlier format attempt's encode failed, so this one encodes in another codec family. */
  ENCODE_FAILED
}
