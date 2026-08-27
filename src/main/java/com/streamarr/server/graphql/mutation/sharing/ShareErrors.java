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
              "That Profile already has a pending or active share with that Household.",
              InputPath.of("householdId"));
    };
  }

  public static AcceptProfileShareError toAcceptError(ShareRejections.Accept rejection) {
    return switch (rejection) {
      case ShareRejections.ShareNotFound _ -> shareNotFound();
      case ShareRejections.ShareNotPending _ -> shareNotPending();
      case ShareRejections.NoEligibleAdmin _ ->
          new RestrictedProfileRequiresHouseholdAdminError(
              "A Household hosting a restricted Profile needs an eligible HouseholdAdmin.");
      case ShareRejections.NameConflict _ ->
          new ShareNameConflictError("Another Profile in that Household already uses that name.");
    };
  }

  public static RejectProfileShareError toRejectError(ShareRejections.Decline rejection) {
    return switch (rejection) {
      case ShareRejections.ShareNotFound _ -> shareNotFound();
      case ShareRejections.ShareNotPending _ -> shareNotPending();
    };
  }

  public static CancelProfileShareError toCancelError(ShareRejections.Decline rejection) {
    return switch (rejection) {
      case ShareRejections.ShareNotFound _ -> shareNotFound();
      case ShareRejections.ShareNotPending _ -> shareNotPending();
    };
  }

  public static EndProfileShareError toEndError(ShareRejections.End rejection) {
    return switch (rejection) {
      case ShareRejections.ShareNotFound _ -> shareNotFound();
      case ShareRejections.ShareNotActive _ -> shareNotActive();
      case ShareRejections.StructuralShareCannotEnd _ -> structuralShare();
    };
  }

  public static ForceEndProfileShareError toForceEndError(ShareRejections.ForceEnd rejection) {
    return switch (rejection) {
      case ShareRejections.ShareNotFound _ -> shareNotFound();
      case ShareRejections.ShareNotActive _ -> shareNotActive();
      case ShareRejections.StructuralShareCannotEnd _ -> structuralShare();
      case ShareRejections.ReasonRequired _ ->
          new ReasonRequiredError("Enter a reason for the audit record.", InputPath.of("reason"));
      case ShareRejections.ReauthenticationRequired _ ->
          new ReauthenticationRequiredError("Confirm your password before retrying this action.");
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

  private static MembershipShareCannotEndError structuralShare() {
    return new MembershipShareCannotEndError(
        "This share is required by Account membership and cannot end while the Account remains in"
            + " the Household.");
  }
}
