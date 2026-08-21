package com.streamarr.server.services.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.fixtures.ProfileFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Profile Safety Rule Tests")
class ProfileSafetyRuleTest {

  @Test
  @DisplayName("Should require no PIN when no Kid Profile is available")
  void shouldRequireNoPinWhenNoKidProfileIsAvailable() {
    var adults = List.of(adult(null), adult(null));

    assertThat(ProfileSafetyRule.profilesRequiringPin(adults)).isEmpty();
    assertThat(ProfileSafetyRule.lockedProfiles(adults)).isEmpty();
  }

  @Test
  @DisplayName("Should lock every Adult without an effective PIN when a Kid is available")
  void shouldLockEveryAdultWithoutEffectivePinWhenKidIsAvailable() {
    var pinned = adult("{argon2id}hash");
    var unpinned = adult(null);
    var blankPin = adult("   ");
    var kid = kid(null, null);
    var available = List.of(pinned, unpinned, blankPin, kid);

    assertThat(ProfileSafetyRule.profilesRequiringPin(available))
        .containsExactlyInAnyOrder(pinned.getId(), unpinned.getId(), blankPin.getId());
    assertThat(ProfileSafetyRule.lockedProfiles(available))
        .containsExactlyInAnyOrder(unpinned.getId(), blankPin.getId());
  }

  @Test
  @DisplayName("Should not require a PIN when a Kid is the only Profile")
  void shouldNotRequirePinWhenKidIsOnlyProfile() {
    var onlyKid = kid(null, null);

    assertThat(ProfileSafetyRule.profilesRequiringPin(List.of(onlyKid))).isEmpty();
  }

  @Test
  @DisplayName("Should require a PIN when a Kid is less restrictive than another Kid")
  void shouldRequirePinWhenKidIsLessRestrictiveThanAnotherKid() {
    var strict = kid(7, null);
    var looser = kid(12, null);
    var unlimited = kid(null, null);
    var available = List.of(strict, looser, unlimited);

    assertThat(ProfileSafetyRule.profilesRequiringPin(available))
        .containsExactlyInAnyOrder(looser.getId(), unlimited.getId());
    assertThat(ProfileSafetyRule.lockedProfiles(available))
        .containsExactlyInAnyOrder(looser.getId(), unlimited.getId());
  }

  @Test
  @DisplayName("Should treat Kids as equally restrictive when ceilings match")
  void shouldTreatKidsAsEquallyRestrictiveWhenCeilingsMatch() {
    var first = kid(10, null);
    var second = kid(10, null);

    assertThat(ProfileSafetyRule.profilesRequiringPin(List.of(first, second))).isEmpty();
  }

  private static Profile adult(String pinHash) {
    return ProfileFixture.defaultProfileBuilder().id(UUID.randomUUID()).pinHash(pinHash).build();
  }

  private static Profile kid(Integer ceiling, String pinHash) {
    return ProfileFixture.kidProfileBuilder()
        .id(UUID.randomUUID())
        .maximumAllowedRatingAge(ceiling)
        .pinHash(pinHash)
        .build();
  }
}
