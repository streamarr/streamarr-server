package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;

import com.streamarr.server.fakes.FakeSegmentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

public final class WorkerListenerPortAllocationScenario {

  private WorkerListenerPortAllocationScenario() {}

  public static void main(String[] args) throws Exception {
    var fixedLocalhost = Boolean.parseBoolean(args[0]);
    var listeners =
        WorkerSessionListeners.builder()
            .localhostPort(OptionalInt.of(fixedLocalhost ? 45001 : 0))
            .mutualTls(
                Optional.of(serverConfigurationBuilder().port(fixedLocalhost ? 0 : 45001).build()))
            .build();
    try (var range = Files.newBufferedReader(Path.of("/proc/sys/net/ipv4/ip_local_port_range"));
        var server = WorkerSessionServer.forListeners(listeners, new FakeSegmentStore())) {
      server.start();

      System.out.println("range=" + range.readLine());
      System.out.println("localhost=" + server.localhostPort());
      System.out.println("mutualTls=" + server.port());
    }
  }
}
