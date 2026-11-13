package com.streamarr.server.services.serversettings;

import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;

/**
 * The server's internet upload speed in megabits per second, as a ServerAdmin enters it from a
 * speed test, with the range a ServerAdmin can choose from. Stream sessions that count against the
 * upload budget may use {@value #STREAM_SHARE_PERCENT}% of it together. Empty while the budget is
 * off, which is the default.
 */
public record UploadBudgetSetting(@NonNull Optional<Integer> internetUploadMegabitsPerSecond) {

  public static final int MINIMUM_INTERNET_UPLOAD_MEGABITS_PER_SECOND = 1;
  public static final int MAXIMUM_INTERNET_UPLOAD_MEGABITS_PER_SECOND = 10_000;

  // The rest of the upload stays free for the household's other traffic and for the variation
  // around each stream's average bitrate.
  public static final int STREAM_SHARE_PERCENT = 80;

  private static final long BITS_PER_MEGABIT = 1_000_000L;
  private static final int WHOLE = 100;

  public int minimumInternetUploadMegabitsPerSecond() {
    return MINIMUM_INTERNET_UPLOAD_MEGABITS_PER_SECOND;
  }

  public int maximumInternetUploadMegabitsPerSecond() {
    return MAXIMUM_INTERNET_UPLOAD_MEGABITS_PER_SECOND;
  }

  public int streamSharePercent() {
    return STREAM_SHARE_PERCENT;
  }

  /** The megabits per second that counted stream sessions may use together. */
  public Optional<Double> streamShareMegabitsPerSecond() {
    return internetUploadMegabitsPerSecond.map(
        megabits -> megabits * STREAM_SHARE_PERCENT / (double) WHOLE);
  }

  public OptionalLong internetUploadBitsPerSecond() {
    return internetUploadMegabitsPerSecond
        .map(megabits -> OptionalLong.of(megabits * BITS_PER_MEGABIT))
        .orElseGet(OptionalLong::empty);
  }

  /** The bits per second that counted stream sessions may use together. */
  public OptionalLong streamShareBitsPerSecond() {
    return internetUploadMegabitsPerSecond
        .map(
            megabits -> OptionalLong.of(megabits * BITS_PER_MEGABIT * STREAM_SHARE_PERCENT / WHOLE))
        .orElseGet(OptionalLong::empty);
  }

  static boolean allows(@NonNull Optional<Integer> internetUploadMegabitsPerSecond) {
    return internetUploadMegabitsPerSecond
        .map(
            megabits ->
                megabits >= MINIMUM_INTERNET_UPLOAD_MEGABITS_PER_SECOND
                    && megabits <= MAXIMUM_INTERNET_UPLOAD_MEGABITS_PER_SECOND)
        .orElse(true);
  }
}
