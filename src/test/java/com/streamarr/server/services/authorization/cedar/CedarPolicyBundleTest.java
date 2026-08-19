package com.streamarr.server.services.authorization.cedar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cedarpolicy.AuthorizationEngine;
import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.policy.Policy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@Tag("UnitTest")
@DisplayName("Cedar Policy Bundle Tests")
class CedarPolicyBundleTest {

  private static final AuthorizationEngine ENGINE = new BasicAuthorizationEngine();
  private static final String SCHEMA =
      """
      namespace Streamarr {
        entity Account;
        entity Server;
        action addLibrary appliesTo { principal: Account, resource: Server };
      }
      """;

  @Test
  @DisplayName("Should load and validate the shipped bundle keyed by policy id")
  void shouldLoadAndValidateShippedBundleKeyedByPolicyId() {
    var bundle = new CedarPolicyBundle(ENGINE);

    assertThat(bundle.policies().policies)
        .extracting(Policy::getID)
        .contains(
            "server-administration-requires-live-enabled-server-admin",
            "disabled-account-is-forbidden");
    assertThat(bundle.schema().schemaText).isPresent();
  }

  @Test
  @DisplayName("Should reject a policy without an id annotation")
  void shouldRejectPolicyWithoutIdAnnotation() {
    assertThatThrownBy(
            () ->
                new CedarPolicyBundle(
                    ENGINE, SCHEMA, List.of("permit (principal, action, resource);")))
        .isInstanceOf(CedarBundleException.class)
        .hasMessageContaining("@id");
  }

  @Test
  @DisplayName("Should reject a policy with a blank id annotation")
  void shouldRejectPolicyWithBlankIdAnnotation() {
    assertThatThrownBy(
            () ->
                new CedarPolicyBundle(
                    ENGINE, SCHEMA, List.of("@id(\" \") permit (principal, action, resource);")))
        .isInstanceOf(CedarBundleException.class)
        .hasMessageContaining("@id");
  }

  @Test
  @DisplayName("Should reject duplicate policy ids across files")
  void shouldRejectDuplicatePolicyIdsAcrossFiles() {
    var policy = "@id(\"same\") permit (principal, action, resource);";

    assertThatThrownBy(() -> new CedarPolicyBundle(ENGINE, SCHEMA, List.of(policy, policy)))
        .isInstanceOf(CedarBundleException.class)
        .hasMessageContaining("Duplicate")
        .hasMessageContaining("same");
  }

  @Test
  @DisplayName("Should reject policies that do not validate against the schema")
  void shouldRejectPoliciesThatDoNotValidateAgainstSchema() {
    var unknownAction =
        "@id(\"unknown\") permit (principal, action == Streamarr::Action::\"nope\", resource);";

    assertThatThrownBy(() -> new CedarPolicyBundle(ENGINE, SCHEMA, List.of(unknownAction)))
        .isInstanceOf(CedarBundleException.class)
        .hasMessageContaining("validation");
  }

  @Test
  @DisplayName("Should reject an unparseable schema")
  void shouldRejectUnparseableSchema() {
    assertThatThrownBy(() -> new CedarPolicyBundle(ENGINE, "namespace {", List.of()))
        .isInstanceOf(CedarBundleException.class)
        .hasMessageContaining("parsed");
  }

  @Test
  @DisplayName("Should fail startup when the policy directory cannot be listed")
  void shouldFailStartupWhenPolicyDirectoryCannotBeListed() {
    var resources =
        new PathMatchingResourcePatternResolver() {
          @Override
          public Resource[] getResources(String locationPattern) throws IOException {
            throw new IOException("listing failed");
          }
        };

    assertThatThrownBy(() -> new CedarPolicyBundle(ENGINE, resources))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("listed");
  }

  @Test
  @DisplayName("Should fail startup when a bundle resource cannot be read")
  void shouldFailStartupWhenBundleResourceCannotBeRead() {
    var resources =
        new PathMatchingResourcePatternResolver() {
          @Override
          public Resource getResource(String location) {
            return new AbstractResource() {
              @Override
              public String getDescription() {
                return "unreadable schema";
              }

              @Override
              public InputStream getInputStream() throws IOException {
                throw new IOException("unreadable");
              }
            };
          }
        };

    assertThatThrownBy(() -> new CedarPolicyBundle(ENGINE, resources))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("could not be read");
  }
}
