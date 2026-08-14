package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Tag("UnitTest")
@DisplayName("Profile PIN Service Tests")
class ProfilePinServiceTest {

  private final ProfilePinService service = new ProfilePinService(new BCryptPasswordEncoder());

  @Test
  @DisplayName("Should hash valid profile PIN and verify it for profile entry")
  void shouldHashValidProfilePinAndVerifyItForProfileEntry() {
    var pinHash = service.encode("2468");
    var profile = Profile.builder().name("Protected").pinHash(pinHash).build();

    assertThat(pinHash).doesNotContain("2468");
    assertThatCode(() -> service.requireEntry(profile, "2468")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should accept a twelve digit profile PIN")
  void shouldAcceptTwelveDigitProfilePin() {
    var pin = "123456789012";

    var pinHash = service.encode(pin);

    assertThatCode(() -> service.requireEntry(Profile.builder().pinHash(pinHash).build(), pin))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"123", "1234567890123", "12a4", "    "})
  @DisplayName("Should reject profile PIN outside four to twelve digits")
  void shouldRejectProfilePinOutsideFourToTwelveDigits(String pin) {
    assertThatThrownBy(() -> service.encode(pin)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should deny protected profile entry when PIN missing or wrong")
  void shouldDenyProtectedProfileEntryWhenPinMissingOrWrong() {
    var profile = Profile.builder().name("Protected").pinHash(service.encode("2468")).build();

    assertThatThrownBy(() -> service.requireEntry(profile, null))
        .isInstanceOf(ProfileAccessDeniedException.class);
    assertThatThrownBy(() -> service.requireEntry(profile, "1357"))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should allow unprotected profile entry without PIN")
  void shouldAllowUnprotectedProfileEntryWithoutPin() {
    var profile = Profile.builder().name("Open").build();

    assertThatCode(() -> service.requireEntry(profile, null)).doesNotThrowAnyException();
  }
}
