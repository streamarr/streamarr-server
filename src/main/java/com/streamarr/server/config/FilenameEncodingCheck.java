package com.streamarr.server.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Warns when the JVM decodes filesystem bytes with something other than UTF-8.
 *
 * <p>{@code sun.jnu.encoding} follows the process locale rather than {@code file.encoding}, so a
 * container started with {@code LC_CTYPE=POSIX} turns every non-ASCII byte in a filename into
 * U+FFFD. Persisted filenames are immune because they are decoded from the filepath URI, but the
 * remaining JDK filesystem interactions are not: a library root configured as {@code /media/Ação}
 * is encoded back to the filesystem through the same charset and will not resolve.
 */
@Slf4j
@Component
public class FilenameEncodingCheck {

  private final String filenameEncoding;

  public FilenameEncodingCheck(@Value("${sun.jnu.encoding:}") String filenameEncoding) {
    this.filenameEncoding = filenameEncoding;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void warnWhenFilenamesAreNotDecodedAsUtf8() {
    if (isUtf8(filenameEncoding)) {
      return;
    }

    var charsetDescription =
        filenameEncoding.isBlank() ? "an unknown charset" : "'" + filenameEncoding + "'";
    log.warn(
        "Filenames are read from the filesystem as {} rather than UTF-8, because sun.jnu.encoding"
            + " follows the process locale. Library paths containing non-ASCII characters may fail"
            + " to resolve. Start this process under a UTF-8 locale, for example"
            + " LC_ALL=C.UTF-8.",
        charsetDescription);
  }

  private static boolean isUtf8(String charsetName) {
    try {
      return StandardCharsets.UTF_8.equals(Charset.forName(charsetName));
    } catch (IllegalArgumentException _) {
      return false;
    }
  }
}
