package com.streamarr.server.support;

import com.streamarr.server.services.mutation.Outcome;

public final class OutcomeTestSupport {

  private OutcomeTestSupport() {}

  public static <T, R> T accepted(Outcome<T, R> outcome) {
    return switch (outcome) {
      case Outcome.Accepted<T, R>(var result) -> result;
      case Outcome.Rejected<T, R>(var rejections) ->
          throw new AssertionError("expected acceptance but got " + rejections);
    };
  }

  public static <R> R rejectionOf(Outcome<?, R> outcome) {
    return switch (outcome) {
      case Outcome.Rejected<?, R>(var rejections) -> rejections.getFirst();
      case Outcome.Accepted<?, R> accepted ->
          throw new AssertionError("expected a rejection but got " + accepted);
    };
  }
}
