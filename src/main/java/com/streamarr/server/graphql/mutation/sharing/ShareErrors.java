package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.identity.ShareCodeRejections;
import com.streamarr.server.services.identity.ShareRejections;

/** The exhaustive mappings from service rejection to schema error type, one per union. */
public final class ShareErrors {

  private static final String SHARE_ID = "shareId";
  private static final String SHARE_CODE_ID = "shareCodeId";
  private static final String PROFILE_ID = "profileId";

  private ShareErrors() {}

  public static OfferProfileShareError toOfferError(ShareRejections.Offer rejection) {
    return switch (rejection) {
      case ShareRejections.ProfileNotFound _ ->
          new ProfileNotFoundError("No such Profile.", InputPath.of(PROFILE_ID));
      case ShareCodeRejections.ShareCodeRefused _ -> shareCodeRefused("shareCode");
      case ShareRejections.AlreadyShared _ ->
          new ProfileAlreadySharedError(
              "That Profile is already available in that Household.", InputPath.of(PROFILE_ID));
    };
  }

  public static IssueShareCodeError toIssueShareCodeError(ShareCodeRejections.Issue rejection) {
    return switch (rejection) {
      case ShareCodeRejections.HouseholdNotFound _ ->
          new HouseholdNotFoundError("No such Household.", InputPath.of("householdId"));
    };
  }

  public static WithdrawShareCodeError toWithdrawShareCodeError(
      ShareCodeRejections.Withdraw rejection) {
    return switch (rejection) {
      case ShareCodeRejections.ShareCodeNotFound _ ->
          new ShareCodeNotFoundError("No such share code.", InputPath.of(SHARE_CODE_ID));
      case ShareCodeRejections.ShareCodeNotLive _ ->
          new ShareCodeNotLiveError(
              "This share code no longer works, so there is nothing to withdraw.",
              InputPath.of(SHARE_CODE_ID));
    };
  }

  public static LookUpShareCodeError toLookUpShareCodeError(ShareCodeRejections.LookUp rejection) {
    return switch (rejection) {
      case ShareCodeRejections.ShareCodeRefused _ -> shareCodeRefused("code");
    };
  }

  /** One plain refusal for every code that does not work, wherever it was presented. */
  private static ShareCodeRefusedError shareCodeRefused(String input) {
    return new ShareCodeRefusedError(
        "This share code does not work. Ask the Household for a new one.", InputPath.of(input));
  }

  public static AcceptProfileShareError toAcceptError(ShareRejections.Accept rejection) {
    return switch (rejection) {
      case ShareRejections.ShareNotFound _ -> shareNotFound();
      case ShareRejections.ShareNotPending _ -> shareNotPending("accepted");
      // The stored reason is written for the Profile's managers, who read it on the Sharing page.
      case ShareRejections.OfferInvalidated _ ->
          new OfferInvalidatedError(
              "This share invitation was withdrawn and can't be accepted. Reload the page to see if"
                  + " other share invitations are available.",
              InputPath.of(SHARE_ID));
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
      case ShareRejections.ShareNotPending _ -> shareNotPending("declined");
    };
  }

  public static CancelProfileShareError toCancelError(ShareRejections.Decline rejection) {
    return switch (rejection) {
      case ShareRejections.ShareNotFound _ -> shareNotFound();
      case ShareRejections.ShareNotPending _ -> shareNotPending("canceled");
    };
  }

  public static EndProfileShareError toEndError(ShareRejections.End rejection) {
    return switch (rejection) {
      case ShareRejections.ShareNotFound _ -> shareNotFound();
      case ShareRejections.ShareNotActive _ -> shareNotActive();
      case ShareRejections.StructuralShareCannotEnd _ -> structuralShare();
    };
  }

  public static AdministrativelyEndProfileShareError toAdministrativelyEndError(
      ShareRejections.AdministrativelyEnd rejection) {
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

  private static ShareNotPendingError shareNotPending(String refusedAction) {
    return new ShareNotPendingError(
        "This share invitation can't be %s anymore. Reload the page to see what changed."
            .formatted(refusedAction),
        InputPath.of(SHARE_ID));
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
