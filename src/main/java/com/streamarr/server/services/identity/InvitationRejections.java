package com.streamarr.server.services.identity;

/** Expected refusals of the credential-issuance mutations (ADR 0026). */
public final class InvitationRejections {

  private InvitationRejections() {}

  public sealed interface Issue
      permits EmailRequired,
          EmailAlreadyUsed,
          ProfileNameRequired,
          HouseholdNotFound,
          RestrictedFirstAccount,
          LocalManagerRequired,
          LocalManagerNotFound,
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

  /** An existing email cannot be invited or reassigned; transfer the Account instead. */
  public record EmailAlreadyUsed() implements Issue {}

  public record ProfileNameRequired() implements Issue {}

  public record HouseholdNotFound() implements Issue {}

  /** The first Account becomes HouseholdAdmin, and a restricted Account holds no authority. */
  public record RestrictedFirstAccount() implements Issue {}

  /** A restricted Profile needs an eligible local HouseholdAdmin manager named up front. */
  public record LocalManagerRequired() implements Issue {}

  public record LocalManagerNotFound() implements Issue {}

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
