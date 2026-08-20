package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.identity.ProfileRejections;

/** The exhaustive mappings from service rejection to schema error type, one per union. */
public final class ProfileErrors {

  private static final String PROFILE_ID = "profileId";
  private static final String NAME = "name";
  private static final String PIN = "pin";

  private ProfileErrors() {}

  public static CreateProfileError toCreateProfileError(ProfileRejections.CreateProfile rejection) {
    return switch (rejection) {
      case ProfileRejections.HouseholdNotFound _ ->
          new HouseholdNotFoundError("No such Household.", InputPath.of("householdId"));
      case ProfileRejections.ProfileNameRequired _ -> nameRequired();
      case ProfileRejections.ProfileNameTaken _ -> nameTaken();
      case ProfileRejections.HomeAnchorRequired _ -> homeAnchorRequired();
      case ProfileRejections.ManagerNotEligible _ ->
          new ManagerNotEligibleError(
              "That Account's Personal Profile is restricted; it cannot manage.",
              InputPath.of("localManagerAccountId"));
      case ProfileRejections.LocalManagerNotFound _ ->
          new LocalManagerNotFoundError("No such Account.", InputPath.of("localManagerAccountId"));
    };
  }

  public static RenameProfileError toRenameProfileError(ProfileRejections.RenameProfile rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.ProfileNameRequired _ -> nameRequired();
      case ProfileRejections.ProfileNameTaken _ -> nameTaken();
    };
  }

  public static SetProfilePictureError toSetProfilePictureError(
      ProfileRejections.SetProfilePicture rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
    };
  }

  public static ChangeProfileKindError toChangeProfileKindError(
      ProfileRejections.ChangeProfilePolicy rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.ReauthenticationRequired _ -> reauthenticationRequired();
      case ProfileRejections.HomeAnchorRequired _ -> homeAnchorRequired();
    };
  }

  public static SetProfileContentCeilingError toSetProfileContentCeilingError(
      ProfileRejections.ChangeProfilePolicy rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.ReauthenticationRequired _ -> reauthenticationRequired();
      case ProfileRejections.HomeAnchorRequired _ -> homeAnchorRequired();
    };
  }

  public static ClearProfileContentCeilingError toClearProfileContentCeilingError(
      ProfileRejections.ChangeProfilePolicy rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.ReauthenticationRequired _ -> reauthenticationRequired();
      case ProfileRejections.HomeAnchorRequired _ -> homeAnchorRequired();
    };
  }

  public static SetProfilePinError toSetProfilePinError(ProfileRejections.SetProfilePin rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.PinMalformed _ -> pinMalformed();
    };
  }

  public static ClearProfilePinError toClearProfilePinError(
      ProfileRejections.ClearProfilePin rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.WouldLockProfile wouldLock ->
          new WouldLockProfileError(
              wouldLock
                  .householdName()
                  .map(
                      name -> "Clearing this PIN would lock the Profile in \"%s\".".formatted(name))
                  .orElse("A Household's safety policy requires this PIN."),
              wouldLock.householdId());
    };
  }

  public static OverrideProfilePinError toOverrideProfilePinError(
      ProfileRejections.OverrideProfilePin rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ -> profileNotFound();
      case ProfileRejections.PinMalformed _ -> pinMalformed();
      case ProfileRejections.ReasonRequired _ ->
          new ReasonRequiredError("Enter a reason for the audit record.", InputPath.of("reason"));
      case ProfileRejections.ReauthenticationRequired _ -> reauthenticationRequired();
    };
  }

  public static DeleteProfileError toDeleteProfileError(ProfileRejections.DeleteProfile rejection) {
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
        "Another available Profile already uses that name.", InputPath.of(NAME));
  }

  private static HomeAnchorRequiredError homeAnchorRequired() {
    return new HomeAnchorRequiredError(
        "The Profile needs an eligible manager in its Household — a HouseholdAdmin for a"
            + " restricted Profile.");
  }

  private static PinMalformedError pinMalformed() {
    return new PinMalformedError("Enter a 4-8 digit PIN.", InputPath.of(PIN));
  }

  private static ReauthenticationRequiredError reauthenticationRequired() {
    return new ReauthenticationRequiredError("Confirm your password to continue.");
  }
}
