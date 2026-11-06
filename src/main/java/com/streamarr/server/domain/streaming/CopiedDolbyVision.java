package com.streamarr.server.domain.streaming;

import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.NonNull;

/** The Dolby Vision that a stream copy carries, as a video capability entry judges it. */
sealed interface CopiedDolbyVision {

  /** The probe found no Dolby Vision, or could not check for it. */
  record None() implements CopiedDolbyVision {}

  /**
   * Profile 8.2 or 9: the copy keeps its RPUs under the sample entry of its SDR base (ADR 0036),
   * hvc1 for profile 8.2 and avc1 for profile 9, so it fits an entry that accepts the base codec
   * and SDR.
   */
  record KeptUnderSdrBase(@NonNull VideoSampleEntry baseSampleEntry) implements CopiedDolbyVision {}

  /**
   * A profile that an entry can list, at the level that the configuration record names. Without a
   * record level from 1 to 13, the level is the lowest one in Dolby's level table that carries the
   * decoded size at the maximum frame rate.
   */
  record Declarable(@NonNull DolbyVisionProfile profile, int level) implements CopiedDolbyVision {}

  /**
   * Dolby Vision that no entry accepts: profile 6, profile 7 that no copy converts, or a profile
   * that the contract does not name. A copy on a PQ or HLG base can still play with the Dolby
   * Vision stripped (ADR 0013).
   */
  record Undeclarable() implements CopiedDolbyVision {}

  /**
   * Single-track profile 7 on a PQ base, whose RPUs ride in the video stream itself, at the level
   * that the record names or the level table gives: a copy converts it to profile 8.1 (ADR 0036,
   * streamarr/streamarr-server#651). No entry accepts the stream as it is, and a copy can still
   * play its PQ base with the Dolby Vision stripped.
   */
  record ConvertibleToProfile81(int level) implements CopiedDolbyVision {}

  /**
   * Dolby Vision that no entry accepts because the probe's facts leave it unknown: neither the
   * record nor the first RPU header names a profile, or neither the record nor the level table
   * gives a level. Until streamarr/streamarr-server#652 stores a maximum frame rate, the table
   * gives no level. A copy on a PQ or HLG base can still play with the Dolby Vision stripped (ADR
   * 0013).
   */
  record Unknown() implements CopiedDolbyVision {}

  /**
   * Profile 5 or 10.0, which has no compatible base. A copy fits an entry that lists the profile at
   * or above its level, and only a worker that writes the Dolby Vision sample entry and
   * configuration record that such a copy needs delivers it: dvh1 for profile 5, dav1 for 10.0 (ADR
   * 0013). Without them, a picture with no compatible base shows wrong colours or is refused.
   * {@code level} is empty when neither the record nor the level table gives one, and then no entry
   * accepts the stream.
   */
  record NeedsDolbyVisionSampleEntry(
      @NonNull DolbyVisionProfile profile, @NonNull OptionalInt level)
      implements CopiedDolbyVision {}

  static CopiedDolbyVision of(VideoStreamCopy copy) {
    var range = copy.dynamicRange();
    return switch (range.dolbyVision()) {
      case DolbyVision.Unspecified _, DolbyVision.Absent _ -> new None();
      case DolbyVision.Present present -> ofPresent(copy, present);
    };
  }

  private static CopiedDolbyVision ofPresent(VideoStreamCopy copy, DolbyVision.Present present) {
    var codecFamily = copy.codecFamily();
    var base = copy.dynamicRange().compatibleBase();
    var profileNumber = profileNumberFromRecordOrRpuHeader(codecFamily, present);
    if (profileNumber.isEmpty()) {
      return new Unknown();
    }

    var sdrBaseSampleEntry = sdrBaseSampleEntry(codecFamily, profileNumber.getAsInt());
    if (base == CompatibleBase.SDR && sdrBaseSampleEntry.isPresent()) {
      return new KeptUnderSdrBase(sdrBaseSampleEntry.get());
    }

    // Profile 5 has no compatible base whatever the signaled transfer claims, so a PQ transfer
    // never makes it a profile whose base a stripped copy could play.
    if (codecFamily == ProbedCodecFamily.HEVC && profileNumber.getAsInt() == 5) {
      return new NeedsDolbyVisionSampleEntry(
          DolbyVisionProfile.PROFILE_5, levelFromRecordOrTable(copy, present));
    }

    var profile = namedProfile(codecFamily, profileNumber.getAsInt(), base);
    if (profile.isEmpty() && isSingleTrackProfile7(copy, present, profileNumber.getAsInt())) {
      return convertibleAt(levelFromRecordOrTable(copy, present));
    }

    if (profile.isEmpty()) {
      return new Undeclarable();
    }

    var level = levelFromRecordOrTable(copy, present);
    if (level.isEmpty()) {
      return new Unknown();
    }

    if (profile.get().compatibleBase() == CompatibleBase.NONE) {
      return new NeedsDolbyVisionSampleEntry(profile.get(), level);
    }

    return new Declarable(profile.get(), level.getAsInt());
  }

