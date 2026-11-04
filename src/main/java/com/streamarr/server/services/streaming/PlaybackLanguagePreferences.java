package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.language.Language;
import java.util.List;
import java.util.UUID;

/**
 * The audio languages that a Profile's language preferences try for one movie or episode, in order
 * (ADR 0043).
 */
@FunctionalInterface
public interface PlaybackLanguagePreferences {

  List<Language> audioLanguagesFor(UUID profileId, UUID titleId);
}
