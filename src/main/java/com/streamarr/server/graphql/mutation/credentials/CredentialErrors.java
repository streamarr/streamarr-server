package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.identity.CredentialRejections;
import java.util.List;

/** The exhaustive mappings from service rejection to schema error type, one per union. */
public final class CredentialErrors {

  private static final List<String> PROFILE_ID_PATH = InputPath.of("profileId");
  private static final List<String> RECIPIENT_EMAIL_PATH = InputPath.of("recipientEmail");

  private CredentialErrors() {}

  public static IssueAccountInvitationError toIssueError(CredentialRejections.Issue rejection) {
    return switch (rejection) {
      case CredentialRejections.EmailRequired _ ->
          new EmailRequiredError("Enter the recipient's email.", RECIPIENT_EMAIL_PATH);
      case CredentialRejections.EmailInvalid _ ->
          new EmailInvalidError("Enter a valid email address.", RECIPIENT_EMAIL_PATH);
      case CredentialRejections.EmailAlreadyUsed _ ->
          new EmailAlreadyUsedError(
              "An Account already uses that email; transfer it instead.", RECIPIENT_EMAIL_PATH);
      case CredentialRejections.ProfileNameRequired _ ->
          new ProfileNameRequiredError("Enter a profile name.", InputPath.of("profileName"));
      case CredentialRejections.ProfileNameTaken _ ->
          new ProfileNameTakenError(
              "A Profile with that name is already available in the Household.",
              InputPath.of("profileName"));
      case CredentialRejections.HouseholdNotFound _ ->
          new HouseholdNotFoundError("No such Household.", InputPath.of("householdId"));
      case CredentialRejections.RestrictedFirstAccount _ ->
          new RestrictedFirstAccountError(
              "The first Account of an empty Household becomes HouseholdAdmin; it cannot be"
                  + " restricted.");
      case CredentialRejections.RestrictedHouseholdAdmin _ ->
          new RestrictedHouseholdAdminError(
              "A restricted Profile cannot be invited as HouseholdAdmin.",
              InputPath.of("householdRole"));
      case CredentialRejections.LocalManagerRequired _ ->
          new EligibleProfileManagerRequiredError(
              "A restricted Profile needs a HouseholdAdmin of its Household, with an unrestricted"
                  + " Personal Profile, as its manager.",
              InputPath.of("profileManagerAccountId"));
      case CredentialRejections.ProfileManagerNotEligible _ ->
          new ProfileManagerNotEligibleError(
              "No eligible Profile manager with that Account id in the Household.",
              InputPath.of("profileManagerAccountId"));
      case CredentialRejections.MaximumAllowedRatingAgeInvalid _ ->
          new MaximumAllowedRatingAgeInvalidError(
              "Enter a non-negative maximum allowed rating age.",
              InputPath.of("maximumAllowedRatingAge"));
      case CredentialRejections.LinkProfileRequired _ ->
          new LinkProfileRequiredError("Name the Profile this invitation links.", PROFILE_ID_PATH);
      case CredentialRejections.LinkProfileNotFound _ ->
          new LinkProfileNotFoundError("No such Profile.", PROFILE_ID_PATH);
      case CredentialRejections.ProfileAlreadyLinked _ ->
          new ProfileAlreadyLinkedError(
              "That Profile already belongs to an Account.", PROFILE_ID_PATH);
      case CredentialRejections.ProfileNotInHousehold _ ->
          new ProfileNotInHouseholdError(
              "LINK joins the recipient to the Profile's own Household.",
              InputPath.of("householdId"));
      case CredentialRejections.ReofferHouseholdNotFound _ ->
          new ReofferHouseholdNotFoundError(
              "No such Household.", InputPath.of("reofferHouseholdIds"));
      case CredentialRejections.ReofferHouseholdNotShared _ ->
          new ReofferHouseholdNotSharedError(
              "Choose a Household where the Profile previously had an active share.",
              InputPath.of("reofferHouseholdIds"));
    };
  }

  public static CancelAccountInvitationError toCancelError(CredentialRejections.Cancel rejection) {
    return switch (rejection) {
      case CredentialRejections.InvitationNotPending _ ->
          new InvitationNotPendingError(
              "That invitation is unavailable.", InputPath.of("invitationId"));
    };
  }

  public static IssuePasswordResetError toIssueResetError(
      CredentialRejections.IssueReset rejection) {
    return switch (rejection) {
      case CredentialRejections.AccountNotFound _ ->
          new AccountNotFoundError("No such Account.", InputPath.of("accountId"));
      case CredentialRejections.ReasonRequired _ ->
          new ReasonRequiredError("Enter a reason for the audit record.", InputPath.of("reason"));
      case CredentialRejections.ReauthenticationRequired _ ->
          new ReauthenticationRequiredError("Confirm your password before retrying this action.");
    };
  }
}
