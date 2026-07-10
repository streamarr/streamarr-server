package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Counter Listener Properties Redaction Tests")
class CounterListenerPropertiesRedactionTest {

  @Test
  @DisplayName("Should not expose connection details in string representation")
  void shouldNotExposeConnectionDetailsInStringRepresentation() {
    var url = "jdbc:postgresql://" + UUID.randomUUID() + "/streamarr";
    var username = UUID.randomUUID().toString();
    var password = UUID.randomUUID().toString();
    var properties = new CounterListenerProperties(url, username, password);

    assertThat(properties.toString()).doesNotContain(url, username, password);
  }

  @Test
  @DisplayName("Should not expose connection details in builder string representation")
  void shouldNotExposeConnectionDetailsInBuilderStringRepresentation() {
    var url = "jdbc:postgresql://" + UUID.randomUUID() + "/streamarr";
    var username = UUID.randomUUID().toString();
    var password = UUID.randomUUID().toString();
    var builder =
        CounterListenerProperties.builder().jdbcUrl(url).username(username).password(password);

    assertThat(builder.toString()).doesNotContain(url, username, password);
  }
}
