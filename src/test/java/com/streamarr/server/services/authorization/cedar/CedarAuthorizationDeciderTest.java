package com.streamarr.server.services.authorization.cedar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.cedarpolicy.AuthorizationEngine;
import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.Context;
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
import com.cedarpolicy.value.PrimBool;
import com.cedarpolicy.value.PrimString;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfilePolicySnapshot;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Cedar Authorization Decider Tests")
class CedarAuthorizationDeciderTest {

  private static final AuthorizationEngine ENGINE = new BasicAuthorizationEngine();
  private static final CedarPolicyBundle BUNDLE =
      new CedarPolicyBundle(ENGINE, new PathMatchingResourcePatternResolver());

  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final CedarAuthorizationDecider decider =
      decider(ENGINE, new LivePrincipalAuthorityContributor(accounts));
  private final AuthorizationService authorizationService =
      new SecurityContextAuthorizationService(decider, accounts);

  static Stream<Arguments> libraryAdministrationIntents() {
    var libraryId = UUID.randomUUID();
    return Stream.of(
        Arguments.of(new Intent.AddLibrary()),
        Arguments.of(new Intent.RemoveLibrary(libraryId)),
        Arguments.of(new Intent.ScanLibrary(libraryId)),
        Arguments.of(new Intent.RefreshLibrary(libraryId)));
  }

  @ParameterizedTest
  @MethodSource("libraryAdministrationIntents")
  @DisplayName(
      "Should allow library administration through the facade when the live row is an enabled ServerAdmin")
  void shouldAllowLibraryAdministrationThroughFacadeWhenLiveRowIsEnabledServerAdmin(
      Intent<AuthorizationUnit> intent) {
    var identity = identityFor(liveAccount(true, true), false);

    assertThat(authorizationService.requireAllowed(identity, intent))
        .isEqualTo(AuthorizationUnit.INSTANCE);
  }

  @ParameterizedTest
  @MethodSource("libraryAdministrationIntents")
  @DisplayName(
      "Should deny library administration through the facade when the live row is not a ServerAdmin")
  void shouldDenyLibraryAdministrationThroughFacadeWhenLiveRowIsNotServerAdmin(
      Intent<AuthorizationUnit> intent) {
    // The token still says ADMIN: a revoked claim must never be enough.
    var identity = identityFor(liveAccount(false, true), true);

    assertThatThrownBy(() -> authorizationService.requireAllowed(identity, intent))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Not allowed.");
  }

  @Test
  @DisplayName("Should deny when the live ServerAdmin is disabled")
  void shouldDenyWhenLiveServerAdminIsDisabled() {
    var identity = identityFor(liveAccount(true, false), true);

    assertThat(decider.decide(identity, new Intent.AddLibrary()))
        .isEqualTo(new Decision.Denied<>(Decision.DenialReason.POLICY));
  }

  @Test
  @DisplayName("Should deny when no authority facts exist for the Account")
  void shouldDenyWhenNoAuthorityFactsExistForAccount() {
    var identity = identityFor(UUID.randomUUID(), true);

    assertThat(decider.decide(identity, new Intent.AddLibrary()))
        .isEqualTo(new Decision.Denied<>(Decision.DenialReason.POLICY));
    assertThat(failClosedCount()).isZero();
  }

