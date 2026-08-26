package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.AccountInvitation.ACCOUNT_INVITATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.exceptions.InvalidPaginationCursorException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import org.jooq.DSLContext;
import org.jooq.SortOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Account Invitation Pagination Integration Tests")
class AccountInvitationPaginationIT extends AbstractIntegrationTest {

  private static final Instant CREATED_ON = Instant.parse("2026-08-26T16:00:00Z");

  @Autowired private AdministrationQueryService administrationQueryService;
  @Autowired private CredentialIssuanceService credentialIssuanceService;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity serverAdmin;
  private AuthenticatedIdentity identity;

  @BeforeEach
  void setUp() {
    serverAdmin = authTestSupport.createAdminIdentity();
    identity =
        AuthenticatedIdentity.builder()
            .accountId(serverAdmin.account().getId())
            .authSessionId(serverAdmin.session().getId())
            .scope(TokenScope.ACCOUNT)
            .householdId(serverAdmin.household().getId())
            .householdRole(serverAdmin.account().getHouseholdRole())
            .contextHouseholdId(serverAdmin.household().getId())
            .build();
  }

  @AfterEach
  void tearDown() {
    invitationRepository.deleteAll();
    authTestSupport.deleteIdentity(serverAdmin);
  }

  @Test
  @DisplayName("Should seek by UUID without gaps when invitations share a creation time")
  void shouldSeekByUuidWithoutGapsWhenInvitationsShareCreationTime() {
    var invitations =
        List.of(
            issue("one@example.com"),
            issue("two@example.com"),
            issue("three@example.com"),
            issue("four@example.com"));
    setCreatedOn(invitations, CREATED_ON);
    var invitationIds = invitations.stream().map(AccountInvitation::getId).toList();
    var expectedIds =
        dsl.select(ACCOUNT_INVITATION.ID)
            .from(ACCOUNT_INVITATION)
            .where(ACCOUNT_INVITATION.ID.in(invitationIds))
            .orderBy(ACCOUNT_INVITATION.ID.asc())
            .fetch(ACCOUNT_INVITATION.ID);

    var first = administrationQueryService.accountInvitations(identity, forwardOptions(2));
    var second =
        administrationQueryService.accountInvitations(
            identity, forwardContinuation(2, first.items().getLast().item()));

    assertThat(first.items())
        .extracting(item -> item.item().getId())
        .containsExactly(expectedIds.get(0), expectedIds.get(1));
    assertThat(first.hasNextPage()).isTrue();
    assertThat(first.hasPreviousPage()).isFalse();
    assertThat(second.items())
        .extracting(item -> item.item().getId())
        .containsExactly(expectedIds.get(2), expectedIds.get(3));
    assertThat(second.hasNextPage()).isFalse();
    assertThat(second.hasPreviousPage()).isTrue();
  }

  @Test
  @DisplayName("Should return the requested last invitations when a before cursor is given")
  void shouldReturnRequestedLastInvitationsWhenBeforeCursorIsGiven() {
    var newest = issue("newest@example.com");
    var second = issue("second@example.com");
    var third = issue("third@example.com");
    var oldest = issue("oldest@example.com");
    setCreatedOn(List.of(newest), CREATED_ON.plusSeconds(3));
    setCreatedOn(List.of(second), CREATED_ON.plusSeconds(2));
    setCreatedOn(List.of(third), CREATED_ON.plusSeconds(1));
    setCreatedOn(List.of(oldest), CREATED_ON);
    var oldestPageItem =
        administrationQueryService.accountInvitations(identity, forwardOptions(10)).items().stream()
            .filter(item -> item.item().getId().equals(oldest.getId()))
            .findFirst()
            .orElseThrow();

    var page =
        administrationQueryService.accountInvitations(
            identity, backwardContinuation(2, oldestPageItem.item()));

    assertThat(page.items())
        .extracting(item -> item.item().getRecipientEmail())
        .containsExactly("second@example.com", "third@example.com");
    assertThat(page.hasNextPage()).isTrue();
    assertThat(page.hasPreviousPage()).isTrue();
  }