  // A profile 7 record that names a base layer, beside RPUs in the stream itself. A dual-track
  // source's base layer track carries no RPU, and its enhancement layer track carries RPUs beside a
  // PQ transfer but no base layer to convert (ADR 0036).
  private static boolean isSingleTrackProfile7(
      VideoStreamCopy copy, DolbyVision.Present present, int profileNumber) {
    return copy.codecFamily() == ProbedCodecFamily.HEVC
        && profileNumber == 7
        && copy.dynamicRange().compatibleBase() == CompatibleBase.PQ
        && present.declared().map(DolbyVisionConfiguration::baseLayer).orElse(false)
        && present.observedRpu() == Presence.PRESENT;
  }

  // Without a level, the converted copy's output cannot name one, so no entry accepts it.
  private static CopiedDolbyVision convertibleAt(OptionalInt level) {
    if (level.isEmpty()) {
      return new Undeclarable();
    }

    return new ConvertibleToProfile81(level.getAsInt());
  }

  // The level never comes from the RPU's vdr_rpu_level. A record level outside the table names no
  // level. A size or maximum frame rate that the probe does not store gives no level.
  private static OptionalInt levelFromRecordOrTable(
      VideoStreamCopy copy, DolbyVision.Present present) {
    var recordLevel =
        present.declared().map(DolbyVisionConfiguration::level).orElse(OptionalInt.empty());
    if (recordLevel.isPresent() && DolbyVisionLevelTable.isTableLevel(recordLevel.getAsInt())) {
      return recordLevel;
    }

    return copy.decodedSize()
        .flatMap(
            size ->
                copy.maximumFrameRate()
                    .map(rate -> DolbyVisionLevelTable.lowestLevelFor(size, rate)))
        .orElse(OptionalInt.empty());
  }

  private static OptionalInt profileNumberFromRecordOrRpuHeader(
      ProbedCodecFamily codecFamily, DolbyVision.Present present) {
    var declaredProfile =
        present.declared().map(DolbyVisionConfiguration::profile).orElse(OptionalInt.empty());
    if (declaredProfile.isPresent()) {
      return declaredProfile;
    }

    return present
        .firstRpuHeader()
        .map(header -> profileNumberFromRpuHeader(codecFamily, header))
        .orElse(OptionalInt.empty());
  }

  // Without a record, the worker contract names the profile from the first RPU header as FFmpeg 8.1
  // guesses it.
  private static OptionalInt profileNumberFromRpuHeader(
      ProbedCodecFamily codecFamily, DolbyVisionRpuHeader header) {
    return switch (codecFamily) {
      case HEVC -> hevcProfileNumberFromRpuHeader(header);
      case AV1 -> OptionalInt.of(10);
      case H264 -> OptionalInt.of(9);
      case OTHER -> OptionalInt.empty();
    };
  }

  // An enhancement layer means profile 7 or 4, which no entry can name.
  private static OptionalInt hevcProfileNumberFromRpuHeader(DolbyVisionRpuHeader header) {
    if (header.vdrRpuProfile() == 0 && header.baseLayerFullRange()) {
      return OptionalInt.of(5);
    }

    var carriesEnhancementLayer =
        header.enhancementLayerSpatialResamplingFilter() && !header.disableResidual();
    if (header.vdrRpuProfile() != 1 || carriesEnhancementLayer) {
      return OptionalInt.empty();
    }

    return OptionalInt.of(8);
  }

  // The sample entry of the SDR base under which a profile 8.2 or 9 copy keeps its RPUs, or empty
  // for a profile without an SDR base.
  private static Optional<VideoSampleEntry> sdrBaseSampleEntry(
      ProbedCodecFamily codecFamily, int profileNumber) {
    return switch (codecFamily) {
      case HEVC -> Optional.of(VideoSampleEntry.HVC1).filter(_ -> profileNumber == 8);
      case H264 -> Optional.of(VideoSampleEntry.AVC1).filter(_ -> profileNumber == 9);
      case AV1, OTHER -> Optional.empty();
    };
  }

  // Within one codec family and profile number, the compatible base tells the named profiles apart.
  private static Optional<DolbyVisionProfile> namedProfile(
      ProbedCodecFamily codecFamily, int profileNumber, CompatibleBase base) {
    return Arrays.stream(DolbyVisionProfile.values())
        .filter(profile -> profile.codecFamily() == codecFamily)
        .filter(profile -> profile.compatibleBase() == base)
        .filter(profile -> profile.profileNumber() == profileNumber)
        .findFirst();
  }
}
