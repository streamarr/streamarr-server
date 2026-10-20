package com.streamarr.server.services.events.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Library item audience")
class LibraryItemAudienceTest {

  private static final UUID WATCHER = UUID.fromString("0f0e4c8e-34a8-4c39-9a52-6d5d2f1c9e01");
  private static final UUID OTHER = UUID.fromString("7b8c1d2e-5f60-4a71-8b92-a3b4c5d6e7f8");

  @Test
  @DisplayName("Should include any Profile when the audience is every Profile")
  void shouldIncludeAnyProfileWhenTheAudienceIsEveryProfile() {
    var audience = LibraryItemAudience.everyProfile();

    assertThat(audience.includes(WATCHER)).isTrue();
    assertThat(audience.includes(OTHER)).isTrue();
  }

  @Test
  @DisplayName("Should include only the named Profile when the audience is one Profile")
  void shouldIncludeOnlyTheNamedProfileWhenTheAudienceIsOneProfile() {
    var audience = LibraryItemAudience.profile(WATCHER);

    assertThat(audience.includes(WATCHER)).isTrue();
    assertThat(audience.includes(OTHER)).isFalse();
  }

  @Test
  @DisplayName("Should refuse a one-Profile audience when the Profile is missing")
  void shouldRefuseAOneProfileAudienceWhenTheProfileIsMissing() {
    assertThatThrownBy(() -> LibraryItemAudience.profile(null))
        .isInstanceOf(NullPointerException.class);
  }
}
