package com.streamarr.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Integration Test Fork Tests")
class IntegrationTestForkTest {

  @Test
  @DisplayName("Should keep the streamarr database when no fork number is set")
  void shouldKeepTheStreamarrDatabaseWhenNoForkNumberIsSet() {
    assertThat(IntegrationTestFork.databaseName(new Properties())).isEqualTo("streamarr");
  }

  @Test
  @DisplayName("Should keep the streamarr database when the first fork runs the tests")
  void shouldKeepTheStreamarrDatabaseWhenTheFirstForkRunsTheTests() {
    assertThat(IntegrationTestFork.databaseName(forkNumber("1"))).isEqualTo("streamarr");
  }

  @ParameterizedTest
  @CsvSource({"2, streamarr_fork2", "3, streamarr_fork3"})
  @DisplayName("Should give a later fork a database of its own when Failsafe numbers the forks")
  void shouldGiveALaterForkADatabaseOfItsOwnWhenFailsafeNumbersTheForks(
      String number, String database) {
    assertThat(IntegrationTestFork.databaseName(forkNumber(number))).isEqualTo(database);
  }

  private static Properties forkNumber(String value) {
    var properties = new Properties();
    properties.setProperty(IntegrationTestFork.NUMBER_PROPERTY, value);
    return properties;
  }
}
