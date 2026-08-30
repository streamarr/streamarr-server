package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.DeviceRegistration.DEVICE_REGISTRATION;
import static com.streamarr.server.jooq.generated.tables.EsnBlock.ESN_BLOCK;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.security.WithAccountContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Tag("IntegrationTest")
@DisplayName("Device Administration Pagination Integration Tests")
@WithAccountContext
class DeviceAdministrationPaginationIT extends AbstractIntegrationTest {

  @Autowired private DeviceRegistrationRepository registrationRepository;
  @Autowired private EsnBlockRepository esnBlockRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DSLContext dsl;

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("Should fetch a bounded active Device registration window by stable keyset")
  void shouldFetchBoundedActiveDeviceRegistrationWindowByStableKeyset() {
    var identity = authTestSupport.createAdminIdentity();
    var registrations = new ArrayList<DeviceRegistration>();
    try {
      for (var index = 0; index < 4; index++) {
        var registration =
            registrationRepository.saveAndFlush(
                DeviceRegistration.builder()
                    .esn("pagination-esn-" + UUID.randomUUID())
                    .displayName("Pagination TV " + index)
                    .householdId(identity.household().getId())
                    .authorizingAccountId(identity.account().getId())
                    .build());
        setCreatedOn(DEVICE_REGISTRATION, registration.getId(), index);
        registrations.add(registration);
      }

      var newest = registrations.getLast();
      var firstWindow =
          registrationRepository.findPageByHouseholdIdAndStatus(
              identity.household().getId(), DeviceRegistrationStatus.ACTIVE, forwardOptions(null));
      var afterNewest =
          registrationRepository.findPageByHouseholdIdAndStatus(
              identity.household().getId(),
              DeviceRegistrationStatus.ACTIVE,
              forwardOptions(newest.getId()));

      assertThat(firstWindow)
          .extracting(DeviceRegistration::getId)
          .containsExactly(registrations.get(3).getId(), registrations.get(2).getId());
      assertThat(afterNewest)
          .extracting(DeviceRegistration::getId)
          .containsExactly(
              registrations.get(3).getId(),
              registrations.get(2).getId(),
              registrations.get(1).getId());
    } finally {
      registrationRepository.deleteAllById(
          registrations.stream().map(DeviceRegistration::getId).toList());
      authTestSupport.deleteIdentity(identity);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("Should fetch a bounded Household ESN block window by stable keyset")
  void shouldFetchBoundedHouseholdEsnBlockWindowByStableKeyset() {
    var identity = authTestSupport.createAdminIdentity();
    var blocks = new ArrayList<EsnBlock>();
    try {
      for (var index = 0; index < 4; index++) {
        var block =
            esnBlockRepository.saveAndFlush(
                EsnBlock.builder()
                    .esn("pagination-block-" + UUID.randomUUID())
                    .householdId(identity.household().getId())
                    .reason("pagination test")
                    .build());
        setCreatedOn(ESN_BLOCK, block.getId(), index);
        blocks.add(block);
      }

      var newest = blocks.getLast();
      var firstWindow =
          esnBlockRepository.findPageByHouseholdId(
              identity.household().getId(), forwardOptions(null));
      var afterNewest =
          esnBlockRepository.findPageByHouseholdId(
              identity.household().getId(), forwardOptions(newest.getId()));

      assertThat(firstWindow)
          .extracting(EsnBlock::getId)
          .containsExactly(blocks.get(3).getId(), blocks.get(2).getId());
      assertThat(afterNewest)
          .extracting(EsnBlock::getId)
          .containsExactly(blocks.get(3).getId(), blocks.get(2).getId(), blocks.get(1).getId());
    } finally {
      esnBlockRepository.deleteAllById(blocks.stream().map(EsnBlock::getId).toList());
      authTestSupport.deleteIdentity(identity);
    }
  }

  private <R extends Record> void setCreatedOn(Table<R> table, UUID id, int secondsAfterEpoch) {
    dsl.update(table)
        .set(table.field("created_on", OffsetDateTime.class), timestamp(secondsAfterEpoch))
        .where(table.field("id", UUID.class).eq(id))
        .execute();
  }

  private static OffsetDateTime timestamp(int secondsAfterEpoch) {
    return Instant.parse("2026-08-21T12:00:00Z")
        .plusSeconds(secondsAfterEpoch)
        .atOffset(ZoneOffset.UTC);
  }

  private static KeysetPaginationOptions forwardOptions(UUID cursorId) {
    return new KeysetPaginationOptions(
        cursorId,
        PaginationOptions.builder()
            .paginationDirection(PaginationDirection.FORWARD)
            .cursor(Optional.empty())
            .limit(1)
            .build());
  }
}
