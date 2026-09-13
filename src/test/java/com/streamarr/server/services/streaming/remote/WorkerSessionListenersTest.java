package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Worker Session Listener Configuration Tests")
class WorkerSessionListenersTest {

  @ParameterizedTest
  @ValueSource(ints = {9090, 8181})
  @DisplayName("Should reject conflicting ports when both worker listeners use a fixed port")
  void shouldRejectConflictingPortsWhenBothWorkerListenersUseAFixedPort(int port) throws Exception {
    var listeners =
        WorkerSessionListeners.builder()
            .mutualTls(Optional.of(serverConfigurationBuilder().port(port).build()))
            .localhostPort(OptionalInt.of(port));

    assertThatThrownBy(listeners::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContainingAll(
            "streaming.worker-session.localhost.port",
            "streaming.worker-session.mutual-tls.port",
            "distinct");
  }

  @ParameterizedTest
  @CsvSource({"0,0", "0,9090", "9090,0", "9090,8181"})
  @DisplayName("Should accept listener ports when they are distinct or assigned by the OS")
  void shouldAcceptListenerPortsWhenTheyAreDistinctOrAssignedByTheOs(int tlsPort, int localPort)
      throws Exception {
    var listeners =
        WorkerSessionListeners.builder()
            .mutualTls(Optional.of(serverConfigurationBuilder().port(tlsPort).build()))
            .localhostPort(OptionalInt.of(localPort));

    assertThatCode(listeners::build).doesNotThrowAnyException();
  }
}
