package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public class FakeAccountInvitationRepository extends FakeJpaRepository<AccountInvitation>
    implements AccountInvitationRepository {

  @Override
  public List<AccountInvitation> findAdministrationPage(MediaPaginationOptions options) {
    var reverse =
        options.getPaginationOptions().getPaginationDirection() == PaginationDirection.REVERSE;
    var order =
        Comparator.comparing(AccountInvitation::getCreatedOn)
            .reversed()
            .thenComparing(AccountInvitation::getId);
    if (reverse) {
      order = order.reversed();
    }

    var invitations =
        new ArrayList<>(
            database.values().stream()
                .filter(invitation -> isAtOrAfterCursor(invitation, options, reverse))
                .sorted(order)
                .limit(
                    options.getPaginationOptions().getLimit()
                        + (options.getCursorId().isPresent() ? 2 : 1))
                .toList());
    if (reverse) {
      Collections.reverse(invitations);
    }

    return invitations;
  }

  private boolean isAtOrAfterCursor(
      AccountInvitation invitation, MediaPaginationOptions options, boolean reverse) {
    if (options.getCursorId().isEmpty()) {
      return true;
    }

    var cursorId = options.getCursorId().orElseThrow();
    if (invitation.getId().equals(cursorId)) {
      return true;
    }

    var cursorCreatedOn =
        Instant.parse(options.getMediaFilter().getPreviousSortFieldValue().toString());
    var createdOnComparison = invitation.getCreatedOn().compareTo(cursorCreatedOn);
    if (createdOnComparison != 0) {
      return reverse ? createdOnComparison > 0 : createdOnComparison < 0;
    }

    var idComparison = invitation.getId().compareTo(cursorId);
    return reverse ? idComparison < 0 : idComparison > 0;
  }

  @Override
  public void lockInvitationIssuanceForRecipientEmail(String recipientEmail) {
    // Unit tests are single-threaded; PostgreSQL integration tests prove cross-instance locking.
  }

  @Override
  public Optional<AccountInvitation> findByPublicId(String publicId) {
    return database.values().stream()
        .filter(invitation -> publicId.equals(invitation.getPublicId()))
        .findFirst();
  }

  @Override
  public boolean markAcceptedIfPendingAndUnexpired(UUID invitationId, Instant now) {
    return markIfPendingAndUnexpired(invitationId, AccountInvitationStatus.ACCEPTED, now);
  }

  @Override
  public boolean markDeclinedIfPendingAndUnexpired(UUID invitationId, Instant now) {
    return markIfPendingAndUnexpired(invitationId, AccountInvitationStatus.DECLINED, now);
  }

  @Override
  public boolean markCanceledIfPendingAndUnexpired(UUID invitationId, Instant now) {
    return markIfPendingAndUnexpired(invitationId, AccountInvitationStatus.CANCELED, now);
  }

  private boolean markIfPendingAndUnexpired(
      UUID invitationId, AccountInvitationStatus target, Instant now) {
    var decided =
        findById(invitationId)
            .filter(invitation -> invitation.getStatus() == AccountInvitationStatus.PENDING)
            .filter(invitation -> invitation.getExpiresAt().isAfter(now));
    decided.ifPresent(
        invitation -> {
          invitation.setStatus(target);
          invitation.setDecidedAt(now);
        });
    return decided.isPresent();
  }

  @Override
  public int invalidatePendingInvitationsForRecipientEmail(
      String recipientEmail, String reason, Instant now) {
    return invalidate(
        invitation ->
            invitation.getRecipientEmail().equalsIgnoreCase(recipientEmail)
                && invitation.getExpiresAt().isAfter(now),
        reason,
        now);
  }

  @Override
  public int invalidatePendingInvitationsIssuedBy(
      UUID issuerAccountId, String reason, Instant now) {
    return invalidate(
        invitation -> issuerAccountId.equals(invitation.getIssuerAccountId()), reason, now);
  }

  @Override
  public int expirePendingInvitationsForRecipientEmail(String recipientEmail, Instant now) {
    var expired =
        database.values().stream()
            .filter(invitation -> invitation.getStatus() == AccountInvitationStatus.PENDING)
            .filter(invitation -> invitation.getRecipientEmail().equalsIgnoreCase(recipientEmail))
            .filter(invitation -> !invitation.getExpiresAt().isAfter(now))
            .toList();
    expired.forEach(invitation -> invitation.setStatus(AccountInvitationStatus.EXPIRED));
    return expired.size();
  }

  private int invalidate(Predicate<AccountInvitation> scope, String reason, Instant now) {
    var affected =
        database.values().stream()
            .filter(invitation -> invitation.getStatus() == AccountInvitationStatus.PENDING)
            .filter(scope)
            .toList();
    affected.forEach(
        invitation -> {
          invitation.setStatus(AccountInvitationStatus.INVALIDATED);
          invitation.setInvalidationReason(reason);
          invitation.setDecidedAt(now);
        });
    return affected.size();
  }
}
