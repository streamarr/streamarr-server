package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.DeviceRegistration.DEVICE_REGISTRATION;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Household device paging")
class HouseholdDevicesPagedListIT
    extends PagedListContract<DeviceRegistration, AuthTestSupport.TestIdentity> {

  private static final Instant NEWEST = Instant.parse("2026-10-20T12:00:00Z");

  @Autowired private DeviceAdministrationService deviceAdministration;
  @Autowired private DeviceRegistrationRepository registrations;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private AuthTestSupport auth;

  @Override
  protected AuthTestSupport.TestIdentity createScope() {
    return auth.createAdminIdentity();
  }

  @Override
  protected void deleteScope(AuthTestSupport.TestIdentity admin) {
    auth.deleteIdentity(admin);
  }

  @Override
  protected UUID add(AuthTestSupport.TestIdentity admin, int rank) {
    var registration =
        registrations.saveAndFlush(
            DeviceRegistration.builder()
                .esn("paged-esn-" + UUID.randomUUID())
                .displayName("Paged TV")
                .householdId(admin.household().getId())
                .authorizingAccountId(admin.account().getId())
                .build());
    auditColumns.overwriteCreatedOn(
        DEVICE_REGISTRATION,
        DEVICE_REGISTRATION.ID.eq(registration.getId()),
        NEWEST.minusSeconds(rank));
    return registration.getId();
  }

  @Override
  protected void remove(AuthTestSupport.TestIdentity admin, UUID item) {
    var outcome = deviceAdministration.revokeDeviceRegistration(auth.identityOf(admin), item);
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
  }

  @Override
  protected ListPage<DeviceRegistration> page(
      AuthTestSupport.TestIdentity admin, PageRequest request) {
    return deviceAdministration.householdDevices(
        auth.identityOf(admin), admin.household().getId(), request);
  }

  @Override
  protected UUID idOf(DeviceRegistration registration) {
    return registration.getId();
  }

  @Override
  protected SortDirection tieDirection() {
    return SortDirection.DESC;
  }

  @Override
  protected boolean sortValuesCanTie() {
    return true;
  }

  @Override
  protected Optional<Position> positionBeforeArrangedItems(AuthTestSupport.TestIdentity admin) {
    return Optional.empty();
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(AuthTestSupport.TestIdentity admin) {
    return Optional.empty();
  }
}
