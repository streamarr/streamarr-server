package com.streamarr.server.services.authorization.cedar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Slice Assembler Tests")
class SliceAssemblerTest {

  @Test
  @DisplayName("Should fail startup when an action's fact has no contributor")
  void shouldFailStartupWhenActionFactHasNoContributor() {
    assertThatThrownBy(() -> new SliceAssembler(List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no contributor provides it");
  }

  @Test
  @DisplayName("Should fail startup when two contributors provide one fact")
  void shouldFailStartupWhenTwoContributorsProvideOneFact() {
    var first = contributor("first");
    var second = contributor("second");
    var contributors = new ArrayList<>(ContributorStubs.allWith(first));
    contributors.add(second);

    assertThatThrownBy(() -> new SliceAssembler(contributors))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Two contributors");
  }

  @Test
  @DisplayName("Should build the principal and resource from the identity and the check")
  void shouldBuildPrincipalAndResourceFromIdentityAndCheck() {
    var accountId = UUID.randomUUID();
    var identity =
        AuthenticatedIdentityFixture.defaultIdentityBuilder().accountId(accountId).build();
    var assembler = new SliceAssembler(ContributorStubs.allWith(contributor("marker")));

    var slice = assembler.assemble(identity, AuthorizationCheck.onServer(Action.ADD_LIBRARY));

    assertThat(slice.principal()).isEqualTo(CedarIds.account(accountId));
    assertThat(slice.entities())
        .satisfiesExactly(
            principal -> {
              assertThat(principal.getEUID()).isEqualTo(CedarIds.account(accountId));
              assertThat(principal.getAttr("marker")).isEqualTo(new PrimBool(true));
            },
            resource -> assertThat(resource.getEUID()).isEqualTo(CedarIds.server()));
  }

  private static FactContributor contributor(String attribute) {
    return new FactContributor() {
      @Override
      public FactRequirement provides() {
        return FactRequirement.LIVE_PRINCIPAL_AUTHORITY;
      }

      @Override
      public void contribute(
          AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
        slice.principalAttribute(attribute, new PrimBool(true));
      }
    };
  }
}
