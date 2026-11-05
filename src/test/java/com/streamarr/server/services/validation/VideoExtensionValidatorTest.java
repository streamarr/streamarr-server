package com.streamarr.server.services.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Video Extension Validation Tests")
class VideoExtensionValidatorTest {

  private final VideoExtensionValidator videoExtensionValidator = new VideoExtensionValidator();

  @Test
  @DisplayName("Should successfully validate when given valid extension")
  void shouldValidateSuccessfully() {
    assertTrue(videoExtensionValidator.validate("mp4"));
  }

  @Test
  @DisplayName("Should fail validation when given invalid extension")
  void shouldFailValidation() {
    assertFalse(videoExtensionValidator.validate("iso"));
  }

  // Broadcast recordings end in .ts, AVCHD camcorders write .MTS and HDV captures .m2t.
  @ParameterizedTest(name = "Should accept an MPEG-TS file when its extension is {0}")
  @ValueSource(strings = {"ts", "mts", "m2t", "m2ts", "TS", "MTS", "M2T"})
  @DisplayName("Should accept an MPEG-TS file when its extension names a transport stream")
  void shouldAcceptAnMpegTsFileWhenItsExtensionNamesATransportStream(String extension) {
    assertTrue(videoExtensionValidator.validate(extension));
  }

  @ParameterizedTest(name = "Should reject a file when its extension is {0}")
  @ValueSource(strings = {"t", "tsv", "m2", "mt", "m2tx", "part", ""})
  @DisplayName("Should reject a file when its extension only resembles a transport stream's")
  void shouldRejectAFileWhenItsExtensionOnlyResemblesATransportStreams(String extension) {
    assertFalse(videoExtensionValidator.validate(extension));
  }
}
