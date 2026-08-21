package com.streamarr.server.services.authorization.cedar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cedarpolicy.AuthorizationEngine;
import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.AuthorizationSuccessResponse;
import com.cedarpolicy.model.DetailedError;
import com.cedarpolicy.model.EntityValidationRequest;
import com.cedarpolicy.model.LevelValidationRequest;
import com.cedarpolicy.model.PartialAuthorizationRequest;
import com.cedarpolicy.model.PartialAuthorizationResponse;
import com.cedarpolicy.model.ValidationRequest;
import com.cedarpolicy.model.ValidationResponse;
import com.cedarpolicy.model.entity.Entities;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.exception.AuthException;
import com.cedarpolicy.model.policy.PolicySet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Cedar Engine Self Check Tests")
class CedarEngineSelfCheckTest {

  private static final AuthorizationEngine REAL = new BasicAuthorizationEngine();

  @Test
  @DisplayName("Should allow the permitted Account and deny a stranger when the engine runs here")
  void shouldAllowPermittedAccountAndDenyStrangerWhenEngineRunsHere() {
    var result = new CedarEngineSelfCheck().run();

    assertThat(result.permittedAccountAllowed()).isTrue();
    assertThat(result.strangerAccountDenied()).isTrue();
    assertThat(result.passed()).isTrue();
  }

  @Test
  @DisplayName("Should report failure when either decision is wrong")
  void shouldReportFailureWhenEitherDecisionIsWrong() {
    assertThat(new CedarEngineSelfCheck.Result(true, false).passed()).isFalse();
    assertThat(new CedarEngineSelfCheck.Result(false, true).passed()).isFalse();
  }

  @Test
  @DisplayName("Should fail when the engine allows the stranger")
  void shouldFailWhenEngineAllowsStranger() {
    var engine =
        new StubEngine(
            StubEngine::passThroughValidation,
            _ ->
                authorizationResponse()
                    .decision(AuthorizationSuccessResponse.Decision.Allow)
                    .build());

    assertThatThrownBy(() -> new CedarEngineSelfCheck(engine).run())
        .isInstanceOf(CedarSelfCheckException.class)
        .hasMessageContaining("decisions");
  }

  @Test
  @DisplayName("Should fail when the self-check policies do not validate")
  void shouldFailWhenSelfCheckPoliciesDoNotValidate() {
    var engine =
        new StubEngine(
            _ -> validationResponse().policyError("bad").build(), UnaryOperator.identity());

    assertThatThrownBy(() -> new CedarEngineSelfCheck(engine).run())
        .isInstanceOf(CedarSelfCheckException.class)
        .hasMessageContaining("validation");
  }

  @Test
  @DisplayName("Should fail when policy validation reports warnings")
  void shouldFailWhenPolicyValidationReportsWarnings() {
    var engine =
        new StubEngine(
            _ -> validationResponse().policyWarning("warning").build(), UnaryOperator.identity());

    assertThatThrownBy(() -> new CedarEngineSelfCheck(engine).run())
        .isInstanceOf(CedarSelfCheckException.class)
        .hasMessageContaining("diagnostics");
  }

  @Test
  @DisplayName("Should fail when the validation response reports warnings")
  void shouldFailWhenValidationResponseReportsWarnings() {
    var engine =
        new StubEngine(
            _ -> validationResponse().responseWarning("warning").build(), UnaryOperator.identity());

    assertThatThrownBy(() -> new CedarEngineSelfCheck(engine).run())
        .isInstanceOf(CedarSelfCheckException.class)
        .hasMessageContaining("diagnostics");
  }

  @Test
  @DisplayName("Should fail when the engine cannot evaluate the self-check request")
  void shouldFailWhenEngineCannotEvaluateSelfCheckRequest() {
    var engine =
        new StubEngine(
            StubEngine::passThroughValidation, _ -> authorizationResponse().failure().build());

    assertThatThrownBy(() -> new CedarEngineSelfCheck(engine).run())
        .isInstanceOf(CedarSelfCheckException.class)
        .hasMessageContaining("evaluation failed");
  }

  @Test
  @DisplayName("Should fail when evaluation reports diagnostics")
  void shouldFailWhenEvaluationReportsDiagnostics() {
    var engine =
        new StubEngine(
            StubEngine::passThroughValidation,
            _ ->
                authorizationResponse()
                    .decision(AuthorizationSuccessResponse.Decision.Allow)
                    .evaluationError("boom")
                    .build());

    assertThatThrownBy(() -> new CedarEngineSelfCheck(engine).run())
        .isInstanceOf(CedarSelfCheckException.class)
        .hasMessageContaining("diagnostics");
  }

  @Test
  @DisplayName("Should fail when the authorization response reports warnings")
  void shouldFailWhenAuthorizationResponseReportsWarnings() {
    var engine =
        new StubEngine(
            StubEngine::passThroughValidation,
            _ ->
                authorizationResponse()
                    .decision(AuthorizationSuccessResponse.Decision.Allow)
                    .warning("warning")
                    .build());

    assertThatThrownBy(() -> new CedarEngineSelfCheck(engine).run())
        .isInstanceOf(CedarSelfCheckException.class)
        .hasMessageContaining("diagnostics");
  }

