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
      case Decision.Denied<AuthorizationUnit>(var reason) ->
          switch (reason) {
            case REAUTHENTICATION_REQUIRED ->
                Optional.of(
                    response
                        .reauthenticationRequired()
                        .orElseThrow(AuthorizationUnavailableException::new)
                        .get());
            case POLICY -> {
              if (response.mayView().getAsBoolean()) {
                throw new AccessDeniedException("Not allowed.");
              }

              yield Optional.of(response.denied().get());
            }
          };
    };
  }

  record Response<R>(
      @NonNull BooleanSupplier mayView,
      @NonNull Supplier<? extends R> denied,
      @NonNull Optional<? extends Supplier<? extends R>> reauthenticationRequired) {}
}
