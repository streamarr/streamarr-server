package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.ShareCodeStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.RetryAfterAware;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeShareCodeRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.PaginationFixture;
import com.streamarr.server.properties.CredentialCodeProperties;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.ShareCodes;
import com.streamarr.server.services.auth.ShareCodesFixture;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;

/** Share codes over fakes: who may issue one, what the answer carries, and what the row keeps. */
@Tag("UnitTest")
@DisplayName("Share Code Service Tests")
class ShareCodeServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final Duration SHARE_CODE_TTL = Duration.ofDays(7);
  private static final UUID PRESENTER_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeShareCodeRepository shareCodes = new FakeShareCodeRepository();
  private final FakeCredentialAttemptRepository attempts = new FakeCredentialAttemptRepository();
  private final ShareCodes codes = ShareCodesFixture.shareCodes();

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
    service = serviceDrawingFrom(codes);
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
  @DisplayName("Should issue another code when the first drawn code matches a stored one")
  void shouldIssueAnotherCodeWhenFirstDrawnCodeMatchesStoredOne() {
    var colliding =
        serviceDrawingFrom(ShareCodesFixture.drawing("BBBBBBBB", "BBBBBBBB", "CCCCCCCC"));

    var first = accepted(colliding.issueShareCode(identity(), householdId));
    var second = accepted(colliding.issueShareCode(identity(), householdId));

    assertThat(first.code()).isEqualTo("BBBB-BBBB");
    assertThat(second.code()).isEqualTo("CCCC-CCCC");
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
    var caller = identity();

    assertThatThrownBy(() -> service.issueShareCode(caller, householdId))
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
    var caller = identity();

    assertThatThrownBy(() -> service.issueShareCode(caller, householdId))
        .isInstanceOf(AuthorizationUnavailableException.class);
    assertThat(shareCodes.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should fail closed when the view decision is unavailable after a refused issue")
  void shouldFailClosedWhenViewDecisionIsUnavailableAfterRefusedIssue() {
    authorization.decideUnitWith(ShareCodeServiceTest::viewUnavailableOtherwiseDenied);
    var caller = identity();

    assertThatThrownBy(() -> service.issueShareCode(caller, householdId))
        .isInstanceOf(AuthorizationUnavailableException.class);
    assertThat(shareCodes.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should fail closed when the view decision is unavailable after a refused withdraw")
  void shouldFailClosedWhenViewDecisionIsUnavailableAfterRefusedWithdraw() {
    var issued = accepted(service.issueShareCode(identity(), householdId)).shareCode().shareCode();
    authorization.decideUnitWith(ShareCodeServiceTest::viewUnavailableOtherwiseDenied);
    var caller = identity();
    var issuedId = issued.getId();

    assertThatThrownBy(() -> service.withdrawShareCode(caller, issuedId))
        .isInstanceOf(AuthorizationUnavailableException.class);
    assertThat(issued.isLiveAt(NOW)).isTrue();
  }

  @Test
  @DisplayName("Should withdraw a live code once when an admin of the Household withdraws it")
  void shouldWithdrawLiveCodeOnceWhenAdminOfHouseholdWithdrawsIt() {
    var issued = accepted(service.issueShareCode(identity(), householdId)).shareCode().shareCode();

    var withdrawn = accepted(service.withdrawShareCode(identity(), issued.getId()));

    assertThat(withdrawn.shareCode().statusAt(NOW)).isEqualTo(ShareCodeStatus.WITHDRAWN);
    assertThat(withdrawn.shareCode().getEndedAt()).isEqualTo(NOW);
    assertThat(withdrawn.issuedByDisplayName()).isEqualTo("Household Admin");
    assertThat(rejectionOf(service.withdrawShareCode(identity(), issued.getId())))
        .isInstanceOf(ShareCodeRejections.ShareCodeNotLive.class);
  }

  @Test
  @DisplayName("Should withdraw a code that another admin issued when an admin withdraws it")
  void shouldWithdrawCodeThatAnotherAdminIssuedWhenAdminWithdrawsIt() {
    var issued = accepted(service.issueShareCode(identity(), householdId)).shareCode().shareCode();
    issuer =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(householdId)
                .displayName("Second Admin")
                .build());

    var withdrawn = accepted(service.withdrawShareCode(identity(), issued.getId()));

    assertThat(withdrawn.shareCode().getIssuerAccountId()).isNotEqualTo(issuer.getId());
    assertThat(withdrawn.issuedByDisplayName()).isEqualTo("Household Admin");
    assertThat(withdrawn.shareCode().statusAt(NOW)).isEqualTo(ShareCodeStatus.WITHDRAWN);
  }

  @Test
  @DisplayName("Should refuse as not live when the code has expired")
  void shouldRefuseAsNotLiveWhenCodeHasExpired() {
    var issued = accepted(service.issueShareCode(identity(), householdId)).shareCode().shareCode();
    issued.setExpiresAt(NOW);

    assertThat(rejectionOf(service.withdrawShareCode(identity(), issued.getId())))
        .isInstanceOf(ShareCodeRejections.ShareCodeNotLive.class);
    assertThat(issued.getStatus()).isEqualTo(ShareCodeStatus.LIVE);
  }

  @Test
  @DisplayName("Should refuse as not found when the caller may not see the code's Household")
  void shouldRefuseAsNotFoundWhenCallerMayNotSeeCodesHousehold() {
    var issued = accepted(service.issueShareCode(identity(), householdId)).shareCode().shareCode();
    authorization.denyAll();

    assertThat(rejectionOf(service.withdrawShareCode(identity(), issued.getId())))
        .isInstanceOf(ShareCodeRejections.ShareCodeNotFound.class);
    assertThat(rejectionOf(service.withdrawShareCode(identity(), UUID.randomUUID())))
        .isInstanceOf(ShareCodeRejections.ShareCodeNotFound.class);
    assertThat(issued.isLiveAt(NOW)).isTrue();
  }

  @Test
  @DisplayName("Should read an ended code with its issuer when the caller may view its Household")
  void shouldReadEndedCodeWithIssuerWhenCallerMayViewItsHousehold() {
    var issued = accepted(service.issueShareCode(identity(), householdId)).shareCode().shareCode();
    accepted(service.withdrawShareCode(identity(), issued.getId()));

    var read = service.shareCode(identity(), issued.getId()).orElseThrow();

    assertThat(read.shareCode().getId()).isEqualTo(issued.getId());
    assertThat(read.shareCode().statusAt(NOW)).isEqualTo(ShareCodeStatus.WITHDRAWN);
    assertThat(read.issuedByDisplayName()).isEqualTo(issuer.getDisplayName());
  }

  @Test
  @DisplayName("Should hide an existing or missing code when the caller may not view its Household")
  void shouldHideExistingOrMissingCodeWhenCallerMayNotViewItsHousehold() {
    var issued = accepted(service.issueShareCode(identity(), householdId)).shareCode().shareCode();
    authorization.denyAll();

    assertThat(service.shareCode(identity(), issued.getId())).isEmpty();
    assertThat(service.shareCode(identity(), UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("Should fail closed when authorization is unavailable for a code read by id")
  void shouldFailClosedWhenAuthorizationIsUnavailableForCodeReadById() {
    var issued = accepted(service.issueShareCode(identity(), householdId)).shareCode().shareCode();
    authorization.failWith(Decision.FailureCause.EVALUATION_ERROR);
    var caller = identity();
    var id = issued.getId();

    assertThatThrownBy(() -> service.shareCode(caller, id))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should list the codes newest first, without an issuer name, when it is deleted")
  void shouldListCodesNewestFirstWithoutIssuerNameWhenIssuerIsDeleted() {
    var older = accepted(service.issueShareCode(identity(), householdId)).shareCode().shareCode();
    var newer = accepted(service.issueShareCode(identity(), householdId)).shareCode().shareCode();
    AuditFieldSetter.setCreatedOn(older, NOW.minusSeconds(60));
    accounts.deleteById(issuer.getId());

    var page =
        service.householdShareCodes(identity(), householdId, PaginationFixture.firstRows(10));

    assertThat(page.rows())
        .extracting(row -> row.item().shareCode().getId())
        .containsExactly(newer.getId(), older.getId());
    assertThat(page.rows())
        .allSatisfy(row -> assertThat(row.item().issuedByDisplayName()).isNull());
  }

  @Test
  @DisplayName("Should list only the live codes when the Household also holds codes that ended")
  void shouldListOnlyLiveCodesWhenHouseholdAlsoHoldsCodesThatEnded() {
    var live = accepted(service.issueShareCode(identity(), householdId)).shareCode().shareCode();
    var expired = accepted(service.issueShareCode(identity(), householdId)).shareCode().shareCode();
    var withdrawn =
        accepted(service.issueShareCode(identity(), householdId)).shareCode().shareCode();
    var spent = accepted(service.issueShareCode(identity(), householdId)).shareCode().shareCode();
    expired.setExpiresAt(NOW);
    accepted(service.withdrawShareCode(identity(), withdrawn.getId()));
    shareCodes.trySpend(spent.getId(), NOW);

    var page =
        service.householdShareCodes(identity(), householdId, PaginationFixture.firstRows(10));

    assertThat(page.rows())
        .extracting(row -> row.item().shareCode().getId())
        .containsExactly(live.getId());
  }

  @Test
  @DisplayName("Should list nothing when the caller may not see the Household's codes")
  void shouldListNothingWhenCallerMayNotSeeHouseholdsCodes() {
    accepted(service.issueShareCode(identity(), householdId));
    authorization.denyAll();

    assertThat(
            service.householdShareCodes(identity(), householdId, PaginationFixture.firstRows(10)))
        .isEqualTo(ListPage.empty());
  }

  @Test
  @DisplayName("Should fail closed when authorization is unavailable for the list")
  void shouldFailClosedWhenAuthorizationIsUnavailableForList() {
    authorization.failWith(Decision.FailureCause.EVALUATION_ERROR);
    var caller = identity();
    var firstPage = PaginationFixture.firstRows(10);

    assertThatThrownBy(() -> service.householdShareCodes(caller, householdId, firstPage))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should name only the Household when a live code is looked up")
  void shouldNameOnlyHouseholdWhenLiveCodeIsLookedUp() {
    var issued = accepted(service.issueShareCode(identity(), householdId));
    var typed = issued.code().toLowerCase(Locale.ROOT).replace("-", " ");

    var householdName = accepted(service.lookUpShareCode(presenter(), command(typed)));

    assertThat(householdName).isEqualTo(households.findById(householdId).orElseThrow().getName());
    assertThat(attempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.metadata().kind()).isEqualTo(CredentialKind.SHARE_CODE);
              assertThat(attempt.metadata().accountId()).isEqualTo(PRESENTER_ID);
              assertThat(attempt.metadata().credentialId()).isNull();
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.SUCCEEDED);
            });
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(UnusableCode.class)
  @DisplayName("Should refuse in one way and count a failure when the code does not work")
  void shouldRefuseInOneWayAndCountFailureWhenCodeDoesNotWork(UnusableCode unusable) {
    var issued = accepted(service.issueShareCode(identity(), householdId));
    var typed = unusable.present(issued, shareCodes, service, identity());

    var refused = rejectionOf(service.lookUpShareCode(presenter(), command(typed)));

    assertThat(refused).isEqualTo(new ShareCodeRejections.ShareCodeRefused());
    assertThat(attempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.metadata().kind()).isEqualTo(CredentialKind.SHARE_CODE);
              assertThat(attempt.metadata().accountId()).isEqualTo(PRESENTER_ID);
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.FAILED);
            });
  }

  @Test
  @DisplayName("Should refuse to look up with the retry delay when the presenter is throttled")
  void shouldRefuseToLookUpWithRetryDelayWhenPresenterIsThrottled() {
    var issued = accepted(service.issueShareCode(identity(), householdId));
    attempts.rejectReservations(Duration.ofMinutes(3));
    var presenter = presenter();
    var presented = command(issued.code());

    assertThatThrownBy(() -> service.lookUpShareCode(presenter, presented))
        .isInstanceOf(TooManyCredentialAttemptsException.class)
        .extracting(failure -> ((RetryAfterAware) failure).retryAfter())
        .isEqualTo(Duration.ofMinutes(3));
  }

  @Test
  @DisplayName("Should refuse before counting an attempt when the presenter may not look codes up")
  void shouldRefuseBeforeCountingAttemptWhenPresenterMayNotLookCodesUp() {
    var issued = accepted(service.issueShareCode(identity(), householdId));
    authorization.denyAll();
    var presenter = presenter();
    var presented = command(issued.code());

    assertThatThrownBy(() -> service.lookUpShareCode(presenter, presented))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(attempts.attempts()).isEmpty();
  }

  @Test
  @DisplayName("Should keep the code live when it is looked up")
  void shouldKeepCodeLiveWhenItIsLookedUp() {
    var issued = accepted(service.issueShareCode(identity(), householdId));

    accepted(service.lookUpShareCode(presenter(), command(issued.code())));
    accepted(service.lookUpShareCode(presenter(), command(issued.code())));

    assertThat(issued.shareCode().shareCode().isLiveAt(NOW)).isTrue();
  }

  /** The ways a presented code fails to name a Household. */
  enum UnusableCode {
    WRONG {
      @Override
      String present(
          NewShareCode issued,
          FakeShareCodeRepository stored,
          ShareCodeService service,
          AuthenticatedIdentity admin) {
        return issued.code().equals("BBBB-BBBB") ? "CCCC-CCCC" : "BBBB-BBBB";
      }
    },
    MALFORMED {
      @Override
      String present(
          NewShareCode issued,
          FakeShareCodeRepository stored,
          ShareCodeService service,
          AuthenticatedIdentity admin) {
        return "AEIO-1234";
      }
    },
    SPENT {
      @Override
      String present(
          NewShareCode issued,
          FakeShareCodeRepository stored,
          ShareCodeService service,
          AuthenticatedIdentity admin) {
        stored.trySpend(issued.shareCode().shareCode().getId(), NOW);
        return issued.code();
      }
    },
    WITHDRAWN {
      @Override
      String present(
          NewShareCode issued,
          FakeShareCodeRepository stored,
          ShareCodeService service,
          AuthenticatedIdentity admin) {
        service.withdrawShareCode(admin, issued.shareCode().shareCode().getId());
        return issued.code();
      }
    },
    INVALIDATED {
      @Override
      String present(
          NewShareCode issued,
          FakeShareCodeRepository stored,
          ShareCodeService service,
          AuthenticatedIdentity admin) {
        stored.invalidateLiveIssuedBy(issued.shareCode().shareCode().getIssuerAccountId(), NOW);
        return issued.code();
      }
    },
    EXPIRED {
      @Override
      String present(
          NewShareCode issued,
          FakeShareCodeRepository stored,
          ShareCodeService service,
          AuthenticatedIdentity admin) {
        issued.shareCode().shareCode().setExpiresAt(NOW);
        return issued.code();
      }
    };

    abstract String present(
        NewShareCode issued,
        FakeShareCodeRepository stored,
        ShareCodeService service,
        AuthenticatedIdentity admin);
  }

  private static Decision<AuthorizationUnit> viewUnavailableOtherwiseDenied(
      Intent.UnitIntent intent) {
    return intent instanceof Intent.ViewShareCodes
        ? new Decision.Failed<>(Decision.FailureCause.EVALUATION_ERROR)
        : new Decision.Denied<>(Decision.DenialReason.POLICY);
  }

  private ShareCodeService serviceDrawingFrom(ShareCodes drawn) {
    return new ShareCodeService(
        authorization,
        shareCodes,
        households,
        accounts,
        new MutationTransactions(new FakeTransactionManager(), new ConstraintViolationTranslator()),
        attempts.gate(Clock.fixed(NOW, ZoneOffset.UTC)),
        new HouseholdShareCodesList(shareCodes, Clock.fixed(NOW, ZoneOffset.UTC)),
        drawn,
        CredentialCodeProperties.builder()
            .invitationTtl(Duration.ofDays(7))
            .passwordResetTtl(Duration.ofHours(1))
            .shareCodeTtl(SHARE_CODE_TTL)
            .replacementLockTimeout(Duration.ofSeconds(5))
            .build(),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static ShareCodeCommand command(String code) {
    return ShareCodeCommand.builder().code(code).ipAddress("192.0.2.40").build();
  }

  /** Someone of another Household, who holds the code. */
  private static AuthenticatedIdentity presenter() {
    return AuthenticatedIdentityFixture.accountScopedBuilder().accountId(PRESENTER_ID).build();
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
        List::getFirst);
  }
}
