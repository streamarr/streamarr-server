package com.streamarr.server.services.identity;

/** Expected refusals of the Device administration mutations (ADR 0026 shapes). */
public final class DeviceRejections {

  private DeviceRejections() {}

  public sealed interface Revoke permits RegistrationNotFound, RegistrationNotActive {}

  public sealed interface Block
      permits HouseholdNotFound, EsnRequired, EsnInvalid, ReasonRequired, AlreadyBlocked {}

  public sealed interface BlockServerWide
      permits EsnRequired, EsnInvalid, ReasonRequired, AlreadyBlocked, ReauthenticationRequired {}

  public sealed interface Unblock
      permits HouseholdNotFound, EsnRequired, EsnInvalid, BlockNotFound {}

  public sealed interface UnblockServerWide permits EsnRequired, EsnInvalid, BlockNotFound {}

  public record RegistrationNotFound() implements Revoke {}

  public record RegistrationNotActive() implements Revoke {}

  public record HouseholdNotFound() implements Block, Unblock {}

  public record EsnRequired() implements Block, BlockServerWide, Unblock, UnblockServerWide {}

  public record EsnInvalid() implements Block, BlockServerWide, Unblock, UnblockServerWide {}

  public record ReasonRequired() implements Block, BlockServerWide {}

  public record AlreadyBlocked() implements Block, BlockServerWide {}

  public record BlockNotFound() implements Unblock, UnblockServerWide {}

  public record ReauthenticationRequired() implements BlockServerWide {}
}
