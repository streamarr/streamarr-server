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
  @DisplayName("Should keep an ordinary device name unchanged")
  void shouldKeepOrdinaryDeviceNameUnchanged() {
    assertThat(DeviceName.sanitize("Apple TV")).isEqualTo("Apple TV");
  }

  @Test
  @DisplayName("Should keep non-ASCII device names intact")
  void shouldKeepNonAsciiDeviceNamesIntact() {
    // Output encoding, not an input whitelist, is the defence on the page that renders this.
    assertThat(DeviceName.sanitize("Salón TV")).isEqualTo("Salón TV");
    assertThat(DeviceName.sanitize("居間のテレビ")).isEqualTo("居間のテレビ");
  }

  @Test
  @DisplayName("Should strip control characters that could forge log lines")
  void shouldStripControlCharactersThatCouldForgeLogLines() {
    assertThat(DeviceName.sanitize("Living\nRoom\tTV\0")).isEqualTo("LivingRoomTV");
  }

  @ParameterizedTest(name = "Should strip bidi control U+{0}")
  @ValueSource(
      ints = {
        0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C,
        0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069
      })
  @DisplayName("Should strip bidi controls that can spoof displayed names")
  void shouldStripBidiControlsThatCanSpoofDisplayedNames(int bidiControl) {
    var spoofed = "Living" + Character.toString(bidiControl) + "Room TV";

    assertThat(DeviceName.sanitize(spoofed)).isEqualTo("LivingRoom TV");
  }

  @Test
  @DisplayName("Should keep legitimate format characters used by emoji")
  void shouldKeepLegitimateFormatCharactersUsedByEmoji() {
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
  @DisplayName("Should cap long names without splitting a character")
  void shouldCapLongNamesWithoutSplittingCharacter() {
    var sanitized = DeviceName.sanitize("🎬".repeat(80));

    assertThat(sanitized.codePointCount(0, sanitized.length())).isEqualTo(64);
    assertThat(sanitized).isEqualTo("🎬".repeat(64));
  }

  @Test
  @DisplayName("Should keep a name exactly at the code-point limit")
  void shouldKeepNameExactlyAtCodePointLimit() {
    var boundary = "🎬".repeat(64);

    assertThat(DeviceName.sanitize(boundary)).isEqualTo(boundary);
  }
}
