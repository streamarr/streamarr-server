package com.streamarr.server.services.authorization.cedar;

import static org.assertj.core.api.Assertions.assertThat;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Intent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Java action enum, the Cedar schema, the intent planner, and the contributors must describe
 * the same world; a drift in any one of them is a request that fails validation in production.
 */
@Tag("UnitTest")
@DisplayName("Authorization Parity Tests")
class AuthorizationParityTest {

  private static final CedarPolicyBundle BUNDLE =
      new CedarPolicyBundle(new BasicAuthorizationEngine());

  @Test
  @DisplayName("Should declare every Java action in the schema with a Server resource")
  void shouldDeclareEveryJavaActionInSchemaWithServerResource() throws Exception {
    var actions = schemaActions();

    for (var action : Action.values()) {
      var declared = actions.get(action.cedarName());
      assertThat(declared).as("schema action for %s", action).isNotNull();
      assertThat(declared.path("appliesTo").path("resourceTypes"))
          .as("resource types of %s", action)
          .extracting(JsonNode::asText)
          .containsExactly("Server");
      assertThat(declared.path("appliesTo").path("principalTypes"))
          .extracting(JsonNode::asText)
          .containsExactly("Account");
    }
  }

  @Test
  @DisplayName("Should plan every concrete schema action from exactly one intent")
  void shouldPlanEveryConcreteSchemaActionFromExactlyOneIntent() throws Exception {
    var concrete = new ArrayList<String>();
    schemaActions()
        .properties()
        .forEach(
            entry -> {
              // Action groups carry an empty appliesTo; only leaf actions name a resource.
              if (!entry.getValue().path("appliesTo").path("resourceTypes").isEmpty()) {
                concrete.add(entry.getKey());
              }
            });
    var planned =
        allIntents().stream()
            .map(intent -> IntentPlanner.plan(intent).check().action().cedarName())
            .toList();

    assertThat(planned).containsExactlyInAnyOrderElementsOf(concrete);
    assertThat(Action.values())
        .extracting(Action::cedarName)
        .containsExactlyInAnyOrderElementsOf(concrete);
  }

  @Test
  @DisplayName("Should keep every library administration action under serverAdministration")
  void shouldKeepEveryLibraryAdministrationActionUnderServerAdministration() throws Exception {
    var actions = schemaActions();

    for (var action : Action.values()) {
      assertThat(actions.get(action.cedarName()).path("memberOf"))
          .extracting(member -> member.path("id").asText())
          .containsExactly("libraryAdministration");
    }
    assertThat(actions.get("libraryAdministration").path("memberOf"))
        .extracting(member -> member.path("id").asText())
        .containsExactly("serverAdministration");
  }

  @Test
  @DisplayName("Should require every declared fact from an action")
  void shouldRequireEveryDeclaredFactFromAnAction() {
    var required = new ArrayList<FactRequirement>();
    for (var action : Action.values()) {
      required.addAll(action.facts());
    }

    assertThat(required).containsOnlyElementsOf(List.of(FactRequirement.values()));
    assertThat(FactRequirement.values()).allMatch(required::contains);
  }

  private static JsonNode schemaActions() throws Exception {
    return BUNDLE.schema().toJsonFormat().path("Streamarr").path("actions");
  }

  private static List<Intent<AuthorizationUnit>> allIntents() {
    var libraryId = UUID.randomUUID();
    return List.of(
        new Intent.AddLibrary(),
        new Intent.RemoveLibrary(libraryId),
        new Intent.ScanLibrary(libraryId),
        new Intent.RefreshLibrary(libraryId));
  }
}
