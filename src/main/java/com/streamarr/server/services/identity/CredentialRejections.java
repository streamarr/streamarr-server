package com.streamarr.server.services.identity;

/**
 * Expected refusals of the credential mutations: invitation issue and cancel, reset issue (ADR
 * 0026).
 */
public final class CredentialRejections {

  private CredentialRejections() {}

  public sealed interface Issue
      permits EmailRequired,
          EmailInvalid,
          EmailAlreadyUsed,
          ProfileNameRequired,
          ProfileNameTaken,
          HouseholdNotFound,
          RestrictedFirstAccount,
          RestrictedHouseholdAdmin,
          LocalManagerRequired,
          ProfileManagerNotEligible,
          MaximumAllowedRatingAgeInvalid,
          ConnectProfileRequired,
          ConnectProfileNotFound,
          ProfileAlreadyLinked,
          ProfileNotInHousehold,
          ReofferHouseholdNotFound,
          ReofferHouseholdNotShared {}

  public sealed interface Cancel permits InvitationNotPending {}

  public sealed interface IssueReset
      permits AccountNotFound, ReasonRequired, ReauthenticationRequired {}

  public record EmailRequired() implements Issue {}

  /** Not the shape of an address; it would become the Account's login identity. */
  public record EmailInvalid() implements Issue {}

  /** An existing email cannot be invited or reassigned; transfer the Account instead. */
  public record EmailAlreadyUsed() implements Issue {}

  public record ProfileNameRequired() implements Issue {}

  public record ProfileNameTaken() implements Issue {}

  public record HouseholdNotFound() implements Issue {}

  /** The first Account becomes HouseholdAdmin, and a restricted Account holds no authority. */
  public record RestrictedFirstAccount() implements Issue {}

  /** A restricted Account holds no authority, so it cannot be invited as HouseholdAdmin. */
  public record RestrictedHouseholdAdmin() implements Issue {}

  /** A restricted Profile needs an eligible local HouseholdAdmin manager named up front. */
  public record LocalManagerRequired() implements Issue {}

  /** The named Account is missing, outside the Household, restricted, or not the role required. */
  public record ProfileManagerNotEligible() implements Issue {}

  public record MaximumAllowedRatingAgeInvalid() implements Issue {}

  /** A CONNECT invitation names the existing Profile it links. */
  public record ConnectProfileRequired() implements Issue {}

  public record ConnectProfileNotFound() implements Issue {}

  /** A linked Profile already belongs to a person; it cannot be connected again. */
  public record ProfileAlreadyLinked() implements Issue {}

  /** CONNECT joins the recipient to the Profile's own Household. */
  public record ProfileNotInHousehold() implements Issue {}

  public record ReofferHouseholdNotFound() implements Issue {}

  /** Only a Household the Profile actively visits today can be offered it afresh. */
  public record ReofferHouseholdNotShared() implements Issue {}

  public record InvitationNotPending() implements Cancel {}

  public record AccountNotFound() implements IssueReset {}

  public record ReasonRequired() implements IssueReset {}

  public record ReauthenticationRequired() implements IssueReset {}
}
