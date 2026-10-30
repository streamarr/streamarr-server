package com.streamarr.server.domain.streaming;

import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;

/** The Dolby Vision that a stream copy carries, as a video capability entry judges it. */
sealed interface CopiedDolbyVision {

  /** The probe found no Dolby Vision, or could not check for it. */
  record None() implements CopiedDolbyVision {}

  /**
   * Profile 8.2 or 9: the copy keeps its RPUs under the sample entry of its SDR base (ADR 0036), so
   * it fits an entry that accepts the base codec and SDR.
   */
  record KeptUnderSdrBase() implements CopiedDolbyVision {}

  /** A profile that an entry can list, at the level that the configuration record names. */
  record Declarable(DolbyVisionProfile profile, int level) implements CopiedDolbyVision {}

  /**
   * Dolby Vision that no entry accepts until a copy can strip it (streamarr/streamarr-server#396):
   * profile 7, an unknown profile, a profile that the contract does not name, or a record that
   * names no Dolby Vision level.
   */
  record Undeclarable() implements CopiedDolbyVision {}

  static CopiedDolbyVision of(ProbedCodecFamily codecFamily, VideoDynamicRange range) {
    return switch (range.dolbyVision()) {
      case DolbyVision.Unspecified _, DolbyVision.Absent _ -> new None();
      case DolbyVision.Present present -> ofPresent(codecFamily, range.compatibleBase(), present);
    };
  }

  private static CopiedDolbyVision ofPresent(
      ProbedCodecFamily codecFamily, CompatibleBase base, DolbyVision.Present present) {
    var profileNumber = profileNumberFromRecordOrRpuHeader(codecFamily, present);
    if (profileNumber.isEmpty()) {
      return new Undeclarable();
    }

    if (base == CompatibleBase.SDR && isSdrBaseProfile(codecFamily, profileNumber.getAsInt())) {
      return new KeptUnderSdrBase();
    }

    var profile = namedProfile(codecFamily, profileNumber.getAsInt(), base);
    var level = present.declared().map(DolbyVisionConfiguration::level).orElse(OptionalInt.empty());
    if (profile.isEmpty() || level.isEmpty()) {
      return new Undeclarable();
    }

    return new Declarable(profile.get(), level.getAsInt());
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

  private static boolean isSdrBaseProfile(ProbedCodecFamily codecFamily, int profileNumber) {
    return switch (codecFamily) {
      case HEVC -> profileNumber == 8;
      case H264 -> profileNumber == 9;
      case AV1, OTHER -> false;
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