  @Test
  @DisplayName("Should reject an invitation cursor when its creation time is missing")
  void shouldRejectInvitationCursorWhenCreationTimeIsMissing() {
    var invitation = issue("missing-sort@example.com");
    var cursor = continuation(invitation.getId(), null);

    assertThatThrownBy(() -> administrationQueryService.accountInvitations(identity, cursor))
        .isInstanceOf(InvalidPaginationCursorException.class)
        .hasMessage("Cursor sort value is required.");
  }

  @Test
  @DisplayName("Should reject an invitation cursor when its creation time is invalid")
  void shouldRejectInvitationCursorWhenCreationTimeIsInvalid() {
    var invitation = issue("invalid-sort@example.com");
    var cursor = continuation(invitation.getId(), "not-an-instant");

    assertThatThrownBy(() -> administrationQueryService.accountInvitations(identity, cursor))
        .isInstanceOf(InvalidPaginationCursorException.class)
        .hasMessage("Cursor sort value is invalid.");
  }

  private AccountInvitation issue(String email) {
    var outcome =
        credentialIssuanceService.issueAccountInvitation(
            identity,
            IssueInvitationCommand.builder()
                .recipientEmail(email)
                .householdId(serverAdmin.household().getId())
                .householdRole(HouseholdRole.MEMBER)
                .profileName(email)
                .profileKind(ProfileKind.ADULT)
                .build());
    return accepted(outcome).invitation();
  }

  private void setCreatedOn(List<AccountInvitation> invitations, Instant createdOn) {
    var ids = invitations.stream().map(AccountInvitation::getId).toList();
    dsl.update(ACCOUNT_INVITATION)
        .set(ACCOUNT_INVITATION.CREATED_ON, createdOn.atOffset(ZoneOffset.UTC))
        .where(ACCOUNT_INVITATION.ID.in(ids))
        .execute();
  }

  private static MediaPaginationOptions forwardOptions(int limit) {
    return MediaPaginationOptions.builder()
        .paginationOptions(
            PaginationOptions.builder()
                .cursor(Optional.empty())
                .paginationDirection(PaginationDirection.FORWARD)
                .limit(limit)
                .build())
        .mediaFilter(invitationFilter())
        .build();
  }

  private static MediaPaginationOptions forwardContinuation(
      int limit, AccountInvitation invitation) {
    return continuation(
        ContinuationSpec.builder()
            .invitationId(invitation.getId())
            .sortValue(invitation.getCreatedOn())
            .direction(PaginationDirection.FORWARD)
            .limit(limit)
            .build());
  }

  private static MediaPaginationOptions backwardContinuation(
      int limit, AccountInvitation invitation) {
    return continuation(
        ContinuationSpec.builder()
            .invitationId(invitation.getId())
            .sortValue(invitation.getCreatedOn())
            .direction(PaginationDirection.REVERSE)
            .limit(limit)
            .build());
  }

  private static MediaPaginationOptions continuation(UUID invitationId, Object sortValue) {
    return continuation(
        ContinuationSpec.builder()
            .invitationId(invitationId)
            .sortValue(sortValue)
            .direction(PaginationDirection.FORWARD)
            .limit(2)
            .build());
  }

  private static MediaPaginationOptions continuation(ContinuationSpec spec) {
    return MediaPaginationOptions.builder()
        .cursorId(spec.invitationId())
        .paginationOptions(
            PaginationOptions.builder()
                .cursor(Optional.of("cursor"))
                .paginationDirection(spec.direction())
                .limit(spec.limit())
                .build())
        .mediaFilter(
            invitationFilter().toBuilder().previousSortFieldValue(spec.sortValue()).build())
        .build();
  }

  private static MediaFilter invitationFilter() {
    return MediaFilter.builder().sortBy(OrderMediaBy.ADDED).sortDirection(SortOrder.DESC).build();
  }

  private static CredentialIssuanceService.IssuedInvitation accepted(
      Outcome<CredentialIssuanceService.IssuedInvitation, ?> outcome) {
    return outcome.fold(
        value -> value,
        rejections -> {
          throw new AssertionError("expected acceptance but got " + rejections);
        });
  }

  @Builder
  private record ContinuationSpec(
      UUID invitationId, Object sortValue, PaginationDirection direction, int limit) {}
}
