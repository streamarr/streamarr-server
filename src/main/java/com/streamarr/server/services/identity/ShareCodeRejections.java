package com.streamarr.server.services.identity;

/** The typed rejections of the share code operations (ADR 0024 §Share codes; ADR 0026). */
public final class ShareCodeRejections {

  private ShareCodeRejections() {}

  public sealed interface Issue permits HouseholdNotFound, ShareCodeLimitReached {}

  public sealed interface Withdraw permits ShareCodeNotFound, ShareCodeNotLive {}

  public sealed interface LookUp permits ShareCodeRefused {}

  /** No such Household, or the caller may not see its share codes. */
  public record HouseholdNotFound() implements Issue {}

  /**
   * The Household already holds the server's limit of live codes. A code that is spent, withdrawn,
   * invalidated or expires makes room for another.
   */
  public record ShareCodeLimitReached() implements Issue {}

  /** No such share code, or the caller may not see the share codes of its Household. */
  public record ShareCodeNotFound() implements Withdraw {}

  /** The code was already spent, withdrawn or invalidated, or it expired. */
  public record ShareCodeNotLive() implements Withdraw {}

  /**
   * The presented code is wrong, spent, withdrawn, expired or invalidated: one refusal for each,
   * naming no Household, whether the code was looked up or offered with.
   */
  public record ShareCodeRefused() implements LookUp, ShareRejections.Offer {}
}
