package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileClassification;
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
            ProfilePolicyChange.builder().pinHash(secret).toString());

    assertThat(renderedValues)
        .hasSize(13)
        .allSatisfy(rendered -> assertThat(rendered).doesNotContain(secret));
  }

  @Test
  @DisplayName("Should not expose plaintext secrets in string representations when rendered")
  void shouldNotExposePlaintextSecretsInStringRepresentationsWhenRendered() {
    var secret = "review-secret-value";
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
                .targetRole(HouseholdRole.MEMBER)
                .password(secret)
                .build()
                .toString(),
            CreatePortableProfileCommand.builder()
                .classification(ProfileClassification.ADULT)
                .pinHash(secret)
                .build()
                .toString(),
            DeleteProfileCommand.builder().password(secret).build().toString(),
            ForceProfileDeletionCommand.builder().password(secret).build().toString(),
            ForceProfileUnshareCommand.builder().password(secret).build().toString(),
            HouseholdOwnershipTransferCommand.builder().password(secret).build().toString(),
            ProfileManagerOverrideCommand.builder()
                .action(ProfileManagerOverrideAction.GRANT)
                .password(secret)
                .build()
                .toString(),
            ProfilePolicyChange.builder()
                .classification(ProfileClassification.KID)
                .pinHash(secret)
                .build()
                .toString());

    assertThat(renderedValues)
        .hasSize(16)
        .allSatisfy(rendered -> assertThat(rendered).doesNotContain(secret));
  }
}
