package com.streamarr.server.domain.streaming;

import java.util.Locale;
import java.util.Optional;
import lombok.NonNull;

/**
 * The Dolby Vision of a stream copy that the playlist describes as Dolby Vision (ADR 0013): its
 * profile, and the Dolby Vision level that the decision set. The copy's Dolby Vision configuration
 * record holds both.
 */
public record DolbyVisionOutput(@NonNull DolbyVisionProfile profile, int level) {

  public DolbyVisionOutput {
    if (!DolbyVisionLevelTable.isTableLevel(level)) {
      throw new IllegalArgumentException("A Dolby Vision output has a level from 1 to 13");
    }
  }

  /**
   * The SUPPLEMENTAL-CODECS value of the copy's playlist entry, such as {@code dvh1.08.06/db1p} for
   * profile 8.1 at level 6 (ADR 0013). Empty for profiles 5 and 10.0, which have no compatible base
   * and carry their Dolby Vision in CODECS.
   */
  public Optional<String> supplementalCodecs() {
    return switch (profile) {
      case PROFILE_8_1 -> Optional.of(codec("dvh1.08", "db1p"));
      case PROFILE_8_4 -> Optional.of(codec("dvh1.08", "db4h"));
      case PROFILE_10_1 -> Optional.of(codec("dav1.10", "db1p"));
      case PROFILE_10_4 -> Optional.of(codec("dav1.10", "db4h"));
      case PROFILE_5, PROFILE_10_0 -> Optional.empty();
    };
  }

  /**
   * The Dolby Vision sample entry that a copy of profile 5 or 10.0 carries in place of its codec's
   * base entry: dvh1 for profile 5 and dav1 for 10.0 (ADR 0013). Empty for a profile with a
   * compatible base, which keeps its base sample entry.
   */
  public Optional<VideoSampleEntry> dolbyVisionSampleEntry() {
    return switch (profile) {
      case PROFILE_5 -> Optional.of(VideoSampleEntry.DVH1);
      case PROFILE_10_0 -> Optional.of(VideoSampleEntry.DAV1);
      case PROFILE_8_1, PROFILE_8_4, PROFILE_10_1, PROFILE_10_4 -> Optional.empty();
    };
  }

  /**
   * The CODECS value of a copy that carries a Dolby Vision sample entry, such as {@code dvh1.05.06}
   * for profile 5 at level 6 (ADR 0013). Empty for a profile with a compatible base, whose CODECS
   * names its base sample entry.
   */
  public Optional<String> codecs() {
    return dolbyVisionSampleEntry()
        .map(
            entry ->
                String.format(
                    Locale.ROOT, "%s.%02d.%02d", entry.getLabel(), profile.profileNumber(), level));
  }

  // The level takes two digits, and the brand names the compatible base: db1p for PQ, db4h for HLG.
  private String codec(String entryAndProfile, String brand) {
    return String.format(Locale.ROOT, "%s.%02d/%s", entryAndProfile, level, brand);
  }
}
