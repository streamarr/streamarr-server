package com.streamarr.server.fixtures;

import com.streamarr.server.services.streaming.PlaybackLanguagePreferences;
import java.util.List;

public final class PlaybackLanguagePreferencesFixture {

  private PlaybackLanguagePreferencesFixture() {}

  /** Preferences that name no language, so a stream session plays the file's default audio. */
  public static PlaybackLanguagePreferences noLanguagePreferences() {
    return (profileId, file) -> List.of();
  }
}
