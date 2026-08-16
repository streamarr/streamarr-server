package com.streamarr.server.services.auth;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Secret record string representation tests")
class SecretRecordToStringTest {

  @Test
  @DisplayName("Should not expose plaintext secrets in builder string representations")
  void shouldNotExposePlaintextSecretsInBuilderStringRepresentations() {
    var secret = UUID.randomUUID().toString();
    var renderedValues =
        List.of(
            LoginCommand.builder().password(secret).toString(),
            LoginCompletionCommand.builder().expectedPasswordHash(secret).toString(),
            SetupCommand.builder().password(secret).toString(),
            LoginResult.builder().rawRefreshToken(secret).toString(),
            AccessToken.builder().value(secret).toString(),
            AccountHouseholdTransferCommand.builder().password(secret).toString(),
            CreatePortableProfileCommand.builder().pinHash(secret).toString(),
            DeleteProfileCommand.builder().password(secret).toString(),
            ForceProfileDeletionCommand.builder().password(secret).toString(),
            ForceProfileUnshareCommand.builder().password(secret).toString(),
            HouseholdOwnershipTransferCommand.builder().password(secret).toString(),
            ProfileManagerOverrideCommand.builder().password(secret).toString(),
            ResetProfilePinCommand.builder().pinHash(secret).toString());

    assertThat(renderedValues)
        .hasSize(13)
        .allSatisfy(rendered -> assertThat(rendered).doesNotContain(secret));
  }

  @Test
  @DisplayName("Should not expose plaintext secrets in string representations when rendered")
  void shouldNotExposePlaintextSecretsInStringRepresentationsWhenRendered() {
    var secret = "review-secret-value";
    var id = UUID.randomUUID();
    var renderedValues =
        List.of(
            LoginCommand.builder().password(secret).build().toString(),
            LoginCompletionCommand.builder()
                .expectedPasswordHash(secret)
                .upgradedPasswordHash(Optional.of(secret))
                .build()
                .toString(),
            SetupCommand.builder().password(secret).build().toString(),
            LoginResult.builder().rawRefreshToken(secret).build().toString(),
            new IssuedRefreshToken(secret, null).toString(),
            new RefreshResult.Rotated(secret, null).toString(),
            new RefreshResult.GraceRetry(secret, null).toString(),
            AccessToken.builder()
                .value(secret)
                .expiresAt(Instant.EPOCH)
                .scope(TokenScope.ACCOUNT)
                .build()
                .toString(),
            AccountHouseholdTransferCommand.builder()
                .actingAccountId(id)
                .targetAccountId(id)
                .targetHouseholdId(id)
                .targetRole(HouseholdRole.MEMBER)
                .password(secret)
                .reason("review")
                .build()
                .toString(),
            CreatePortableProfileCommand.builder()
                .authority(accountIdentityBuilder().accountId(id).build())
                .name("Profile")
                .kind(ProfileKind.ADULT)
                .pinHash(secret)
                .build()
                .toString(),
            DeleteProfileCommand.builder()
                .actingAccountId(id)
                .profileId(id)
                .password(secret)
                .build()
                .toString(),
            ForceProfileDeletionCommand.builder()
                .actingAccountId(id)
                .profileId(id)
                .password(secret)
                .reason("review")
                .build()
                .toString(),
            ForceProfileUnshareCommand.builder()
                .actingAccountId(id)
                .shareId(id)
                .password(secret)
                .reason("review")
                .build()
                .toString(),
            HouseholdOwnershipTransferCommand.builder()
                .authority(
                    accountIdentityBuilder()
                        .accountId(id)
                        .householdId(id)
                        .householdRole(HouseholdRole.OWNER)
                        .build())
                .householdId(id)
                .targetAccountId(id)
                .password(secret)
                .reason("review")
                .build()
                .toString(),
            ProfileManagerOverrideCommand.builder()
                .actingAccountId(id)
                .targetAccountId(id)
                .profileId(id)
                .action(ProfileManagerOverrideAction.GRANT)
                .password(secret)
                .reason("review")
                .build()
                .toString(),
            ResetProfilePinCommand.builder()
                .actingAccountId(id)
                .profileId(id)
                .pinHash(secret)
                .build()
                .toString());

    assertThat(renderedValues)
        .hasSize(16)
        .allSatisfy(rendered -> assertThat(rendered).doesNotContain(secret));
  }
}
