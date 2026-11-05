package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.regex.Pattern;
import lombok.NonNull;

/**
 * The marks that an audio track's name gives it when no disposition does (ADR 0043): its title, or
 * its handler name when it has no title, marks a commentary or an audio description by a whole
 * word, whatever the case. A generic handler name, such as "SoundHandler", holds none of these
 * words.
 */
public final class AudioTrackMarks {

  private static final Pattern COMMENTARY_OR_AUDIO_DESCRIPTION =
      Pattern.compile(
          "\\b(commentary|commentaire|kommentar|comentario|commento"
              + "|audio description|descriptive|described|dvs|visually impaired)\\b",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

  private AudioTrackMarks() {}

  public static boolean isNamedCommentaryOrAudioDescription(@NonNull StreamInfo stream) {
    return name(stream)
        .filter(name -> COMMENTARY_OR_AUDIO_DESCRIPTION.matcher(name).find())
        .isPresent();
  }

  private static Optional<String> name(StreamInfo stream) {
    return stream.title().or(stream::handlerName);
  }
}
