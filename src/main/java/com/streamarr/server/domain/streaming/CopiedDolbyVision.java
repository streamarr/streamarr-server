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
   * Profile 8.2 or 9: the copy keeps its RPUs under the sample entry of its SDR base (ADR 0036), so
   * it fits an entry that accepts the base codec and SDR.
   */
  record KeptUnderSdrBase() implements CopiedDolbyVision {}

  /**
   * A profile that an entry can list, at the level that the configuration record names. Without a
   * record level from 1 to 13, the level is the lowest one in Dolby's level table that carries the
   * decoded size at the maximum frame rate.
   */
  record Declarable(@NonNull DolbyVisionProfile profile, int level) implements CopiedDolbyVision {}

  /**
   * Dolby Vision that no entry accepts: profile 6 or 7, an unknown profile, a profile that the
   * contract does not name, or a stream whose level neither its record nor the level table gives.
   * Until streamarr/streamarr-server#652 stores a maximum frame rate, the table gives no level. A
   * copy on a PQ or HLG base can still play with the Dolby Vision stripped (ADR 0013).
   */
  record Undeclarable() implements CopiedDolbyVision {}

  /**
   * Profile 5 or 10.0, which no entry accepts until the worker can write the Dolby Vision sample
   * entry and configuration record that such a copy needs: dvh1 for profile 5, dav1 for 10.0
   * (streamarr/streamarr-server#396). The worker writes HEVC copies under hvc1 without the record
   * and cannot write dav1, and a picture with no compatible base shows wrong colours or is refused
   * without them.
   */
  record NeedsDolbyVisionSampleEntry() implements CopiedDolbyVision {}

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
      return new Undeclarable();
    }

    if (base == CompatibleBase.SDR && isSdrBaseProfile(codecFamily, profileNumber.getAsInt())) {
      return new KeptUnderSdrBase();
    }

    // Profile 5 has no compatible base whatever the signaled transfer claims, so a PQ transfer
    // never makes it a profile whose base a stripped copy could play.
    if (codecFamily == ProbedCodecFamily.HEVC && profileNumber.getAsInt() == 5) {
      return new NeedsDolbyVisionSampleEntry();
    }

    var profile = namedProfile(codecFamily, profileNumber.getAsInt(), base);
    var level = levelFromRecordOrTable(copy, present);
    if (profile.isEmpty() || level.isEmpty()) {
      return new Undeclarable();
    }

    if (profile.get().compatibleBase() == CompatibleBase.NONE) {
      return new NeedsDolbyVisionSampleEntry();
    }

    return new Declarable(profile.get(), level.getAsInt());
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
