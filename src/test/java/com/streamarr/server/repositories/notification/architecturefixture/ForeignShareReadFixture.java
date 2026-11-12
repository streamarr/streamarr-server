package com.streamarr.server.repositories.notification.architecturefixture;

import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;

import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

/**
 * A Notifications class that reads the sharing domain: its repository, and its table through jOOQ.
 */
@RequiredArgsConstructor
public class ForeignShareReadFixture {

  private final ProfileHouseholdShareRepository shareRepository;
  private final DSLContext dsl;

  @SuppressWarnings("java:S1144") // ArchUnit inspects this uninvoked bytecode fixture.
  private boolean shareExists(UUID shareId) {
    return shareRepository.existsById(shareId)
        || dsl.fetchExists(PROFILE_HOUSEHOLD_SHARE, PROFILE_HOUSEHOLD_SHARE.ID.eq(shareId));
  }
}
