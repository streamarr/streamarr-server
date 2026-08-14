package com.streamarr.server.graphql.inputs;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileClassification;
import com.streamarr.server.services.auth.ProfileManagerOverrideAction;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Portable Profile Input Tests")
class PortableProfileInputsTest {

  @Test
  @DisplayName("Should redact every plaintext secret from GraphQL input descriptions")
  void shouldRedactEveryPlaintextSecretFromGraphQlInputDescriptions() {
    var secret = "plaintext-secret";
    var id = UUID.randomUUID().toString();
    var inputs =
        List.of(
            new PortableProfileInputs.ProfileCreation("Kai", ProfileClassification.KID, 7, secret),
            new PortableProfileInputs.PolicyChange(id, ProfileClassification.ADULT, null, secret),
            new PortableProfileInputs.ProfileDeletion(id, secret),
            new PortableProfileInputs.ForceProfileDeletion(id, secret, "Recovery"),
            new PortableProfileInputs.ForceProfileUnshare(id, secret, "Recovery"),
            new PortableProfileInputs.ManagerOverride(
                id, id, ProfileManagerOverrideAction.GRANT, secret, "Recovery"),
            new PortableProfileInputs.AccountTransfer(
                id, id, HouseholdRole.PARENT, secret, "Recovery"),
            new PortableProfileInputs.OwnershipTransfer(id, id, secret, "Family change"));

    assertThat(inputs)
        .allSatisfy(
            input ->
                assertThat(input.toString())
                    .doesNotContain(secret)
                    .containsIgnoringCase("redacted"));
  }
}
