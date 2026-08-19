package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.AuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.EntityValidationRequest;
import com.cedarpolicy.model.entity.Entities;
import com.cedarpolicy.model.exception.BadRequestException;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationDecider;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Decision.FailureCause;
import com.streamarr.server.services.authorization.Intent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Cedar behind the facade (ADR 0025). Every request and slice is validated against the schema
 * before evaluation, any evaluation diagnostic fails closed even when Cedar also reports allow, and
 * every exception fails closed with an ERROR log and a {@code streamarr.authorization.fail_closed}
 * count by cause. Diagnostics stay here; callers see only the {@link Decision}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class CedarAuthorizationDecider implements AuthorizationDecider {

  static final String FAIL_CLOSED_METRIC = "streamarr.authorization.fail_closed";

  private final AuthorizationEngine engine;
  private final CedarPolicyBundle bundle;
  private final SliceAssembler sliceAssembler;
  private final MeterRegistry meterRegistry;

  @Override
  public <T> Decision<T> decide(AuthenticatedIdentity identity, Intent<T> intent) {
    var plan = IntentPlanner.plan(intent);
    var check = plan.check();
    try {
      var slice = sliceAssembler.assemble(identity, check);
      var entities = slice.entities();
      try {
        engine.validateEntities(new EntityValidationRequest(bundle.schema(), entities));
      } catch (BadRequestException e) {
        return failClosed(FailureCause.INVALID_SLICE, check, e.getErrors().toString());
      }
      var request =
          new AuthorizationRequest(
              slice.principal(),
              check.action().uid(),
              check.resource(),
              check.context(),
              Optional.of(bundle.schema()),
              true);
      var response =
          engine.isAuthorized(request, bundle.policies(), new Entities(new HashSet<>(entities)));
      return interpret(response, check, plan.value());
    } catch (Exception e) {
      log.error("Authorization failed closed for {} (ENGINE_FAILURE)", check.action(), e);
      return countFailClosed(FailureCause.ENGINE_FAILURE);
    }
  }

  private <T> Decision<T> interpret(
      AuthorizationResponse response, AuthorizationCheck check, T value) {
    if (response.success.isEmpty()) {
      return failClosed(FailureCause.INVALID_REQUEST, check, String.valueOf(response.errors));
    }
    var success = response.success.get();
    if (!success.getErrors().isEmpty()) {
      return failClosed(FailureCause.EVALUATION_ERROR, check, success.getErrors().toString());
    }
    if (success.isAllowed()) {
      return new Decision.Allowed<>(value);
    }
    return new Decision.Denied<>(Decision.DenialReason.POLICY);
  }

  private <T> Decision<T> failClosed(
      FailureCause cause, AuthorizationCheck check, String diagnostics) {
    log.error("Authorization failed closed for {} ({}): {}", check.action(), cause, diagnostics);
    return countFailClosed(cause);
  }

  private <T> Decision<T> countFailClosed(FailureCause cause) {
    meterRegistry.counter(FAIL_CLOSED_METRIC, "cause", cause.name()).increment();
    return new Decision.Failed<>(cause);
  }
}
