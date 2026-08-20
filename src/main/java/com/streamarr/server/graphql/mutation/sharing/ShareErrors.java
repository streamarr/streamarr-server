package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.identity.ShareRejections;

/** The exhaustive mappings from service rejection to schema error type, one per union. */
public final class ShareErrors {

  private static final String SHARE_ID = "shareId";

  private ShareErrors() {}

  public static OfferProfileShareError toOfferError(ShareRejections.Offer rejection) {
    return switch (rejection) {
      case ShareRejections.ProfileNotFound _ ->
          new ProfileNotFoundError("No such Profile.", InputPath.of("profileId"));
      case ShareRejections.HouseholdNotFound _ ->
          new HouseholdNotFoundError("No such Household.", InputPath.of("householdId"));
      case ShareRejections.AlreadyShared _ ->
          new ProfileAlreadySharedError(
              "That Profile already has a live share into that Household.",
              InputPath.of("householdId"));
    };
  }

  public static AcceptProfileShareError toAcceptError(ShareRejections.Decide rejection) {
    return switch (rejection) {
      case ShareRejections.ShareNotFound _ -> shareNotFound();
      case ShareRejections.ShareNotPending _ -> shareNotPending();
      case ShareRejections.NoEligibleAdmin _ ->
          new NoEligibleAdminError(
              "A Household hosting a restricted Profile needs an eligible HouseholdAdmin.");
      case ShareRejections.NameConflict _ ->
          new ShareNameConflictError("Another available Profile there already uses that name.");
    };
  }

  public static RejectProfileShareError toRejectError(ShareRejections.Decide rejection) {
    return switch (rejection) {
      case ShareRejections.ShareNotFound _ -> shareNotFound();
      case ShareRejections.ShareNotPending _,
          ShareRejections.NoEligibleAdmin _,
          ShareRejections.NameConflict _ ->
          shareNotPending();
    };
  }

  public static CancelProfileShareError toCancelError(ShareRejections.Decide rejection) {
    return switch (rejection) {
      case ShareRejections.ShareNotFound _ -> shareNotFound();
      case ShareRejections.ShareNotPending _,
          ShareRejections.NoEligibleAdmin _,
          ShareRejections.NameConflict _ ->
          shareNotPending();
    };
  }

  public static EndProfileShareError toEndError(ShareRejections.End rejection) {
    return switch (rejection) {
      case ShareRejections.ShareNotFound _ -> shareNotFound();
      case ShareRejections.ShareNotActive _ -> shareNotActive();
      case ShareRejections.StructuralShareCannotEnd _ -> structuralShare();
      case ShareRejections.ReauthenticationRequired _, ShareRejections.ReasonRequired _ ->
          shareNotActive();
    };
  }

  public static ForceEndProfileShareError toForceEndError(ShareRejections.End rejection) {
    return switch (rejection) {
      case ShareRejections.ShareNotFound _ -> shareNotFound();
      case ShareRejections.ShareNotActive _ -> shareNotActive();
      case ShareRejections.StructuralShareCannotEnd _ -> structuralShare();
      case ShareRejections.ReasonRequired _ ->
          new ReasonRequiredError("Enter a reason for the audit record.", InputPath.of("reason"));
      case ShareRejections.ReauthenticationRequired _ ->
          new ReauthenticationRequiredError("Confirm your password to continue.");
    };
  }

  private static ShareNotFoundError shareNotFound() {
    return new ShareNotFoundError("No such share.", InputPath.of(SHARE_ID));
  }

  private static ShareNotPendingError shareNotPending() {
    return new ShareNotPendingError("That offer is not pending.", InputPath.of(SHARE_ID));
  }

  private static ShareNotActiveError shareNotActive() {
    return new ShareNotActiveError("That share is not active.", InputPath.of(SHARE_ID));
  }

  private static StructuralShareError structuralShare() {
    return new StructuralShareError(
        "A Personal Profile's share into its own Household cannot end while the Account remains a"
            + " member.");
  }
}
