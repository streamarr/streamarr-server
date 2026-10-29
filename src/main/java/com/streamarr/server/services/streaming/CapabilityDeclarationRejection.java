package com.streamarr.server.services.streaming;

/**
 * Why the API boundary refuses a client capability declaration (ADR 0049). Each rejection names the
 * capability entry, and the codec profile or Dolby Vision profile inside it, by its position in the
 * declaration's lists.
 */
public sealed interface CapabilityDeclarationRejection extends StartPlaybackSessionRejection {

  /** A maximum of a video capability entry that applies to the entry as a whole. */
  enum VideoEntryMaximum {
    BIT_DEPTH,
    WIDTH,
    HEIGHT,
    FRAME_RATE
  }

  record VideoEntriesMissing() implements CapabilityDeclarationRejection {}

  record AudioEntriesMissing() implements CapabilityDeclarationRejection {}

  /** No audio capability entry declares AAC over HLS, which ADR 0043's audio encode needs. */
  record HlsAacEntryMissing() implements CapabilityDeclarationRejection {}

  record TooManyVideoEntries(int maximum) implements CapabilityDeclarationRejection {}

  record TooManyAudioEntries(int maximum) implements CapabilityDeclarationRejection {}

  record CodecProfilesMissing(int videoEntry) implements CapabilityDeclarationRejection {}

  record TooManyCodecProfiles(int videoEntry, int maximum)
      implements CapabilityDeclarationRejection {}

  record CompatibleBasesMissing(int videoEntry) implements CapabilityDeclarationRejection {}

  record TooManyCompatibleBases(int videoEntry, int maximum)
      implements CapabilityDeclarationRejection {}

  record TooManyDolbyVisionProfiles(int videoEntry, int maximum)
      implements CapabilityDeclarationRejection {}

  record VideoMaximumNotPositive(int videoEntry, VideoEntryMaximum maximum)
      implements CapabilityDeclarationRejection {}

  /** The codec profile belongs to another codec family than its capability entry. */
  record CodecProfileOutsideCodecFamily(int videoEntry, int codecProfile)
      implements CapabilityDeclarationRejection {}

  record CodecLevelNotPositive(int videoEntry, int codecProfile)
      implements CapabilityDeclarationRejection {}

  /** An H.264 codec profile names a maximum tier, and H.264 has no tier. */
  record CodecTierNotApplicable(int videoEntry, int codecProfile)
      implements CapabilityDeclarationRejection {}

  /** An HEVC or AV1 codec profile names no maximum tier. */
  record CodecTierMissing(int videoEntry, int codecProfile)
      implements CapabilityDeclarationRejection {}

  /** The Dolby Vision profile is carried by another codec family than its capability entry. */
  record DolbyVisionProfileOutsideCodecFamily(int videoEntry, int dolbyVisionProfile)
      implements CapabilityDeclarationRejection {}

  record DolbyVisionLevelNotPositive(int videoEntry, int dolbyVisionProfile)
      implements CapabilityDeclarationRejection {}

  record AudioChannelsNotPositive(int audioEntry) implements CapabilityDeclarationRejection {}
}
