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
  @DisplayName("Should map every creation rejection when the service refuses creation")
  void shouldMapEveryCreationRejectionWhenServiceRefusesCreation() {
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
    assertThat(ProfileErrors.toCreateProfileError(new ProfileRejections.EligibleManagerRequired()))
        .isInstanceOf(ProfileRequiresEligibleManagerError.class)
        .isNotInstanceOf(InputMutationError.class);
    assertInputError(
        ProfileErrors.toCreateProfileError(new ProfileRejections.ManagerNotEligible()),
        ProfileManagerNotEligibleError.class,
        "profileManagerAccountId");
    assertInputError(
        ProfileErrors.toCreateProfileError(new ProfileRejections.LocalManagerNotFound()),
        AccountNotFoundError.class,
        "profileManagerAccountId");
    assertInputError(
        ProfileErrors.toCreateProfileError(new ProfileRejections.MaximumAllowedRatingAgeInvalid()),
        MaximumAllowedRatingAgeInvalidError.class,
        "maximumAllowedRatingAge");
  }

  @Test
  @DisplayName("Should map every rename rejection when the service refuses a rename")
  void shouldMapEveryRenameRejectionWhenServiceRefusesRename() {
    assertInputError(
        ProfileErrors.toRenameProfileError(new ProfileRejections.ProfileNotFound()),
        ProfileNotFoundError.class,
        "profileId");
    assertInputError(
        ProfileErrors.toRenameProfileError(new ProfileRejections.ProfileNameRequired()),
        ProfileNameRequiredError.class,
        "name");
    assertInputError(
        ProfileErrors.toRenameProfileError(new ProfileRejections.ProfileNameTaken()),
        ProfileNameTakenError.class,
        "name");
  }

  @Test
  @DisplayName("Should map not found when the service refuses a picture change")
  void shouldMapNotFoundWhenServiceRefusesPictureChange() {
    assertInputError(
        ProfileErrors.toSetProfilePictureError(new ProfileRejections.ProfileNotFound()),
        ProfileNotFoundError.class,
        "profileId");
  }

  @Test
  @DisplayName("Should map every policy rejection when the service refuses a kind change")
  void shouldMapEveryPolicyRejectionWhenServiceRefusesKindChange() {
    assertInputError(
        ProfileErrors.toChangeProfileKindError(new ProfileRejections.ProfileNotFound()),
        ProfileNotFoundError.class,
        "profileId");
    assertThat(
            ProfileErrors.toChangeProfileKindError(
                new ProfileRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(
            ProfileErrors.toChangeProfileKindError(new ProfileRejections.EligibleManagerRequired()))
        .isInstanceOf(ProfileRequiresEligibleManagerError.class);
    assertInputError(
        ProfileErrors.toChangeProfileKindError(new ProfileRejections.RestrictedAccountAuthority()),
        RestrictedAccountCannotAdministerError.class,
        "kind");
    assertInputError(
        ProfileErrors.toChangeProfileKindError(
            new ProfileRejections.MaximumAllowedRatingAgeInvalid()),
        MaximumAllowedRatingAgeInvalidError.class,
        "maximumAllowedRatingAge");
  }

  @Test
  @DisplayName("Should map every policy rejection when setting the maximum allowed rating age")
  void shouldMapEveryPolicyRejectionWhenSettingMaximumAllowedRatingAge() {
    assertInputError(
        ProfileErrors.toSetProfileMaximumAllowedRatingAgeError(
            new ProfileRejections.ProfileNotFound()),
        ProfileNotFoundError.class,
        "profileId");
    assertThat(
            ProfileErrors.toSetProfileMaximumAllowedRatingAgeError(
                new ProfileRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(
            ProfileErrors.toSetProfileMaximumAllowedRatingAgeError(
                new ProfileRejections.EligibleManagerRequired()))
        .isInstanceOf(ProfileRequiresEligibleManagerError.class);
    assertInputError(
        ProfileErrors.toSetProfileMaximumAllowedRatingAgeError(
            new ProfileRejections.RestrictedAccountAuthority()),
        RestrictedAccountCannotAdministerError.class,
        "maximumAllowedRatingAge");
    assertInputError(
        ProfileErrors.toSetProfileMaximumAllowedRatingAgeError(
            new ProfileRejections.MaximumAllowedRatingAgeInvalid()),
        MaximumAllowedRatingAgeInvalidError.class,
        "maximumAllowedRatingAge");
  }

  @Test
  @DisplayName("Should map every policy rejection when removing the maximum allowed rating age")
  void shouldMapEveryPolicyRejectionWhenRemovingMaximumAllowedRatingAge() {
    assertInputError(
        ProfileErrors.toRemoveProfileMaximumAllowedRatingAgeError(
            new ProfileRejections.ProfileNotFound()),
        ProfileNotFoundError.class,
        "profileId");
    assertThat(
            ProfileErrors.toRemoveProfileMaximumAllowedRatingAgeError(
                new ProfileRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(
            ProfileErrors.toRemoveProfileMaximumAllowedRatingAgeError(
                new ProfileRejections.EligibleManagerRequired()))
        .isInstanceOf(ProfileRequiresEligibleManagerError.class);
    assertInputError(
        ProfileErrors.toRemoveProfileMaximumAllowedRatingAgeError(
            new ProfileRejections.RestrictedAccountAuthority()),
        RestrictedAccountCannotAdministerError.class,
        "maximumAllowedRatingAge");
    assertInputError(
        ProfileErrors.toRemoveProfileMaximumAllowedRatingAgeError(
            new ProfileRejections.MaximumAllowedRatingAgeInvalid()),
        MaximumAllowedRatingAgeInvalidError.class,
        "maximumAllowedRatingAge");
  }

  @Test
  @DisplayName("Should name the Household in the lock error when it may be seen")
  void shouldNameHouseholdInLockErrorWhenItMayBeSeen() {
    var householdId = UUID.randomUUID();
    var named =
        ProfileErrors.toRemoveProfilePinError(
            new ProfileRejections.WouldLockProfile(householdId, Optional.of("Beach House")));
    var unnamed =
        ProfileErrors.toRemoveProfilePinError(
            new ProfileRejections.WouldLockProfile(householdId, Optional.empty()));

    assertThat(named)
        .isInstanceOf(ProfilePinRequiredError.class)
        .satisfies(
            error ->
                assertThat(((ProfilePinRequiredError) error).message()).contains("Beach House"));
    assertThat(((ProfilePinRequiredError) unnamed).message()).doesNotContain("Beach House");
    assertThat(((ProfilePinRequiredError) unnamed).householdId()).isEqualTo(householdId);
  }

  @Test
  @DisplayName("Should map every set-PIN rejection when the service refuses the mutation")
  void shouldMapEverySetPinRejectionWhenServiceRefusesMutation() {
    assertInputError(
        ProfileErrors.toSetProfilePinError(new ProfileRejections.ProfileNotFound()),
        ProfileNotFoundError.class,
        "profileId");
    assertInputError(
        ProfileErrors.toSetProfilePinError(new ProfileRejections.PinMalformed()),
        InvalidProfilePinError.class,
        "pin");
  }

  @Test
  @DisplayName("Should map every remove-PIN rejection when the service refuses the mutation")
  void shouldMapEveryRemovePinRejectionWhenServiceRefusesMutation() {
    assertInputError(
        ProfileErrors.toRemoveProfilePinError(new ProfileRejections.ProfileNotFound()),
        ProfileNotFoundError.class,
        "profileId");
  }

  @Test
  @DisplayName("Should map every override-PIN rejection when the service refuses the mutation")
  void shouldMapEveryOverridePinRejectionWhenServiceRefusesMutation() {
    assertInputError(
        ProfileErrors.toOverrideProfilePinError(new ProfileRejections.ProfileNotFound()),
        ProfileNotFoundError.class,
        "profileId");
    assertInputError(
        ProfileErrors.toOverrideProfilePinError(new ProfileRejections.PinMalformed()),
        InvalidProfilePinError.class,
        "pin");
    assertInputError(
        ProfileErrors.toOverrideProfilePinError(new ProfileRejections.ReasonRequired()),
        ReasonRequiredError.class,
        "reason");
    assertThat(
            ProfileErrors.toOverrideProfilePinError(
                new ProfileRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
  }

  @Test
  @DisplayName("Should map every deletion rejection when the service refuses deletion")
  void shouldMapEveryDeletionRejectionWhenServiceRefusesDeletion() {
    assertInputError(
        ProfileErrors.toDeleteProfileError(new ProfileRejections.ProfileNotFound()),
        ProfileNotFoundError.class,
        "profileId");
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
