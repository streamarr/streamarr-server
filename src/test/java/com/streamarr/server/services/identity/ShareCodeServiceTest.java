package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.ShareCodeStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeShareCodeRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.properties.CredentialCodeProperties;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.ShareCodes;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** Share codes over fakes: who may issue one, what the answer carries, and what the row keeps. */
@Tag("UnitTest")
@DisplayName("Share Code Service Tests")
class ShareCodeServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final Duration SHARE_CODE_TTL = Duration.ofDays(7);

  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeShareCodeRepository shareCodes = new FakeShareCodeRepository();
  private final ShareCodes codes = new ShareCodes();

  private UserAccount issuer;
  private FakeAuthorizationService authorization;
  private ShareCodeService service;
  private UUID householdId;

  @BeforeEach
  void setUp() {
    householdId = households.save(HouseholdFixture.defaultHouseholdBuilder().build()).getId();
    issuer =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(householdId)
                .displayName("Household Admin")
                .build());
    authorization = new FakeAuthorizationService(this::identity);
    service =
        new ShareCodeService(
            authorization,
            shareCodes,
            households,
            accounts,
            new MutationTransactions(
                new FakeTransactionManager(), new ConstraintViolationTranslator()),
            codes,
            CredentialCodeProperties.builder()
                .invitationTtl(Duration.ofDays(7))
                .passwordResetTtl(Duration.ofHours(1))
                .shareCodeTtl(SHARE_CODE_TTL)
                .replacementLockTimeout(Duration.ofSeconds(5))
                .build(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("Should answer with a code that only its digest is stored by when a code is issued")
  void shouldAnswerWithCodeThatOnlyItsDigestIsStoredByWhenCodeIsIssued() {
    var issued = accepted(service.issueShareCode(identity(), householdId));

    var stored = shareCodes.findById(issued.shareCode().shareCode().getId()).orElseThrow();
    assertThat(issued.code()).matches("[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}");
    assertThat(stored.getCodeDigest()).isEqualTo(codes.digestOf(issued.code()).orElseThrow());
    assertThat(stored.getHouseholdId()).isEqualTo(householdId);
    assertThat(stored.getIssuerAccountId()).isEqualTo(issuer.getId());
    assertThat(stored.statusAt(NOW)).isEqualTo(ShareCodeStatus.LIVE);
    assertThat(stored.getExpiresAt()).isEqualTo(NOW.plus(SHARE_CODE_TTL));
    assertThat(issued.shareCode().issuedByDisplayName()).isEqualTo("Household Admin");
    assertThat(issued.toString()).doesNotContain(issued.code());
  }

  @Test
  @DisplayName("Should keep every code live when a Household issues several")
  void shouldKeepEveryCodeLiveWhenHouseholdIssuesSeveral() {
    var first = accepted(service.issueShareCode(identity(), householdId));
    var second = accepted(service.issueShareCode(identity(), householdId));

    assertThat(second.code()).isNotEqualTo(first.code());
    assertThat(shareCodes.findAll())
        .hasSize(2)
        .allSatisfy(stored -> assertThat(stored.isLiveAt(NOW)).isTrue());
  }

  @Test
  @DisplayName("Should refuse as not found when the caller may not see the Household's codes")
  void shouldRefuseAsNotFoundWhenCallerMayNotSeeHouseholdsCodes() {
    authorization.denyAll();

    assertThat(rejectionOf(service.issueShareCode(identity(), householdId)))
        .isInstanceOf(ShareCodeRejections.HouseholdNotFound.class);
    assertThat(shareCodes.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse as forbidden when the caller sees the codes but may not issue one")
  void shouldRefuseAsForbiddenWhenCallerSeesCodesButMayNotIssueOne() {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.ViewShareCodes
                ? new Decision.Allowed<>(AuthorizationUnit.INSTANCE)
                : new Decision.Denied<>(Decision.DenialReason.POLICY));

    assertThatThrownBy(() -> service.issueShareCode(identity(), householdId))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(shareCodes.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse as not found when the Household does not exist")
  void shouldRefuseAsNotFoundWhenHouseholdDoesNotExist() {
    assertThat(rejectionOf(service.issueShareCode(identity(), UUID.randomUUID())))
        .isInstanceOf(ShareCodeRejections.HouseholdNotFound.class);
    assertThat(shareCodes.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should fail closed when authorization is unavailable")
  void shouldFailClosedWhenAuthorizationIsUnavailable() {
    authorization.failWith(Decision.FailureCause.EVALUATION_ERROR);

    assertThatThrownBy(() -> service.issueShareCode(identity(), householdId))
        .isInstanceOf(AuthorizationUnavailableException.class);
    assertThat(shareCodes.findAll()).isEmpty();
  }

  private AuthenticatedIdentity identity() {
    return AuthenticatedIdentityFixture.accountScopedBuilder()
        .accountId(issuer.getId())
        .householdId(householdId)
        .contextHouseholdId(householdId)
        .build();
  }

  private static <T, R> T accepted(Outcome<T, R> outcome) {
    return outcome.fold(
        result -> result,
        rejections -> {
          throw new AssertionError("rejected: " + rejections);
        });
  }

  private static <T, R> R rejectionOf(Outcome<T, R> outcome) {
    return outcome.fold(
        result -> {
          throw new AssertionError("accepted: " + result);
        },
        rejections -> rejections.getFirst());
  }
}
