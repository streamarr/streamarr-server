package com.streamarr.server.services.identity;

/** Expected refusals of the credential-issuance mutations (ADR 0026). */
public final class InvitationRejections {

  private InvitationRejections() {}

  public sealed interface Issue
      permits EmailRequired,
          EmailAlreadyUsed,
          ProfileNameRequired,
          ProfileNameTaken,
          HouseholdNotFound,
          RestrictedFirstAccount,
          LocalManagerRequired,
          LocalManagerNotFound,
          MaximumAllowedRatingAgeInvalid {}

  public sealed interface Cancel permits InvitationNotPending {}

  public sealed interface IssueReset
      permits AccountNotFound, ReasonRequired, ReauthenticationRequired {}

  public record EmailRequired() implements Issue {}

  /** An existing email cannot be invited or reassigned; transfer the Account instead. */
  public record EmailAlreadyUsed() implements Issue {}

  public record ProfileNameRequired() implements Issue {}

  public record ProfileNameTaken() implements Issue {}

  public record HouseholdNotFound() implements Issue {}

  /** The first Account becomes HouseholdAdmin, and a restricted Account holds no authority. */
  public record RestrictedFirstAccount() implements Issue {}

  /** A restricted Profile needs an eligible local HouseholdAdmin manager named up front. */
  public record LocalManagerRequired() implements Issue {}

  public record LocalManagerNotFound() implements Issue {}

  public record MaximumAllowedRatingAgeInvalid() implements Issue {}

  public record InvitationNotPending() implements Cancel {}

  public record AccountNotFound() implements IssueReset {}

  public record ReasonRequired() implements IssueReset {}

  public record ReauthenticationRequired() implements IssueReset {}
}
