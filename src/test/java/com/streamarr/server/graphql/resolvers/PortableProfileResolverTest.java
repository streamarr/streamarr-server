package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileClassification;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.graphql.inputs.PortableProfileInputs;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.CreatePortableProfileCommand;
import com.streamarr.server.services.auth.PortableIdentityTransactionExecutor;
import com.streamarr.server.services.auth.ProfileHomeDeparture;
import com.streamarr.server.services.auth.ProfileManagementService;
import com.streamarr.server.services.auth.ProfileSharingService;
import com.streamarr.server.services.auth.TokenScope;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

@Tag("UnitTest")
@DisplayName("Portable Profile Resolver Tests")
class PortableProfileResolverTest {

  @Test
  @DisplayName("Should derive leave current home account and profile only from live identity")
  void shouldDeriveLeaveCurrentHomeAccountAndProfileOnlyFromLiveIdentity() {
    var accountId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    var sharingService = new CapturingProfileSharingService();
    var authorizationService =
        new FakeAuthorizationService(
            AuthenticatedIdentity.builder()
                .accountId(accountId)
                .profileId(profileId)
                .role(AccountRole.USER)
                .authSessionId(UUID.randomUUID())
                .scope(TokenScope.PROFILE)
                .build());
    var resolver =
        new PortableProfileResolver(
            authorizationService,
            new PortableIdentityTransactionExecutor(new NoOpTransactionManager()),
            sharingService,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThat(resolver.leaveCurrentHome()).isTrue();
    assertThat(sharingService.departure)
        .isEqualTo(
            ProfileHomeDeparture.builder()
                .actingAccountId(accountId)
                .activeProfileId(profileId)
                .build());
  }

  @Test
  @DisplayName("Should derive portable profile creator and hash PIN at GraphQL boundary")
  void shouldDerivePortableProfileCreatorAndHashPinAtGraphQlBoundary() {
    var accountId = UUID.randomUUID();
    var managementService = new CapturingProfileManagementService();
    var authorizationService =
        new FakeAuthorizationService(
            AuthenticatedIdentity.builder()
                .accountId(accountId)
                .role(AccountRole.USER)
                .authSessionId(UUID.randomUUID())
                .scope(TokenScope.ACCOUNT)
                .build());
    var resolver =
        new PortableProfileResolver(
            authorizationService,
            new PortableIdentityTransactionExecutor(new NoOpTransactionManager()),
            null,
            managementService,
            null,
            null,
            null,
            null,
            NoOpPasswordEncoder.getInstance());

    var result =
        resolver.createPortableProfile(
            new PortableProfileInputs.ProfileCreation(
                "Global Kai", ProfileClassification.KID, 7, "1234"));

    assertThat(result.name()).isEqualTo("Global Kai");
    assertThat(managementService.creation)
        .isEqualTo(
            CreatePortableProfileCommand.builder()
                .actingAccountId(accountId)
                .name("Global Kai")
                .classification(ProfileClassification.KID)
                .maximumAllowedRatingAge(7)
                .pinHash("1234")
                .build());
  }

  private static final class CapturingProfileSharingService extends ProfileSharingService {

    private ProfileHomeDeparture departure;

    private CapturingProfileSharingService() {
      super(null, null, null, null, null, null, null, null, null);
    }

    @Override
    public void leaveCurrentHome(ProfileHomeDeparture departure) {
      this.departure = departure;
    }
  }

  private static final class CapturingProfileManagementService extends ProfileManagementService {

    private CreatePortableProfileCommand creation;

    private CapturingProfileManagementService() {
      super(null, null, null, null, null, null, null, null);
    }

    @Override
    public Profile create(CreatePortableProfileCommand command) {
      creation = command;
      return Profile.builder()
          .name(command.name())
          .classification(command.classification())
          .maximumAllowedRatingAge(command.maximumAllowedRatingAge())
          .pinHash(command.pinHash())
          .build();
    }
  }

  private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
