package com.streamarr.server.graphql.mutation.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.graphql.mutation.InputMutationError;
import com.streamarr.server.graphql.mutation.MutationError;
import com.streamarr.server.services.identity.ProfileRejections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Every service rejection maps to exactly its schema error type and field-specific input path. */
@Tag("UnitTest")
@DisplayName("Profile Errors Tests")
class ProfileErrorsTest {

  @Test
  @DisplayName("Should map every creation rejection to its schema error")
  void shouldMapEveryCreationRejectionToItsSchemaError() {
    assertInputError(
        ProfileErrors.toCreateProfileError(new ProfileRejections.HouseholdNotFound()),
        HouseholdNotFoundError.class,
        "householdId");
    assertInputError(
        ProfileErrors.toCreateProfileError(new ProfileRejections.ProfileNameRequired()),
        ProfileNameRequiredError.class,
        "name");
    assertInputError(
        ProfileErrors.toCreateProfileError(new ProfileRejections.ProfileNameTaken()),
        ProfileNameTakenError.class,
        "name");
    assertThat(ProfileErrors.toCreateProfileError(new ProfileRejections.HomeAnchorRequired()))
        .isInstanceOf(HomeAnchorRequiredError.class)
        .isNotInstanceOf(InputMutationError.class);
    assertInputError(
        ProfileErrors.toCreateProfileError(new ProfileRejections.ManagerNotEligible()),
        ManagerNotEligibleError.class,
        "localManagerAccountId");
    assertInputError(
        ProfileErrors.toCreateProfileError(new ProfileRejections.LocalManagerNotFound()),
        LocalManagerNotFoundError.class,
        "localManagerAccountId");
    assertInputError(
        ProfileErrors.toCreateProfileError(new ProfileRejections.MaximumAllowedRatingAgeInvalid()),
        MaximumAllowedRatingAgeInvalidError.class,
        "maximumAllowedRatingAge");
  }

