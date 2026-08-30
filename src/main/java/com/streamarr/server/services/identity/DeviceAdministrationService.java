package com.streamarr.server.services.identity;

import com.streamarr.server.domain.BaseAuditableEntity;
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
import com.streamarr.server.services.auth.Esn;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationService;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Device administration (ADR 0024 §Devices): revoking a registration ends its sessions with it, and
 * an ESN block first revokes every matching registration and device session in the same
 * transaction. A server-wide block is a fresh-reauthenticated ServerAdmin action; every operation
 * here writes one audit record.
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
  private final PaginationService paginationService;
  private final Clock clock;

  public Outcome<UUID, DeviceRejections.Revoke> revokeDeviceRegistration(
      AuthenticatedIdentity identity, UUID registrationId) {
    Optional<DeviceRejections.Revoke> refusal =
        refusalOf(
            identity,
            new Intent.RevokeDeviceRegistration(registrationId),
            () -> mayViewRegistration(identity, registrationId),
            DeviceRejections.RegistrationNotFound::new,
            Optional.empty());
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
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
    Optional<DeviceRejections.Block> invalidEsn =
        esnRejection(esn, DeviceRejections.EsnRequired::new, DeviceRejections.EsnInvalid::new);
    if (invalidEsn.isPresent()) {
      return Outcome.rejected(invalidEsn.get());
    }

    if (isBlank(reason)) {
      return Outcome.rejected(new DeviceRejections.ReasonRequired());
    }

    Optional<DeviceRejections.Block> refusal =
        refusalOf(
            identity,
            new Intent.BlockEsn(householdId),
            () -> mayViewDevices(identity, householdId),
            DeviceRejections.HouseholdNotFound::new,
            Optional.empty());
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    if (householdRepository.findById(householdId).isEmpty()) {
      return Outcome.rejected(new DeviceRejections.HouseholdNotFound());
    }

    return writeBlock(
        BlockWrite.builder()
            .identity(identity)
            .householdId(householdId)
            .esn(Esn.normalize(esn))
            .reason(reason)
            .operation("blockEsn")
            .build(),
        DeviceRejections.AlreadyBlocked::new);
  }

  public Outcome<EsnBlock, DeviceRejections.BlockServerWide> blockEsnServerWide(
      AuthenticatedIdentity identity, String esn, String reason) {
    Optional<DeviceRejections.BlockServerWide> invalidEsn =
        esnRejection(esn, DeviceRejections.EsnRequired::new, DeviceRejections.EsnInvalid::new);
    if (invalidEsn.isPresent()) {
      return Outcome.rejected(invalidEsn.get());
    }

    if (isBlank(reason)) {
      return Outcome.rejected(new DeviceRejections.ReasonRequired());
    }

    Optional<DeviceRejections.BlockServerWide> refusal =
        refusalOf(
            identity,
            new Intent.BlockEsnServerWide(),
            () -> false,
            () -> {
              throw new AccessDeniedException("Not allowed.");
            },
            Optional.of(DeviceRejections.ReauthenticationRequired::new));
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    return writeBlock(
        BlockWrite.builder()
            .identity(identity)
            .esn(Esn.normalize(esn))
            .reason(reason)
            .operation("blockEsnServerWide")
            .build(),
        DeviceRejections.AlreadyBlocked::new);
  }

  public Outcome<String, DeviceRejections.Unblock> unblockEsn(
      AuthenticatedIdentity identity, UUID householdId, String esn) {
    Optional<DeviceRejections.Unblock> invalidEsn =
        esnRejection(esn, DeviceRejections.EsnRequired::new, DeviceRejections.EsnInvalid::new);
    if (invalidEsn.isPresent()) {
      return Outcome.rejected(invalidEsn.get());
    }

    var normalizedEsn = Esn.normalize(esn);

    Optional<DeviceRejections.Unblock> refusal =
        refusalOf(
            identity,
            new Intent.UnblockEsn(householdId),
            () -> mayViewDevices(identity, householdId),
            DeviceRejections.HouseholdNotFound::new,
            Optional.empty());
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    return removeBlock(
        identity,
        normalizedEsn,
        () -> esnBlockRepository.findByEsnAndHouseholdId(normalizedEsn, householdId),
        "unblockEsn");
  }

  public Outcome<String, DeviceRejections.UnblockServerWide> unblockEsnServerWide(
      AuthenticatedIdentity identity, String esn) {
    Optional<DeviceRejections.UnblockServerWide> invalidEsn =
        esnRejection(esn, DeviceRejections.EsnRequired::new, DeviceRejections.EsnInvalid::new);
    if (invalidEsn.isPresent()) {
      return Outcome.rejected(invalidEsn.get());
    }

    var normalizedEsn = Esn.normalize(esn);
    authorizationService.requireAllowed(identity, new Intent.UnblockEsnServerWide());
    return removeBlock(
        identity,
        normalizedEsn,
        () -> esnBlockRepository.findByEsnAndHouseholdIdIsNull(normalizedEsn),
        "unblockEsnServerWide");
  }

  /** ACTIVE registrations of the Household, for whoever may view its device administration. */
  public MediaPage<DeviceRegistration> householdDevices(
      AuthenticatedIdentity identity, UUID householdId, KeysetPaginationOptions options) {
    if (!mayReadDevices(identity, new Intent.ViewDeviceAdministration(householdId))) {
      return page(List.<DeviceRegistration>of(), options);
    }

    return page(
        registrationRepository.findPageByHouseholdIdAndStatus(
            householdId, DeviceRegistrationStatus.ACTIVE, options),
        options);
  }

  public MediaPage<EsnBlock> esnBlocks(
      AuthenticatedIdentity identity, UUID householdId, KeysetPaginationOptions options) {
    if (!mayReadDevices(identity, new Intent.ViewDeviceAdministration(householdId))) {
      return page(List.<EsnBlock>of(), options);
    }

    return page(esnBlockRepository.findPageByHouseholdId(householdId, options), options);
  }

  public MediaPage<EsnBlock> serverEsnBlocks(
      AuthenticatedIdentity identity, KeysetPaginationOptions options) {
    if (!mayReadDevices(identity, new Intent.ViewServerDeviceAdministration())) {
      return page(List.<EsnBlock>of(), options);
    }

    return page(esnBlockRepository.findPageByHouseholdIdIsNull(options), options);
  }

  private <T extends BaseAuditableEntity<T>> MediaPage<T> page(
      List<T> values, KeysetPaginationOptions options) {
    var items = values.stream().map(value -> new PageItem<>(value, value.getCreatedOn())).toList();
    return paginationService.buildMediaPage(
        items, options.getPaginationOptions(), options.getCursorId());
  }

  private <R> Outcome<EsnBlock, R> writeBlock(
      BlockWrite command, Supplier<? extends R> alreadyBlocked) {
    var now = clock.instant();
    return mutationTransactions.write(
        () -> {
          registrationLifecycle.revokeAllByEsn(
              command.esn(),
              command.householdId(),
              command.identity().accountId(),
              "ESN blocked",
              now);
          var block =
              esnBlockRepository.saveAndFlush(
                  EsnBlock.builder()
                      .esn(command.esn())
                      .householdId(command.householdId())
                      .reason(command.reason())
                      .build());
          audit(command.identity(), command.operation(), "esn", null, command.reason());
          return block;
        },
        constraint -> alreadyBlocked(constraint, alreadyBlocked));
  }

  private static <R> Optional<R> alreadyBlocked(
      String constraint, Supplier<? extends R> rejection) {
    return UQ_BLOCK_SCOPE.equals(constraint) ? Optional.of(rejection.get()) : Optional.empty();
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
        instanceof Decision.Allowed<AuthorizationUnit>;
  }

  private boolean mayReadDevices(AuthenticatedIdentity identity, Intent.UnitIntent intent) {
    return switch (authorizationService.decide(identity, intent)) {
      case Decision.Allowed<AuthorizationUnit> _ -> true;
      case Decision.Denied<AuthorizationUnit> _ -> false;
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
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

  private static <R> Optional<R> esnRejection(
      String esn, Supplier<? extends R> required, Supplier<? extends R> invalid) {
    if (Esn.isMissing(esn)) {
      return Optional.of(required.get());
    }

    if (Esn.exceedsMaximum(Esn.normalize(esn))) {
      return Optional.of(invalid.get());
    }

    return Optional.empty();
  }

  private <R> Optional<R> refusalOf(
      AuthenticatedIdentity identity,
      Intent.UnitIntent intent,
      BooleanSupplier mayView,
      Supplier<? extends R> denied,
      Optional<? extends Supplier<? extends R>> reauthenticationRequired) {
    return switch (authorizationService.decide(identity, intent)) {
      case Decision.Allowed<AuthorizationUnit> _ -> Optional.empty();
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
      case Decision.Denied<AuthorizationUnit>(var reason) ->
          switch (reason) {
            case REAUTHENTICATION_REQUIRED ->
                Optional.of(
                    reauthenticationRequired
                        .orElseThrow(AuthorizationUnavailableException::new)
                        .get());
            case POLICY -> {
              if (mayView.getAsBoolean()) {
                throw new AccessDeniedException("Not allowed.");
              }

              yield Optional.of(denied.get());
            }
          };
    };
  }

  @Builder
  private record BlockWrite(
      @NonNull AuthenticatedIdentity identity,
      UUID householdId,
      @NonNull String esn,
      @NonNull String reason,
      @NonNull String operation) {}
}
