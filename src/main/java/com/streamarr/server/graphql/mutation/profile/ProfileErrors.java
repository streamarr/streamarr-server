package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.identity.ProfileRejections;
import lombok.NonNull;

/** The exhaustive mappings from service rejection to schema error type, one per union. */
public final class ProfileErrors {

  private static final String PROFILE_ID = "profileId";
  private static final String NAME = "name";
  private static final String PIN = "pin";
  private static final String MAXIMUM_ALLOWED_RATING_AGE = "maximumAllowedRatingAge";

  private ProfileErrors() {}

  public static CreateProfileError toCreateProfileError(
      @NonNull ProfileRejections.CreateProfile rejection) {
    return switch (rejection) {
      case ProfileRejections.HouseholdNotFound _ ->
          new HouseholdNotFoundError("No such Household.", InputPath.of("householdId"));
      case ProfileRejections.ProfileNameRequired _ -> nameRequired();
      case ProfileRejections.ProfileNameTaken _ -> nameTaken();
      case ProfileRejections.EligibleManagerRequired _ -> profileRequiresEligibleManager();
      case ProfileRejections.ManagerNotEligible _ ->
          new ProfileManagerNotEligibleError(
              "That Account cannot manage Profiles because its Personal Profile is restricted.",
              InputPath.of("profileManagerAccountId"));
      case ProfileRejections.ProfileManagerNotEligible _ ->
          new AccountNotFoundError("No such Account.", InputPath.of("profileManagerAccountId"));
      case ProfileRejections.MaximumAllowedRatingAgeInvalid _ -> maximumAllowedRatingAgeInvalid();
    };
  }

  public static RenameProfileError toRenameProfileError(
      @NonNull ProfileRejections.RenameProfile rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.ProfileNameRequired _ -> nameRequired();
      case ProfileRejections.ProfileNameTaken _ -> nameTaken();
    };
  }

  public static SetProfilePictureError toSetProfilePictureError(
      @NonNull ProfileRejections.SetProfilePicture rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
    };
  }

  public static ChangeProfileKindError toChangeProfileKindError(
      @NonNull ProfileRejections.ChangeProfilePolicy rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.ReauthenticationRequired _ -> reauthenticationRequired();
      case ProfileRejections.EligibleManagerRequired _ -> profileRequiresEligibleManager();
      case ProfileRejections.RestrictedAccountAuthority _ ->
          restrictedAccountCannotAdminister("kind");
      case ProfileRejections.MaximumAllowedRatingAgeInvalid _ ->
          maximumAllowedRatingAgeInvalid("kind");
    };
  }

  public static SetProfileMaximumAllowedRatingAgeError toSetProfileMaximumAllowedRatingAgeError(
      @NonNull ProfileRejections.ChangeProfilePolicy rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.ReauthenticationRequired _ -> reauthenticationRequired();
      case ProfileRejections.EligibleManagerRequired _ -> profileRequiresEligibleManager();
      case ProfileRejections.RestrictedAccountAuthority _ ->
          restrictedAccountCannotAdminister(MAXIMUM_ALLOWED_RATING_AGE);
      case ProfileRejections.MaximumAllowedRatingAgeInvalid _ -> maximumAllowedRatingAgeInvalid();
    };
  }

  public static RemoveProfileMaximumAllowedRatingAgeError
      toRemoveProfileMaximumAllowedRatingAgeError(
          @NonNull ProfileRejections.ChangeProfilePolicy rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.ReauthenticationRequired _ -> reauthenticationRequired();
      case ProfileRejections.EligibleManagerRequired _ -> profileRequiresEligibleManager();
      case ProfileRejections.RestrictedAccountAuthority _ ->
          restrictedAccountCannotAdminister(PROFILE_ID);
      case ProfileRejections.MaximumAllowedRatingAgeInvalid _ ->
          maximumAllowedRatingAgeInvalid(PROFILE_ID);
    };
  }

  public static SetProfilePinError toSetProfilePinError(
      @NonNull ProfileRejections.SetProfilePin rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.PinMalformed _ -> pinMalformed();
    };
  }

  public static RemoveProfilePinError toRemoveProfilePinError(
      @NonNull ProfileRejections.RemoveProfilePin rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.WouldLockProfile(var householdId, var householdName) ->
          new ProfilePinRequiredError(
              householdName
                  .map(ProfileErrors::removalWouldLockMessage)
                  .orElse("A Household's safety policy requires this PIN."),
              householdId);
    };
  }

  public static AdministrativelyResetProfilePinError toAdministrativelyResetProfilePinError(
      @NonNull ProfileRejections.AdministrativelyResetProfilePin rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.PinMalformed _ -> pinMalformed();
      case ProfileRejections.ReasonRequired _ ->
          new ReasonRequiredError("Enter a reason for the audit record.", InputPath.of("reason"));
      case ProfileRejections.ReauthenticationRequired _ -> reauthenticationRequired();
    };
  }

  public static DeleteProfileError toDeleteProfileError(
      @NonNull ProfileRejections.DeleteProfile rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.ProfileNotDeletable _ ->
          new ProfileNotDeletableError(
              "Deletion needs an unlinked, unshared Profile with you as its only manager.");
      case ProfileRejections.ReauthenticationRequired _ -> reauthenticationRequired();
    };
  }

  private static ProfileNotFoundError profileNotFound() {
    return new ProfileNotFoundError("No such Profile.", InputPath.of(PROFILE_ID));
  }

  private static ProfileNameRequiredError nameRequired() {
    return new ProfileNameRequiredError("Enter a profile name.", InputPath.of(NAME));
  }

  private static ProfileNameTakenError nameTaken() {
    return new ProfileNameTakenError(
        "Another Profile in that Household already uses that name.", InputPath.of(NAME));
  }

  private static ProfileRequiresEligibleManagerError profileRequiresEligibleManager() {
    return new ProfileRequiresEligibleManagerError(
        "The Profile needs an eligible Profile manager in its Household.");
  }

  private static RestrictedAccountCannotAdministerError restrictedAccountCannotAdminister(
      String inputPath) {
    return new RestrictedAccountCannotAdministerError(
        "An Account with a restricted Personal Profile cannot be a ServerAdmin, HouseholdAdmin, or Profile manager.",
        InputPath.of(inputPath));
  }

  private static MaximumAllowedRatingAgeInvalidError maximumAllowedRatingAgeInvalid() {
    return maximumAllowedRatingAgeInvalid(MAXIMUM_ALLOWED_RATING_AGE);
  }

  private static MaximumAllowedRatingAgeInvalidError maximumAllowedRatingAgeInvalid(
      String inputPath) {
    return new MaximumAllowedRatingAgeInvalidError(
        "Maximum allowed rating age cannot be negative.", InputPath.of(inputPath));
  }

  private static String removalWouldLockMessage(String householdName) {
    return "Removing this PIN would lock the Profile in \"%s\".".formatted(householdName);
  }

  private static InvalidProfilePinError pinMalformed() {
    return new InvalidProfilePinError("Enter a 4-8 digit PIN.", InputPath.of(PIN));
  }

  private static ReauthenticationRequiredError reauthenticationRequired() {
    return new ReauthenticationRequiredError("Confirm your password before retrying this action.");
  }
}
