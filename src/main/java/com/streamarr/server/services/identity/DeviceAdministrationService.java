package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.repositories.auth.EsnBlockRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.DeviceRegistrationLifecycle;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Device administration (ADR 0024 §Devices): revoking a registration ends its sessions with it, and
 * an ESN block first revokes every matching registration and device session in the same transaction
 * — T10 refuses any block that would leave either behind. A server-wide block is a
 * fresh-reauthenticated ServerAdmin action; every operation here writes one audit record.
 */
@Service
@RequiredArgsConstructor
public class DeviceAdministrationService {

  private static final String UQ_BLOCK_SCOPE = "uq_esn_block_scope";

  private final AuthorizationService authorizationService;
  private final DeviceRegistrationRepository registrationRepository;
  private final EsnBlockRepository esnBlockRepository;
  private final HouseholdRepository householdRepository;
  private final DeviceRegistrationLifecycle registrationLifecycle;
  private final SecurityAuditEventRepository securityAuditEventRepository;
  private final MutationTransactions mutationTransactions;
  private final Clock clock;

  public Outcome<UUID, DeviceRejections.Revoke> revokeDeviceRegistration(
      AuthenticatedIdentity identity, UUID registrationId) {
    var refusal =
        refusalOf(
            identity,
            new Intent.RevokeDeviceRegistration(registrationId),
            () -> mayViewRegistration(identity, registrationId),
            DeviceRejections.RegistrationNotFound::new,
            null);
    if (refusal.isPresent()) {
      return Outcome.rejected((DeviceRejections.Revoke) refusal.get());
    }
    return mutationTransactions.write(
        () -> {
          if (!registrationLifecycle.revoke(
              registrationId, identity.accountId(), "revoked by administrator", clock.instant())) {
            throw new MutationRejection(new DeviceRejections.RegistrationNotActive());
          }
          audit(identity, "revokeDeviceRegistration", "registrationId", registrationId, null);
          return registrationId;
        },
        _ -> Optional.empty());
  }

  public Outcome<EsnBlock, DeviceRejections.Block> blockEsn(
      AuthenticatedIdentity identity, UUID householdId, String esn, String reason) {
    if (isBlank(esn)) {
      return Outcome.rejected(new DeviceRejections.EsnRequired());
    }
    if (isBlank(reason)) {
      return Outcome.rejected(new DeviceRejections.ReasonRequired());
    }
    var refusal =
        refusalOf(
            identity,
            new Intent.BlockEsn(householdId),
            () -> mayViewDevices(identity, householdId),
            DeviceRejections.HouseholdNotFound::new,
            null);
    if (refusal.isPresent()) {
      return Outcome.rejected((DeviceRejections.Block) refusal.get());
    }
    if (householdRepository.findById(householdId).isEmpty()) {
      return Outcome.rejected(new DeviceRejections.HouseholdNotFound());
    }
    return writeBlock(identity, householdId, esn.strip(), reason, "blockEsn");
  }

  public Outcome<EsnBlock, DeviceRejections.BlockServerWide> blockEsnServerWide(
      AuthenticatedIdentity identity, String esn, String reason) {
    if (isBlank(esn)) {
      return Outcome.rejected(new DeviceRejections.EsnRequired());
    }
    if (isBlank(reason)) {
      return Outcome.rejected(new DeviceRejections.ReasonRequired());
    }
    var refusal =
        refusalOf(
            identity,
            new Intent.BlockEsnServerWide(),
            () -> false,
            () -> {
              throw new AccessDeniedException("Not allowed.");
            },
            DeviceRejections.ReauthenticationRequired::new);
    if (refusal.isPresent()) {
      return Outcome.rejected((DeviceRejections.BlockServerWide) refusal.get());
    }
    return writeBlock(identity, null, esn.strip(), reason, "blockEsnServerWide");
  }

  public Outcome<String, DeviceRejections.Unblock> unblockEsn(
      AuthenticatedIdentity identity, UUID householdId, String esn) {
    if (isBlank(esn)) {
      return Outcome.rejected(new DeviceRejections.EsnRequired());
    }
    var refusal =
        refusalOf(
            identity,
            new Intent.UnblockEsn(householdId),
            () -> mayViewDevices(identity, householdId),
            DeviceRejections.HouseholdNotFound::new,
            null);
    if (refusal.isPresent()) {
      return Outcome.rejected((DeviceRejections.Unblock) refusal.get());
    }
    return removeBlock(
        identity,
        esn.strip(),
        () -> esnBlockRepository.findByEsnAndHouseholdId(esn.strip(), householdId),
        "unblockEsn");
  }

  public Outcome<String, DeviceRejections.UnblockServerWide> unblockEsnServerWide(
      AuthenticatedIdentity identity, String esn) {
    if (isBlank(esn)) {
      return Outcome.rejected(new DeviceRejections.EsnRequired());
    }
    authorizationService.requireAllowed(identity, new Intent.UnblockEsnServerWide());
    return removeBlock(
        identity,
        esn.strip(),
        () -> esnBlockRepository.findByEsnAndHouseholdIdIsNull(esn.strip()),
        "unblockEsnServerWide");
  }

