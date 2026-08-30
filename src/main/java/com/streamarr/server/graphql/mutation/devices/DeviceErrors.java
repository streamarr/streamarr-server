package com.streamarr.server.graphql.mutation.devices;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.identity.DeviceRejections;

/** The exhaustive mappings from service rejection to schema error type, one per union. */
public final class DeviceErrors {

  private static final String ESN = "esn";

  private DeviceErrors() {}

  public static RevokeDeviceRegistrationError toRevokeError(DeviceRejections.Revoke rejection) {
    return switch (rejection) {
      case DeviceRejections.RegistrationNotFound _ ->
          new RegistrationNotFoundError("No such registration.", InputPath.of("registrationId"));
      case DeviceRejections.RegistrationNotActive _ ->
          new RegistrationNotActiveError(
              "That registration is not active.", InputPath.of("registrationId"));
    };
  }

  public static BlockEsnError toBlockError(DeviceRejections.Block rejection) {
    return switch (rejection) {
      case DeviceRejections.HouseholdNotFound _ -> householdNotFound();
      case DeviceRejections.EsnRequired _ -> esnRequired();
      case DeviceRejections.EsnInvalid _ -> esnInvalid();
      case DeviceRejections.ReasonRequired _ -> reasonRequired();
      case DeviceRejections.AlreadyBlocked _ -> alreadyBlocked();
    };
  }

  public static BlockEsnServerWideError toBlockServerWideError(
      DeviceRejections.BlockServerWide rejection) {
    return switch (rejection) {
      case DeviceRejections.EsnRequired _ -> esnRequired();
      case DeviceRejections.EsnInvalid _ -> esnInvalid();
      case DeviceRejections.ReasonRequired _ -> reasonRequired();
      case DeviceRejections.AlreadyBlocked _ -> alreadyBlocked();
      case DeviceRejections.ReauthenticationRequired _ ->
          new ReauthenticationRequiredError("Confirm your password before retrying this action.");
    };
  }

  public static UnblockEsnError toUnblockError(DeviceRejections.Unblock rejection) {
    return switch (rejection) {
      case DeviceRejections.HouseholdNotFound _ -> householdNotFound();
      case DeviceRejections.EsnRequired _ -> esnRequired();
      case DeviceRejections.EsnInvalid _ -> esnInvalid();
      case DeviceRejections.BlockNotFound _ -> blockNotFound();
    };
  }

  public static UnblockEsnServerWideError toUnblockServerWideError(
      DeviceRejections.UnblockServerWide rejection) {
    return switch (rejection) {
      case DeviceRejections.EsnRequired _ -> esnRequired();
      case DeviceRejections.EsnInvalid _ -> esnInvalid();
      case DeviceRejections.BlockNotFound _ -> blockNotFound();
    };
  }

  private static HouseholdNotFoundError householdNotFound() {
    return new HouseholdNotFoundError("No such Household.", InputPath.of("householdId"));
  }

  private static EsnRequiredError esnRequired() {
    return new EsnRequiredError("Enter the device's ESN.", InputPath.of(ESN));
  }

  private static EsnInvalidError esnInvalid() {
    return new EsnInvalidError("The ESN is too long.", InputPath.of(ESN));
  }

  private static ReasonRequiredError reasonRequired() {
    return new ReasonRequiredError("Enter a reason for the audit record.", InputPath.of("reason"));
  }

  private static EsnAlreadyBlockedError alreadyBlocked() {
    return new EsnAlreadyBlockedError("That ESN is already blocked there.", InputPath.of(ESN));
  }

  private static EsnBlockNotFoundError blockNotFound() {
    return new EsnBlockNotFoundError("No such block.", InputPath.of(ESN));
  }
}
