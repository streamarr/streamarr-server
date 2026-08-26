package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Tag("IntegrationTest")
@DisplayName("Credential Lifecycle Integration Tests")
class CredentialLifecycleIT extends AbstractIntegrationTest {

  @Autowired private CredentialIssuanceService credentialIssuanceService;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity issuer;
  private AuthTestSupport.TestIdentity resetTarget;

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    invitationRepository.deleteAll();
    resetCodeRepository.deleteAll();
    if (resetTarget != null) {
      authTestSupport.deleteIdentity(resetTarget);
    }

    if (issuer != null) {
      authTestSupport.deleteIdentity(issuer);
    }
  }

  @Test
  @DisplayName("Should expire a stale invitation just in time when its replacement is issued")
  void shouldExpireStaleInvitationJustInTimeWhenReplacementIsIssued() {
    issuer = authTestSupport.createAdminIdentity();
    var email = "stale-invitation@example.com";
    invitationRepository.saveAndFlush(
        AccountInvitation.builder()
            .recipientEmail(email)
            .householdId(issuer.household().getId())
            .householdName(issuer.household().getName())
            .householdRole(HouseholdRole.MEMBER)
            .profileName("Stale")
            .profileKind(ProfileKind.ADULT)
            .issuerAccountId(issuer.account().getId())
            .expiresAt(Instant.now().minusSeconds(1))
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[32])
            .build());

    var outcome =
        credentialIssuanceService.issueAccountInvitation(
            identityOf(issuer),
            IssueInvitationCommand.builder()
                .recipientEmail(email)
                .householdId(issuer.household().getId())
                .householdRole(HouseholdRole.MEMBER)
                .profileName("Replacement")
                .profileKind(ProfileKind.ADULT)
                .build());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(invitationRepository.findAll())
        .extracting(AccountInvitation::getStatus)
        .containsExactlyInAnyOrder(
            AccountInvitationStatus.EXPIRED, AccountInvitationStatus.PENDING);
  }

  @Test
  @DisplayName("Should expire a stale reset code just in time when its replacement is issued")
  void shouldExpireStaleResetCodeJustInTimeWhenReplacementIsIssued() {
    issuer = authTestSupport.createAdminIdentity();
    resetTarget = authTestSupport.createIdentity();
    resetCodeRepository.saveAndFlush(
        PasswordResetCode.builder()
            .accountId(resetTarget.account().getId())
            .issuerAccountId(issuer.account().getId())
            .expiresAt(Instant.now().minusSeconds(1))
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[32])
            .build());

    var outcome =
        credentialIssuanceService.issuePasswordReset(
            freshIdentityOf(issuer), resetTarget.account().getId(), "recover access");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(resetCodeRepository.findAll())
        .extracting(PasswordResetCode::getStatus)
        .containsExactlyInAnyOrder(
            PasswordResetCodeStatus.EXPIRED, PasswordResetCodeStatus.PENDING);
  }

  @Test
  @DisplayName("Should persist only reset-code statuses supported by real transitions")
  void shouldPersistOnlyResetCodeStatusesSupportedByRealTransitions() {
    var status =
        field("unnest(enum_range(NULL::password_reset_code_status))", String.class).as("status");

    assertThat(dsl.select(status).fetch(status))
        .containsExactly("PENDING", "REDEEMED", "EXPIRED", "INVALIDATED");
  }

  @Test
  @DisplayName("Should invalidate a pending invitation when its target Household disappears")
  void shouldInvalidatePendingInvitationWhenTargetHouseholdDisappears() {
    issuer = authTestSupport.createAdminIdentity();
    var targetHousehold =
        householdRepository.saveAndFlush(Household.builder().name("Target").build());
    var invitation =
        invitationRepository.saveAndFlush(
            pendingInvitationBuilder()
                .householdId(targetHousehold.getId())
                .householdName(targetHousehold.getName())
                .build());

    householdRepository.deleteById(targetHousehold.getId());
    householdRepository.flush();

    assertThat(invitationRepository.findById(invitation.getId()).orElseThrow())
        .satisfies(
            persisted -> {
              assertThat(persisted.getStatus()).isEqualTo(AccountInvitationStatus.INVALIDATED);
              assertThat(persisted.getInvalidationReason()).isEqualTo("target Household deleted");
            });
  }

  @Test
  @DisplayName("Should invalidate a pending invitation when its required manager disappears")
  void shouldInvalidatePendingInvitationWhenRequiredManagerDisappears() {
    issuer = authTestSupport.createAdminIdentity();
    resetTarget = authTestSupport.createIdentity();
    var invitation =
        invitationRepository.saveAndFlush(
            pendingInvitationBuilder()
                .householdId(issuer.household().getId())
                .householdName(issuer.household().getName())
                .profileKind(ProfileKind.KID)
                .localManagerAccountId(resetTarget.account().getId())
                .build());

    authTestSupport.deleteIdentity(resetTarget);

    assertThat(invitationRepository.findById(invitation.getId()).orElseThrow())
        .satisfies(
            persisted -> {
              assertThat(persisted.getStatus()).isEqualTo(AccountInvitationStatus.INVALIDATED);
              assertThat(persisted.getInvalidationReason()).isEqualTo("required manager deleted");
            });
  }

  private AccountInvitation.AccountInvitationBuilder<?, ?> pendingInvitationBuilder() {
    return AccountInvitation.builder()
        .recipientEmail(UUID.randomUUID() + "@example.com")
        .householdRole(HouseholdRole.MEMBER)
        .profileName("Invited")
        .profileKind(ProfileKind.ADULT)
        .issuerAccountId(issuer.account().getId())
        .expiresAt(Instant.now().plusSeconds(3600))
        .publicId(UUID.randomUUID().toString())
        .secretDigest(new byte[32]);
  }

  private AuthenticatedIdentity identityOf(AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.fromJwt(
        jwtDecoder.decode(authTestSupport.accountBearer(identity)));
  }

  private AuthenticatedIdentity freshIdentityOf(AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.fromJwt(
        jwtDecoder.decode(authTestSupport.freshAccountBearer(identity)));
  }
}
