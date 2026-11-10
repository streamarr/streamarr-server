package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.HouseholdRegistrationFilter;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValueKind;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.services.pagination.PagedList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The ACTIVE device registrations of one Household in the scope, newest first. */
@Component
@RequiredArgsConstructor
public class HouseholdDevicesList implements PagedList<DeviceRegistration, HouseholdDevicesScope> {

  private static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private final DeviceRegistrationRepository registrationRepository;

  @Override
  public ListOrder order(HouseholdDevicesScope household) {
    return NEWEST_FIRST;
  }

  @Override
  public List<Row<DeviceRegistration>> rowsBeyond(HouseholdDevicesScope household, Seek seek) {
    return registrationRepository.rowsInHousehold(
        new HouseholdRegistrationFilter(
            household.householdId(), DeviceRegistrationStatus.ACTIVE, household.visitorsOnly()),
        seek);
  }
}
