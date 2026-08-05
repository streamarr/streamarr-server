package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Device Name Sanitization Tests")
class DeviceNameTest {

  @Test
  @DisplayName("Should keep the device name unchanged when it contains ordinary characters")
  void shouldKeepDeviceNameUnchangedWhenCharactersOrdinary() {
    assertThat(DeviceName.sanitize("Apple TV")).isEqualTo("Apple TV");
  }

  @Test
  @DisplayName("Should keep the device name intact when it contains non-ASCII characters")
  void shouldKeepDeviceNameIntactWhenCharactersNonAscii() {
    // Output encoding, not an input whitelist, is the defence on the page that renders this.
    assertThat(DeviceName.sanitize("Salón TV")).isEqualTo("Salón TV");
    assertThat(DeviceName.sanitize("居間のテレビ")).isEqualTo("居間のテレビ");
  }

  @Test
  @DisplayName("Should normalize the device name to NFC when its characters are decomposed")
  void shouldNormalizeDeviceNameToNfcWhenCharactersDecomposed() {
    assertThat(DeviceName.sanitize("Cafe\u0301 TV")).isEqualTo("Café TV");
  }

  @Test
  @DisplayName("Should trim only surrounding whitespace when the device name has internal spacing")
  void shouldTrimOnlySurroundingWhitespaceWhenDeviceNameHasInternalSpacing() {
    assertThat(DeviceName.sanitize("  Living Room TV  ")).isEqualTo("Living Room TV");
  }

  @Test
  @DisplayName("Should strip control characters when they could forge log lines")
  void shouldStripControlCharactersWhenTheyCouldForgeLogLines() {
    assertThat(DeviceName.sanitize("Living\nRoom\tTV\0")).isEqualTo("LivingRoomTV");
  }

  @ParameterizedTest(name = "Should strip bidi control U+{0}")
  @ValueSource(
      ints = {
        0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C,
        0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069
      })
  @DisplayName("Should strip bidi controls when they could spoof displayed names")
  void shouldStripBidiControlsWhenTheyCouldSpoofDisplayedNames(int bidiControl) {
    var spoofed = "Living" + Character.toString(bidiControl) + "Room TV";

    assertThat(DeviceName.sanitize(spoofed)).isEqualTo("LivingRoom TV");
  }

  @Test
  @DisplayName("Should keep format characters when they are used by emoji")
  void shouldKeepFormatCharactersWhenUsedByEmoji() {
    var familyTv = "Family 👨‍👩‍👧‍👦 TV";

    assertThat(DeviceName.sanitize(familyTv)).isEqualTo(familyTv);
  }

  @ParameterizedTest(name = "Should fall back for blank input [{index}]")
  @NullSource
  @ValueSource(strings = {"", "   ", "\n\t"})
  @DisplayName("Should fall back to a placeholder when no usable name remains")
  void shouldFallBackToPlaceholderWhenNoUsableNameRemains(String rawDeviceName) {
    assertThat(DeviceName.sanitize(rawDeviceName)).isEqualTo("Unknown device");
  }

  @Test
  @DisplayName("Should cap the name without splitting a character when it exceeds the limit")
  void shouldCapNameWithoutSplittingCharacterWhenLimitExceeded() {
    var sanitized = DeviceName.sanitize("🎬".repeat(80));

    assertThat(sanitized.codePointCount(0, sanitized.length())).isEqualTo(64);
    assertThat(sanitized).isEqualTo("🎬".repeat(64));
  }

  @Test
  @DisplayName("Should keep the name unchanged when it is exactly at the code-point limit")
  void shouldKeepNameUnchangedWhenExactlyAtCodePointLimit() {
    var boundary = "🎬".repeat(64);

    assertThat(DeviceName.sanitize(boundary)).isEqualTo(boundary);
  }
}
