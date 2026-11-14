package com.streamarr.server.services.identity;

import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.NonNull;
import org.springframework.security.access.AccessDeniedException;

final class AuthorizationRefusal {

  private AuthorizationRefusal() {}

  static <R> Optional<R> from(
      @NonNull Decision<AuthorizationUnit> decision, @NonNull Response<R> response) {
    return switch (decision) {
      case Decision.Allowed<AuthorizationUnit> _ -> Optional.empty();
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
      case Decision.Denied<AuthorizationUnit>(var reason) -> Optional.of(of(reason, response));
    };
  }

  /** The refusal a denial answers with; FORBIDDEN has no typed refusal, so it is thrown. */
  static <R> R of(@NonNull Decision.DenialReason reason, @NonNull Response<R> response) {
    return switch (reason) {
      case REAUTHENTICATION_REQUIRED ->
          response
              .reauthenticationRequired()
              .orElseThrow(AuthorizationUnavailableException::new)
              .get();
      case POLICY -> {
        if (!response.mayView().getAsBoolean()) {
          yield response.hidden().get();
        }

        yield response.visible().get().orElseThrow(() -> new AccessDeniedException("Not allowed."));
      }
    };
  }

  /**
   * How an operation answers a denial (ADR 0026 resource visibility rules): as {@code hidden} to a
   * caller that may not view the resource; to one that may, with the refusal {@code visible}
   * explains the denial by, or FORBIDDEN when it explains nothing; and with {@code
   * reauthenticationRequired} when only a recent password is missing.
   */
  record Response<R>(
      @NonNull BooleanSupplier mayView,
      @NonNull Supplier<? extends R> hidden,
      @NonNull Supplier<? extends Optional<? extends R>> visible,
      @NonNull Optional<? extends Supplier<? extends R>> reauthenticationRequired) {

    static <R> Response<R> hiddenUnless(
        @NonNull BooleanSupplier mayView, @NonNull Supplier<? extends R> hidden) {
      return new Response<>(mayView, hidden, Optional::empty, Optional.empty());
    }

    Response<R> visibleAs(@NonNull Supplier<? extends Optional<? extends R>> explained) {
      return new Response<>(mayView, hidden, explained, reauthenticationRequired);
    }

    Response<R> reauthenticatedAs(@NonNull Supplier<? extends R> stepUp) {
      return new Response<>(mayView, hidden, visible, Optional.of(stepUp));
    }
  }
}
