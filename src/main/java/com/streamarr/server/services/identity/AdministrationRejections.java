package com.streamarr.server.services.identity;

/** Typed, protocol-independent rejection reasons for administration mutations. */
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

  public record ReauthenticationRequired() implements GrantServerAdmin, RevokeServerAdmin {}

  public record ReasonRequired() implements GrantServerAdmin, RevokeServerAdmin {}

  public record RestrictedAccount() implements GrantServerAdmin, GrantHouseholdAdmin {}

  public record LastServerAdmin() implements RevokeServerAdmin, DisableAccount {}

  public record LastHouseholdAdmin() implements RevokeHouseholdAdmin {}

  public record DisplayNameRequired() implements RenameAccount {}

  public record HouseholdNameRequired() implements CreateHousehold, RenameHousehold {}

  public record HouseholdNotFound() implements RenameHousehold {}
}