  @Test
  @DisplayName("Should fail when the engine throws")
  void shouldFailWhenEngineThrows() {
    var engine =
        new StubEngine(
            _ -> {
              throw new AuthException("native bridge lost");
            },
            UnaryOperator.identity());

    assertThatThrownBy(() -> new CedarEngineSelfCheck(engine).run())
        .isInstanceOf(CedarSelfCheckException.class)
        .hasMessageContaining("could not evaluate")
        .hasCauseInstanceOf(AuthException.class);
  }

  private static ValidationResponseBuilder validationResponse() {
    return new ValidationResponseBuilder();
  }

  private static AuthorizationResponseBuilder authorizationResponse() {
    return new AuthorizationResponseBuilder();
  }

  private static DetailedError diagnostic(String message) {
    return new DetailedError(
        message,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static final class ValidationResponseBuilder {

    private final List<ValidationResponse.ValidationError> policyErrors = new ArrayList<>();
    private final List<ValidationResponse.ValidationError> policyWarnings = new ArrayList<>();
    private final List<DetailedError> responseWarnings = new ArrayList<>();

    private ValidationResponseBuilder policyError(String message) {
      policyErrors.add(new ValidationResponse.ValidationError("p", diagnostic(message)));
      return this;
    }

    private ValidationResponseBuilder policyWarning(String message) {
      policyWarnings.add(new ValidationResponse.ValidationError("p", diagnostic(message)));
      return this;
    }

    private ValidationResponseBuilder responseWarning(String message) {
      responseWarnings.add(diagnostic(message));
      return this;
    }

    private ValidationResponse build() {
      return new ValidationResponse(
          ValidationResponse.SuccessOrFailure.Success,
          Optional.of(policyErrors),
          Optional.of(policyWarnings),
          Optional.empty(),
          responseWarnings.isEmpty() ? Optional.empty() : Optional.of(responseWarnings));
    }
  }

  private static final class AuthorizationResponseBuilder {

    private AuthorizationResponse.SuccessOrFailure type =
        AuthorizationResponse.SuccessOrFailure.Success;
    private AuthorizationSuccessResponse.Decision decision =
        AuthorizationSuccessResponse.Decision.Allow;
    private final List<AuthorizationSuccessResponse.AuthorizationError> evaluationErrors =
        new ArrayList<>();
    private final ArrayList<String> warnings = new ArrayList<>();

    private AuthorizationResponseBuilder decision(AuthorizationSuccessResponse.Decision decision) {
      this.decision = decision;
      return this;
    }

    private AuthorizationResponseBuilder evaluationError(String message) {
      evaluationErrors.add(
          new AuthorizationSuccessResponse.AuthorizationError("p", diagnostic(message)));
      return this;
    }

    private AuthorizationResponseBuilder failure() {
      type = AuthorizationResponse.SuccessOrFailure.Failure;
      return this;
    }

    private AuthorizationResponseBuilder warning(String warning) {
      warnings.add(warning);
      return this;
    }

    private AuthorizationResponse build() {
      if (type == AuthorizationResponse.SuccessOrFailure.Failure) {
        return new AuthorizationResponse(
            type, Optional.empty(), Optional.of(new ArrayList<>()), warnings);
      }
      var diagnostics = new AuthorizationSuccessResponse.Diagnostics(Set.of(), evaluationErrors);
      var success = new AuthorizationSuccessResponse(decision, diagnostics);
      return new AuthorizationResponse(type, Optional.of(success), Optional.empty(), warnings);
    }
  }

  /** Wraps the real engine so a test can replace the validation or authorization answer. */
  private static final class StubEngine implements AuthorizationEngine {

    private final ValidationAnswer validation;
    private final UnaryOperator<AuthorizationResponse> authorization;

    private StubEngine(
        ValidationAnswer validation, UnaryOperator<AuthorizationResponse> authorization) {
      this.validation = validation;
      this.authorization = authorization;
    }

    private static ValidationResponse passThroughValidation(ValidationRequest request)
        throws AuthException {
      return REAL.validate(request);
    }

    @Override
    public AuthorizationResponse isAuthorized(
        AuthorizationRequest request, PolicySet policySet, Set<Entity> entities)
        throws AuthException {
      return authorization.apply(REAL.isAuthorized(request, policySet, entities));
    }

    @Override
    public AuthorizationResponse isAuthorized(
        AuthorizationRequest request, PolicySet policySet, Entities entities) throws AuthException {
      return authorization.apply(REAL.isAuthorized(request, policySet, entities));
    }

    @Override
    public PartialAuthorizationResponse isAuthorizedPartial(
        PartialAuthorizationRequest request, PolicySet policySet, Set<Entity> entities)
        throws AuthException {
      return REAL.isAuthorizedPartial(request, policySet, entities);
    }

    @Override
    public PartialAuthorizationResponse isAuthorizedPartial(
        PartialAuthorizationRequest request, PolicySet policySet, Entities entities)
        throws AuthException {
      return REAL.isAuthorizedPartial(request, policySet, entities);
    }

    @Override
    public ValidationResponse validate(ValidationRequest request) throws AuthException {
      return validation.answer(request);
    }

    @Override
    public ValidationResponse validateWithLevel(LevelValidationRequest request)
        throws AuthException {
      return REAL.validateWithLevel(request);
    }

    @Override
    public void validateEntities(EntityValidationRequest request) throws AuthException {
      REAL.validateEntities(request);
    }

    @FunctionalInterface
    private interface ValidationAnswer {
      ValidationResponse answer(ValidationRequest request) throws AuthException;
    }
  }
}
