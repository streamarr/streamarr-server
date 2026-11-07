package com.streamarr.server.services.authorization.cedar;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.support.AuthTestSupport;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every action that authorizes a read, decided by the real engine against PostgreSQL inside a
 * read-only transaction, where a row lock is refused. A new read action joins the parameters by
 * itself and fails here until {@link #intentFor} names the intent that asks for it.
 */
@Tag("IntegrationTest")
@DisplayName("Read Decisions In Read-Only Transaction Integration Tests")
class ReadDecisionsInReadOnlyTransactionIT extends AbstractIntegrationTest {

  private static final Decision<AuthorizationUnit> ALLOWED =
      new Decision.Allowed<>(AuthorizationUnit.INSTANCE);

  @Autowired private AuthorizationService authorizationService;
  @Autowired private IntentPlanner intentPlanner;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private PlatformTransactionManager transactionManager;

  private AuthTestSupport.TestIdentity serverAdmin;

  @BeforeEach
  void createServerAdmin() {
    serverAdmin = authTestSupport.createAdminIdentity();
  }

  @AfterEach
  void deleteServerAdmin() {
    authTestSupport.deleteIdentity(serverAdmin);
  }

  static Stream<Action> readActions() {
    return Arrays.stream(Action.values()).filter(action -> !action.authorizesWrite());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("readActions")
  @DisplayName(
      "Should allow the ServerAdmin's own read when it is decided inside a read-only transaction")
  void shouldAllowTheServerAdminsOwnReadWhenItIsDecidedInsideReadOnlyTransaction(Action action) {
    var identity = watchingAs(serverAdmin);
    var intent = intentFor(action);
    assertThat(intentPlanner.plan(identity, intent).check().action()).isEqualTo(action);
    var readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);

    var decision = readOnly.execute(_ -> authorizationService.decide(identity, intent));

    assertThat(decision).isEqualTo(ALLOWED);
  }

  private Intent.UnitIntent intentFor(Action action) {
    var accountId = serverAdmin.account().getId();
    var householdId = serverAdmin.household().getId();
    var profileId = serverAdmin.profile().getId();
    return switch (action) {
      case VIEW_LIBRARY_ADMINISTRATION -> new Intent.ViewLibraryAdministration();
      case VIEW_PROFILE_PICKER -> new Intent.ViewProfilePicker();
      case PLAYBACK -> new Intent.Playback();
      case VIEW_PROFILE_ACTIVITY -> new Intent.ViewProfileActivity(profileId);
      case VIEW_HOUSEHOLD_ADMINISTRATION -> new Intent.ViewHouseholdAdministration(householdId);
      case VIEW_HOUSEHOLDS -> new Intent.ViewHouseholds();
      case VIEW_ACCOUNT_ADMINISTRATION -> new Intent.ViewAccountAdministration(accountId);
      case VIEW_PROFILE_ADMINISTRATION -> new Intent.ViewProfileAdministration(profileId);
      case VIEW_MANAGER_INVITATIONS -> new Intent.ViewManagerInvitations(profileId);
      case VIEW_ACCOUNT_INVITATIONS -> new Intent.ViewAccountInvitations();
      case VIEW_DEVICE_ADMINISTRATION -> new Intent.ViewDeviceAdministration(householdId);
      case VIEW_SERVER_DEVICE_ADMINISTRATION -> new Intent.ViewServerDeviceAdministration();
      case VIEW_SECURITY_AUDIT -> new Intent.ViewSecurityAudit();
      case VIEW_ENCODER_PRESET -> new Intent.ViewEncoderPreset();
      case VIEW_IDLE_SEGMENT_RETENTION -> new Intent.ViewIdleSegmentRetention();
      case VIEW_ACTIVE_STREAMS -> new Intent.ViewActiveStreams();
      case VIEW_PLAYBACK_REPORTS -> new Intent.ViewPlaybackReports();
      default -> throw new AssertionError(action + " authorizes a read but names no intent here");
    };
  }

  private AuthenticatedIdentity watchingAs(AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.fromJwt(
        jwtDecoder.decode(authTestSupport.profileBearer(identity)));
  }
}
