package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Signed Authority Boundary Tests")
class SignedAuthorityBoundaryTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("householdCommands")
  @DisplayName("Should carry signed authority through household commands")
  void shouldCarrySignedAuthorityThroughHouseholdCommands(Class<?> commandType) {
    var components = Arrays.stream(commandType.getRecordComponents()).toList();

    assertThat(components)
        .extracting(RecordComponent::getName)
        .contains("authority")
        .doesNotContain("actingAccountId");
    assertThat(components)
        .filteredOn(component -> component.getName().equals("authority"))
        .singleElement()
        .extracting(RecordComponent::getType)
        .isEqualTo(AuthenticatedIdentity.class);
  }

  @Test
  @DisplayName("Should derive access tokens only from signed authority")
  void shouldDeriveAccessTokensOnlyFromSignedAuthority() {
    var derivedMethods =
        Arrays.stream(AccessTokenIssuer.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("issueDerived"))
            .toList();

    assertThat(derivedMethods)
        .singleElement()
        .satisfies(
            method ->
                assertThat(method.getParameterTypes())
                    .containsExactly(AuthenticatedIdentity.class, Instant.class));
  }

  private static Stream<Class<?>> householdCommands() {
    return Stream.of(
        CreatePortableProfileCommand.class,
        ProfileShareAcceptance.class,
        ProfileShareRejection.class,
        HouseholdProfileRemoval.class,
        ProfileHomeDeparture.class,
        HouseholdOwnershipTransferCommand.class);
  }
}