  @Test
  @DisplayName("Should fail closed when the slice does not conform to the schema")
  void shouldFailClosedWhenSliceDoesNotConformToSchema() {
    var malformed =
        decider(
            ENGINE,
            contributor(
                slice ->
                    slice.principalAttribute(
                        LivePrincipalAuthorityContributor.ENABLED, new PrimString("yes"))));
    var identity = identityFor(liveAccount(true, true), true);

    assertThat(malformed.decide(identity, new Intent.AddLibrary()))
        .isEqualTo(new Decision.Failed<>(Decision.FailureCause.INVALID_SLICE));
    assertThat(failClosedCount(Decision.FailureCause.INVALID_SLICE)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Should fail closed when the request does not conform to the schema")
  void shouldFailClosedWhenRequestDoesNotConformToSchema() {
    var badContext =
        new RewritingEngine(
            ENGINE,
            request ->
                new AuthorizationRequest(
                    request.principalEUID,
                    request.actionEUID,
                    request.resourceEUID,
                    new Context(Map.of("unknownKey", new PrimBool(true))),
                    request.schema,
                    request.enableRequestValidation));
    var identity = identityFor(liveAccount(true, true), true);

    assertThat(
            decider(badContext, new LivePrincipalAuthorityContributor(accounts))
                .decide(identity, new Intent.AddLibrary()))
        .isEqualTo(new Decision.Failed<>(Decision.FailureCause.INVALID_REQUEST));
    assertThat(failClosedCount(Decision.FailureCause.INVALID_REQUEST)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Should fail closed when evaluation reports a diagnostic even though it also allows")
  void shouldFailClosedWhenEvaluationReportsDiagnosticEvenThoughItAlsoAllows() throws Exception {
    var erroringPolicies =
        PolicySet.parsePolicies(
            """
            @id("permit-everything")
            permit (principal, action, resource);
            @id("erroring-forbid")
            forbid (principal, action, resource) when { principal.serverAdmin == false };
            """);
    var engine = new RewritingEngine(ENGINE, Function.identity(), erroringPolicies);
    var identity = identityFor(liveAccount(true, true), true);
    var noFacts = decider(engine, contributor(_ -> {}));

    assertThat(noFacts.decide(identity, new Intent.AddLibrary()))
        .isEqualTo(new Decision.Failed<>(Decision.FailureCause.EVALUATION_ERROR));
    assertThat(failClosedCount(Decision.FailureCause.EVALUATION_ERROR)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Should fail closed when the intent is null")
  void shouldFailClosedWhenIntentIsNull() {
    var identity = identityFor(UUID.randomUUID(), true);
    Intent<AuthorizationUnit> intent = null;

    assertThat(decider.decide(identity, intent))
        .isEqualTo(new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE));
    assertThat(failClosedCount(Decision.FailureCause.ENGINE_FAILURE)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Should fail closed without a step-up retry when a group evaluation errors")
  void shouldFailClosedWithoutStepUpRetryWhenGroupEvaluationErrors() throws Exception {
    var erroringPolicies =
        PolicySet.parsePolicies(
            """
            @id("erroring-forbid")
            forbid (principal, action, resource) when { principal.serverAdmin == false };
            """);
    var engine = new RewritingEngine(ENGINE, Function.identity(), erroringPolicies);
    var identity = identityFor(liveAccount(true, true), true);
    var noFacts = decider(engine, contributor(_ -> {}));

    // A stale caller whose evaluation cannot be completed is Failed, never
    // REAUTHENTICATION_REQUIRED — the step-up probe only classifies clean denials.
    assertThat(noFacts.decide(identity, new Intent.GrantServerAdmin(UUID.randomUUID())))
        .isEqualTo(new Decision.Failed<>(Decision.FailureCause.EVALUATION_ERROR));
  }

  @Test
  @DisplayName("Should fail closed when a contributor throws")
  void shouldFailClosedWhenContributorThrows() {
    var failing =
        decider(
            ENGINE,
            contributor(
                _ -> {
                  throw new IllegalStateException("database unavailable");
                }));
    var identity = identityFor(liveAccount(true, true), true);

    assertThat(failing.decide(identity, new Intent.AddLibrary()))
        .isEqualTo(new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE));
    assertThat(failClosedCount(Decision.FailureCause.ENGINE_FAILURE)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Should fail closed when policy-change planning throws")
  void shouldFailClosedWhenPolicyChangePlanningThrows() {
    var unavailableProfiles =
        new FakeProfileRepository() {
          @Override
          public Optional<ProfilePolicySnapshot> lockPolicyById(UUID profileId) {
            throw new IllegalStateException("database unavailable");
          }
        };
    var failing =
        new CedarAuthorizationDecider(
            ENGINE,
            BUNDLE,
            new SliceAssembler(
                ContributorStubs.allWith(new LivePrincipalAuthorityContributor(accounts))),
            new ProfilePolicyPlanner(unavailableProfiles),
            ContributorStubs.systemClockFreshness(),
            meters);
    var identity = identityFor(liveAccount(true, true), true);

    assertThat(
            failing.decide(
                identity, new Intent.ChangeProfileKind(UUID.randomUUID(), ProfileKind.ADULT)))
        .isEqualTo(new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE));
    assertThat(failClosedCount(Decision.FailureCause.ENGINE_FAILURE)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Should fail closed when the engine throws")
  void shouldFailClosedWhenEngineThrows() {
    var throwing =
        new RewritingEngine(
            ENGINE,
            _ -> {
              throw new IllegalStateException("native bridge lost");
            });
    var identity = identityFor(liveAccount(true, true), true);

    assertThat(
            decider(throwing, new LivePrincipalAuthorityContributor(accounts))
                .decide(identity, new Intent.AddLibrary()))
        .isEqualTo(new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE));
    assertThat(failClosedCount(Decision.FailureCause.ENGINE_FAILURE)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Should log an error when authorization fails closed")
  void shouldLogErrorWhenAuthorizationFailsClosed() {
    var throwing =
        new RewritingEngine(
            ENGINE,
            _ -> {
              throw new IllegalStateException("native bridge lost");
            });
    var identity = identityFor(liveAccount(true, true), true);
    var logger = (Logger) LoggerFactory.getLogger(CedarAuthorizationDecider.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);

    try {
      decider(throwing, new LivePrincipalAuthorityContributor(accounts))
          .decide(identity, new Intent.AddLibrary());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    assertThat(appender.list)
        .filteredOn(event -> event.getLevel() == Level.ERROR)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getFormattedMessage())
                  .isEqualTo("Authorization failed closed for ADD_LIBRARY (ENGINE_FAILURE)");
              assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy()))
                  .contains("native bridge lost");
            });
  }

  private CedarAuthorizationDecider decider(
      AuthorizationEngine engine, FactContributor contributor) {
    return new CedarAuthorizationDecider(
        engine,
        BUNDLE,
        new SliceAssembler(ContributorStubs.allWith(contributor)),
        new ProfilePolicyPlanner(new FakeProfileRepository()),
        ContributorStubs.systemClockFreshness(),
        meters);
  }

  private static FactContributor contributor(Consumer<EntitySlice> contribution) {
    return new FactContributor() {
      @Override
      public FactRequirement provides() {
        return FactRequirement.LIVE_PRINCIPAL_AUTHORITY;
      }

      @Override
      public void contribute(
          AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
        contribution.accept(slice);
      }
    };
  }

  private UUID liveAccount(boolean serverAdmin, boolean enabled) {
    return accounts
        .save(
            AccountFixture.defaultAccountBuilder()
                .serverAdmin(serverAdmin)
                .enabled(enabled)
                .build())
        .getId();
  }

  /** The token's ServerAdmin claim is display only; the live row decides. */
  private static AuthenticatedIdentity identityFor(UUID accountId, boolean tokenSaysAdmin) {
    return AuthenticatedIdentityFixture.profileScopedBuilder()
        .accountId(accountId)
        .serverAdmin(tokenSaysAdmin)
        .build();
  }

  private double failClosedCount() {
    return meters.find(CedarAuthorizationDecider.FAIL_CLOSED_METRIC).counters().stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  private double failClosedCount(Decision.FailureCause cause) {
    return meters
        .counter(CedarAuthorizationDecider.FAIL_CLOSED_METRIC, "cause", cause.name())
        .count();
  }

  /** Delegates to the real engine but lets a test rewrite the request or swap the policy set. */
  private static final class RewritingEngine implements AuthorizationEngine {

    private final AuthorizationEngine delegate;
    private final Function<AuthorizationRequest, AuthorizationRequest> rewrite;
    private final PolicySet policyOverride;

    private RewritingEngine(
        AuthorizationEngine delegate,
        Function<AuthorizationRequest, AuthorizationRequest> rewrite) {
      this(delegate, rewrite, null);
    }

    private RewritingEngine(
        AuthorizationEngine delegate,
        Function<AuthorizationRequest, AuthorizationRequest> rewrite,
        PolicySet policyOverride) {
      this.delegate = delegate;
      this.rewrite = rewrite;
      this.policyOverride = policyOverride;
    }

    @Override
    public AuthorizationResponse isAuthorized(
        AuthorizationRequest request, PolicySet policySet, Set<Entity> entities)
        throws AuthException {
      return delegate.isAuthorized(rewrite.apply(request), policies(policySet), entities);
    }

    @Override
    public AuthorizationResponse isAuthorized(
        AuthorizationRequest request, PolicySet policySet, Entities entities) throws AuthException {
      return delegate.isAuthorized(rewrite.apply(request), policies(policySet), entities);
    }

    @Override
    public PartialAuthorizationResponse isAuthorizedPartial(
        PartialAuthorizationRequest request, PolicySet policySet, Set<Entity> entities)
        throws AuthException {
      return delegate.isAuthorizedPartial(request, policySet, entities);
    }

    @Override
    public PartialAuthorizationResponse isAuthorizedPartial(
        PartialAuthorizationRequest request, PolicySet policySet, Entities entities)
        throws AuthException {
      return delegate.isAuthorizedPartial(request, policySet, entities);
    }

    @Override
    public ValidationResponse validate(ValidationRequest request) throws AuthException {
      return delegate.validate(request);
    }

    @Override
    public ValidationResponse validateWithLevel(LevelValidationRequest request)
        throws AuthException {
      return delegate.validateWithLevel(request);
    }

    @Override
    public void validateEntities(EntityValidationRequest request) throws AuthException {
      delegate.validateEntities(request);
    }

    private PolicySet policies(PolicySet requested) {
      return policyOverride == null ? requested : policyOverride;
    }
  }
}
