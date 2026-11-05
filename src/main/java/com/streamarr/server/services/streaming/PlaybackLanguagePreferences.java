package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.media.MediaFile;
import java.util.List;
import java.util.UUID;

/**
 * The audio languages that a Profile's language preferences try for one media file, in order: the
 * overrides of its movie or episode, its season and its series, then the Profile's own list (ADR
 * 0043). A file of no title gets the Profile's own list.
 */
@FunctionalInterface
public interface PlaybackLanguagePreferences {

  List<Language> audioLanguagesFor(UUID profileId, MediaFile file);
}
