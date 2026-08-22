package com.streamarr.server.services.identity;

/**
 * Every expected reason an administration mutation refuses (ADR 0026): one sealed union per
 * mutation, with the members shared where mutations refuse for the same reason. Hidden resources
 * refuse as {@link AccountNotFound}/{@link HouseholdNotFound} under the oracle rule — a caller who
 * may not view the resource learns nothing from the denial.
 */
public final class AdministrationRejections {

  private AdministrationRejections() {}

  public sealed interface GrantServerAdmin
      permits AccountNotFound, ReauthenticationRequired, ReasonRequired, RestrictedAccount {}

  public sealed interface RevokeServerAdmin
      permits AccountNotFound, ReauthenticationRequired, ReasonRequired, LastServerAdmin {}

  public sealed interface GrantHouseholdAdmin permits AccountNotFound, RestrictedAccount {}

  public sealed interface RevokeHouseholdAdmin permits AccountNotFound, LastHouseholdAdmin {}

  public sealed interface DisableAccount permits AccountNotFound, LastServerAdmin {}

  public sealed interface EnableAccount permits AccountNotFound {}

  public sealed interface RenameAccount permits AccountNotFound, DisplayNameRequired {}

  public sealed interface CreateHousehold permits HouseholdNameRequired {}

  public sealed interface RenameHousehold permits HouseholdNotFound, HouseholdNameRequired {}

  public record AccountNotFound()
      implements GrantServerAdmin,
          RevokeServerAdmin,
          GrantHouseholdAdmin,
          RevokeHouseholdAdmin,
          DisableAccount,
          EnableAccount,
          RenameAccount {}

  /** The action is allowed except for a current reauthentication ceremony (ADR 0024). */
  public record ReauthenticationRequired() implements GrantServerAdmin, RevokeServerAdmin {}

  public record ReasonRequired() implements GrantServerAdmin, RevokeServerAdmin {}

  /** T5: an Account whose Personal Profile is restricted holds no authority. */
  public record RestrictedAccount() implements GrantServerAdmin, GrantHouseholdAdmin {}

  /** T4: at least one enabled ServerAdmin remains after bootstrap. */
  public record LastServerAdmin() implements RevokeServerAdmin, DisableAccount {}

  /** T1: a Household keeps at least one HouseholdAdmin. */
  public record LastHouseholdAdmin() implements RevokeHouseholdAdmin {}

  public record DisplayNameRequired() implements RenameAccount {}

  public record HouseholdNameRequired() implements CreateHousehold, RenameHousehold {}

  public record HouseholdNotFound() implements RenameHousehold {}
}