  @Test
  @DisplayName("Should map the edit rejections to their schema errors")
  void shouldMapEditRejectionsToTheirSchemaErrors() {
    assertThat(ProfileErrors.toRenameProfileError(new ProfileRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(ProfileErrors.toRenameProfileError(new ProfileRejections.ProfileNameRequired()))
        .isInstanceOf(ProfileNameRequiredError.class);
    assertThat(ProfileErrors.toRenameProfileError(new ProfileRejections.ProfileNameTaken()))
        .isInstanceOf(ProfileNameTakenError.class);
    assertThat(ProfileErrors.toSetProfilePictureError(new ProfileRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
  }

  @Test
  @DisplayName("Should map the policy rejections across all three transition mutations")
  void shouldMapPolicyRejectionsAcrossAllThreeTransitionMutations() {
    assertThat(ProfileErrors.toChangeProfileKindError(new ProfileRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(
            ProfileErrors.toChangeProfileKindError(
                new ProfileRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(ProfileErrors.toChangeProfileKindError(new ProfileRejections.HomeAnchorRequired()))
        .isInstanceOf(HomeAnchorRequiredError.class);
    assertInputError(
        ProfileErrors.toChangeProfileKindError(new ProfileRejections.RestrictedAccountAuthority()),
        RestrictedAccountAuthorityError.class,
        "maximumAllowedRatingAge");
    assertInputError(
        ProfileErrors.toChangeProfileKindError(
            new ProfileRejections.MaximumAllowedRatingAgeInvalid()),
        MaximumAllowedRatingAgeInvalidError.class,
        "maximumAllowedRatingAge");
    assertThat(
            ProfileErrors.toSetProfileContentCeilingError(new ProfileRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(
            ProfileErrors.toSetProfileContentCeilingError(
                new ProfileRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(
            ProfileErrors.toSetProfileContentCeilingError(
                new ProfileRejections.HomeAnchorRequired()))
        .isInstanceOf(HomeAnchorRequiredError.class);
    assertInputError(
        ProfileErrors.toSetProfileContentCeilingError(
            new ProfileRejections.RestrictedAccountAuthority()),
        RestrictedAccountAuthorityError.class,
        "maximumAllowedRatingAge");
    assertInputError(
        ProfileErrors.toSetProfileContentCeilingError(
            new ProfileRejections.MaximumAllowedRatingAgeInvalid()),
        MaximumAllowedRatingAgeInvalidError.class,
        "maximumAllowedRatingAge");
    assertThat(
            ProfileErrors.toClearProfileContentCeilingError(
                new ProfileRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(
            ProfileErrors.toClearProfileContentCeilingError(
                new ProfileRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(
            ProfileErrors.toClearProfileContentCeilingError(
                new ProfileRejections.HomeAnchorRequired()))
        .isInstanceOf(HomeAnchorRequiredError.class);
    assertInputError(
        ProfileErrors.toClearProfileContentCeilingError(
            new ProfileRejections.RestrictedAccountAuthority()),
        RestrictedAccountAuthorityError.class,
        "maximumAllowedRatingAge");
    assertInputError(
        ProfileErrors.toClearProfileContentCeilingError(
            new ProfileRejections.MaximumAllowedRatingAgeInvalid()),
        MaximumAllowedRatingAgeInvalidError.class,
        "maximumAllowedRatingAge");
  }

  @Test
  @DisplayName("Should name the Household in the lock error only when it may be seen")
  void shouldNameHouseholdInLockErrorOnlyWhenItMayBeSeen() {
    var householdId = UUID.randomUUID();
    var named =
        ProfileErrors.toClearProfilePinError(
            new ProfileRejections.WouldLockProfile(householdId, Optional.of("Beach House")));
    var unnamed =
        ProfileErrors.toClearProfilePinError(
            new ProfileRejections.WouldLockProfile(householdId, Optional.empty()));

    assertThat(named)
        .isInstanceOf(WouldLockProfileError.class)
        .satisfies(
            error -> assertThat(((WouldLockProfileError) error).message()).contains("Beach House"));
    assertThat(((WouldLockProfileError) unnamed).message()).doesNotContain("Beach House");
    assertThat(((WouldLockProfileError) unnamed).householdId()).isEqualTo(householdId);
    assertThat(ProfileErrors.toClearProfilePinError(new ProfileRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
  }

  @Test
  @DisplayName("Should map the PIN and deletion rejections to their schema errors")
  void shouldMapPinAndDeletionRejectionsToTheirSchemaErrors() {
    assertThat(ProfileErrors.toSetProfilePinError(new ProfileRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(ProfileErrors.toSetProfilePinError(new ProfileRejections.PinMalformed()))
        .isInstanceOf(PinMalformedError.class);
    assertThat(ProfileErrors.toOverrideProfilePinError(new ProfileRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(ProfileErrors.toOverrideProfilePinError(new ProfileRejections.PinMalformed()))
        .isInstanceOf(PinMalformedError.class);
    assertThat(ProfileErrors.toOverrideProfilePinError(new ProfileRejections.ReasonRequired()))
        .isInstanceOf(ReasonRequiredError.class);
    assertThat(
            ProfileErrors.toOverrideProfilePinError(
                new ProfileRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(ProfileErrors.toDeleteProfileError(new ProfileRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(ProfileErrors.toDeleteProfileError(new ProfileRejections.ProfileNotDeletable()))
        .isInstanceOf(ProfileNotDeletableError.class);
    assertThat(ProfileErrors.toDeleteProfileError(new ProfileRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
  }

  private static <T extends InputMutationError> void assertInputError(
      MutationError actual, Class<T> expectedType, String expectedPath) {
    assertThat(actual).isInstanceOf(expectedType);
    assertThat(expectedType.cast(actual).inputPath()).containsExactly(expectedPath);
  }
}
