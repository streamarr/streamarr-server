package com.streamarr.server.services.identity;

/** The typed rejections of the share code operations (ADR 0024 §Share codes; ADR 0026). */
public final class ShareCodeRejections {

  private ShareCodeRejections() {}

  public sealed interface Issue permits HouseholdNotFound {}

  /** No such Household, or the caller may not see its share codes. */
  public record HouseholdNotFound() implements Issue {}
}
