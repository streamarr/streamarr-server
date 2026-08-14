package com.streamarr.server.graphql.inputs;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
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
    var inputs =
        Arrays.stream(PortableProfileInputs.class.getDeclaredClasses())
            .filter(Class::isRecord)
            .filter(PortableProfileInputsTest::carriesPlaintextSecret)
            .map(type -> instantiate(type, secret))
            .toList();

    assertThat(inputs)
        .isNotEmpty()
        .allSatisfy(
            input ->
                assertThat(input.toString())
                    .doesNotContain(secret)
                    .containsIgnoringCase("redacted"));
  }

  private static boolean carriesPlaintextSecret(Class<?> type) {
    return Arrays.stream(type.getRecordComponents())
        .map(RecordComponent::getName)
        .map(name -> name.toLowerCase(Locale.ROOT))
        .anyMatch(name -> name.contains("password") || name.contains("pin"));
  }

  private static Object instantiate(Class<?> type, String secret) {
    try {
      var components = type.getRecordComponents();
      var parameterTypes =
          Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new);
      var arguments =
          Arrays.stream(components).map(component -> value(component, secret)).toArray();
      return type.getDeclaredConstructor(parameterTypes).newInstance(arguments);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(
          "Could not construct secret-bearing GraphQL input", exception);
    }
  }

  private static Object value(RecordComponent component, String secret) {
    var name = component.getName().toLowerCase(Locale.ROOT);
    if (name.contains("password") || name.contains("pin")) {
      return secret;
    }
    if (component.getType() == String.class) {
      return "value";
    }
    if (component.getType() == int.class || component.getType() == Integer.class) {
      return 7;
    }
    if (component.getType().isEnum()) {
      return component.getType().getEnumConstants()[0];
    }
    throw new IllegalStateException("Unsupported GraphQL input component " + component.getName());
  }
}
