package com.streamarr.server.services.identity;

/** The typed rejections of the share code operations (ADR 0024 §Share codes; ADR 0026). */
public final class ShareCodeRejections {

  private ShareCodeRejections() {}

  public sealed interface Issue permits HouseholdNotFound {}

  public sealed interface Withdraw permits ShareCodeNotFound, ShareCodeNotLive {}

  /** No such Household, or the caller may not see its share codes. */
  public record HouseholdNotFound() implements Issue {}

  /** No such share code, or the caller may not see the share codes of its Household. */
  public record ShareCodeNotFound() implements Withdraw {}

  /** The code was already spent, withdrawn or invalidated, or it expired. */
  public record ShareCodeNotLive() implements Withdraw {}
}