  /** ACTIVE registrations of the Household, for whoever may view its device administration. */
  public List<DeviceRegistration> householdDevices(
      AuthenticatedIdentity identity, UUID householdId) {
    if (!mayReadDevices(identity, new Intent.ViewDeviceAdministration(householdId))) {
      return List.of();
    }
    return registrationRepository.findByHouseholdIdAndStatus(
        householdId, DeviceRegistrationStatus.ACTIVE);
  }

  public List<EsnBlock> esnBlocks(AuthenticatedIdentity identity, UUID householdId) {
    if (!mayReadDevices(identity, new Intent.ViewDeviceAdministration(householdId))) {
      return List.of();
    }
    return esnBlockRepository.findByHouseholdId(householdId);
  }

  public List<EsnBlock> serverEsnBlocks(AuthenticatedIdentity identity) {
    if (!mayReadDevices(identity, new Intent.ViewServerDeviceAdministration())) {
      return List.of();
    }
    return esnBlockRepository.findByHouseholdIdIsNull();
  }

  private <R> Outcome<EsnBlock, R> writeBlock(
      AuthenticatedIdentity identity,
      UUID householdId,
      String esn,
      String reason,
      String operation) {
    var now = clock.instant();
    return mutationTransactions.write(
        () -> {
          // The block must leave nothing behind (T10): matching registrations and their
          // sessions fall in the same transaction the block row is written in.
          registrationLifecycle.revokeAllByEsn(
              esn, householdId, identity.accountId(), "ESN blocked", now);
          var block =
              esnBlockRepository.saveAndFlush(
                  EsnBlock.builder().esn(esn).householdId(householdId).reason(reason).build());
          audit(identity, operation, "esn", null, reason);
          return block;
        },
        constraint -> alreadyBlocked(constraint));
  }

  @SuppressWarnings("unchecked")
  private static <R> Optional<R> alreadyBlocked(String constraint) {
    return UQ_BLOCK_SCOPE.equals(constraint)
        ? Optional.of((R) new DeviceRejections.AlreadyBlocked())
        : Optional.empty();
  }

  private <R> Outcome<String, R> removeBlock(
      AuthenticatedIdentity identity,
      String esn,
      Supplier<Optional<EsnBlock>> block,
      String operation) {
    return mutationTransactions.write(
        () -> {
          var found = block.get();
          if (found.isEmpty()) {
            throw new MutationRejection(new DeviceRejections.BlockNotFound());
          }
          esnBlockRepository.delete(found.get());
          esnBlockRepository.flush();
          audit(identity, operation, "esn", null, null);
          return esn;
        },
        _ -> Optional.empty());
  }

  private boolean mayViewDevices(AuthenticatedIdentity identity, UUID householdId) {
    return authorizationService.decide(identity, new Intent.ViewDeviceAdministration(householdId))
        instanceof Decision.Allowed<?>;
  }

  private boolean mayReadDevices(AuthenticatedIdentity identity, Intent<?> intent) {
    return switch (authorizationService.decide(identity, intent)) {
      case Decision.Allowed<?> _ -> true;
      case Decision.Denied<?> _ -> false;
      case Decision.Failed<?> _ -> throw new AuthorizationUnavailableException();
    };
  }

  private boolean mayViewRegistration(AuthenticatedIdentity identity, UUID registrationId) {
    return registrationRepository
        .findById(registrationId)
        .map(
            registration ->
                registration.getHouseholdId() != null
                    && mayViewDevices(identity, registration.getHouseholdId()))
        .orElse(false);
  }

  private void audit(
      AuthenticatedIdentity identity,
      String operation,
      String resourceName,
      UUID resourceId,
      String reason) {
    var entry =
        SecurityAuditEntry.builder()
            .operation(operation)
            .actorAccountId(identity.accountId())
            .reason(reason);
    if (resourceId != null) {
      entry.resource(resourceName, resourceId);
    }
    securityAuditEventRepository.append(entry.build());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private Optional<Object> refusalOf(
      AuthenticatedIdentity identity,
      Intent<?> intent,
      BooleanSupplier mayView,
      Supplier<Object> denied,
      Supplier<Object> reauthenticationRequired) {
    return switch (authorizationService.decide(identity, intent)) {
      case Decision.Allowed<?> _ -> Optional.empty();
      case Decision.Failed<?> _ -> throw new AuthorizationUnavailableException();
      case Decision.Denied<?>(var reason) ->
          switch (reason) {
            case REAUTHENTICATION_REQUIRED -> Optional.of(reauthenticationRequired.get());
            case POLICY -> {
              if (mayView.getAsBoolean()) {
                throw new AccessDeniedException("Not allowed.");
              }
              yield Optional.of(denied.get());
            }
          };
    };
  }
}
