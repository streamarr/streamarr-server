package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Portable Identity Command Validation Tests")
class PortableIdentityCommandValidationTest {

  @Test
  @DisplayName("Should reject null security audit operation at construction")
  void shouldRejectNullSecurityAuditOperationAtConstruction() {
    var invalidAudit =
        SecurityAuditRecord.builder()
            .actingAccountId(UUID.randomUUID())
            .reason("administrative action");

    assertThatThrownBy(invalidAudit::build).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("Should reject null profile id in lifecycle command at construction")
  void shouldRejectNullProfileIdInLifecycleCommandAtConstruction() {
    var invalidOffer =
        ProfileShareOffer.builder()
            .actingAccountId(UUID.randomUUID())
            .targetHouseholdId(UUID.randomUUID());

    assertThatThrownBy(invalidOffer::build).isInstanceOf(NullPointerException.class);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidLifecycleCommands")
  @DisplayName("Should reject null required lifecycle command values at construction")
  void shouldRejectNullRequiredLifecycleCommandValuesAtConstruction(
      String commandName, Runnable construction) {
    assertThatThrownBy(construction::run).as(commandName).isInstanceOf(NullPointerException.class);
  }

  private static Stream<Arguments> invalidLifecycleCommands() {
    var id = UUID.randomUUID();
    return Stream.of(
        command(
            "account household transfer",
            () ->
                new AccountHouseholdTransferCommand(
                    null, id, id, HouseholdRole.MEMBER, "password", "reason")),
        command(
            "portable profile creation",
            () -> new CreatePortableProfileCommand(null, "Profile", ProfileKind.ADULT, null, null)),
        command("profile deletion", () -> new DeleteProfileCommand(null, id, "password")),
        command(
            "forced profile deletion",
            () -> new ForceProfileDeletionCommand(null, id, "password", "reason")),
        command(
            "forced profile unshare",
            () -> new ForceProfileUnshareCommand(null, id, "password", "reason")),
        command(
            "household ownership transfer",
            () -> new HouseholdOwnershipTransferCommand(null, id, id, "password", "reason")),
        command("household profile removal", () -> new HouseholdProfileRemoval(null, id)),
        command("profile home departure", () -> new ProfileHomeDeparture(null, id)),
        command(
            "profile management relinquishment",
            () -> new ProfileManagementRelinquishment(null, id)),
        command(
            "manager invitation acceptance",
            () -> new ProfileManagerInvitationAcceptance(null, id)),
        command(
            "manager invitation cancellation",
            () -> new ProfileManagerInvitationCancellation(null, id)),
        command(
            "manager invitation rejection", () -> new ProfileManagerInvitationRejection(null, id)),
        command("manager invitation", () -> new ProfileManagerInvite(null, id, id)),
        command(
            "manager override",
            () ->
                new ProfileManagerOverrideCommand(
                    null, id, id, ProfileManagerOverrideAction.GRANT, "password", "reason")),
        command(
            "profile kind change", () -> new SetProfileKindCommand(null, id, ProfileKind.ADULT)),
        command("content ceiling set", () -> new SetProfileContentCeilingCommand(null, id, 13)),
        command("content ceiling removal", () -> new RemoveProfileContentCeilingCommand(null, id)),
        command("profile PIN reset", () -> new ResetProfilePinCommand(null, id, "encoded-pin")),
        command("profile share acceptance", () -> new ProfileShareAcceptance(null, id, null)),
        command("profile share cancellation", () -> new ProfileShareCancellation(null, id)),
        command("profile share rejection", () -> new ProfileShareRejection(null, id)),
        command(
            "portable profile rename", () -> new RenamePortableProfileCommand(null, id, "Name")));
  }

  private static Arguments command(String name, Runnable construction) {
    return Arguments.of(name, construction);
  }
}
