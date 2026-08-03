package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("Filename Encoding Check Tests")
class FilenameEncodingCheckTest {

  @Test
  @DisplayName("Should warn when filenames are decoded with an ASCII locale charset")
  void shouldWarnWhenFilenamesAreDecodedWithAsciiLocaleCharset() {
    var warnings = runCheckWith("ANSI_X3.4-1968");

    assertThat(warnings).singleElement().asString().contains("ANSI_X3.4-1968");
  }

  @Test
  @DisplayName("Should warn when filename encoding is not a known charset")
  void shouldWarnWhenFilenameEncodingIsNotAKnownCharset() {
    assertThat(runCheckWith("")).hasSize(1);
  }

  @Test
  @DisplayName("Should stay silent when filenames are decoded as UTF-8")
  void shouldStaySilentWhenFilenamesAreDecodedAsUtf8() {
    assertThat(runCheckWith("UTF-8")).isEmpty();
  }

  @Test
  @DisplayName("Should stay silent when filename encoding is a UTF-8 alias")
  void shouldStaySilentWhenFilenameEncodingIsAUtf8Alias() {
    assertThat(runCheckWith("utf8")).isEmpty();
  }

  private static List<String> runCheckWith(String filenameEncoding) {
    var logger = (Logger) LoggerFactory.getLogger(FilenameEncodingCheck.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);

    try {
      new FilenameEncodingCheck(filenameEncoding).warnWhenFilenamesAreNotDecodedAsUtf8();
    } finally {
      logger.detachAppender(appender);
    }

    return appender.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }
}
