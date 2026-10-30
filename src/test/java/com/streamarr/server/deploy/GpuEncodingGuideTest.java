package com.streamarr.server.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("GPU Encoding Guide Tests")
class GpuEncodingGuideTest {

  private static final Path GUIDE = Path.of("docs/distributed-transcoding.adoc");
  private static final Path COMPOSE_FILE = Path.of("docker-compose.yml");
  private static final Path MANIFEST = Path.of("deploy/kubernetes/distributed-transcoding.yaml");
  private static final String WORKER_DEPLOYMENT = "streamarr-transcode-worker";

  // FFmpeg's NVENC encoders load libcuda (compute) and libnvidia-encode (video); utility mounts
  // nvidia-smi, which the guide uses to check the container's GPU.
  private static final List<String> NVENC_DRIVER_CAPABILITIES =
      List.of("compute", "video", "utility");

  @Test
  @DisplayName("Should reserve the NVENC driver capabilities for the shipped Compose worker")
  void shouldReserveTheNvencDriverCapabilitiesForTheShippedComposeWorker() throws IOException {
    var snippet = asMap(new Yaml().load(listing("compose-nvidia-gpu")));
    var services = asMap(snippet.get("services"));
    var shippedServices =
        asMap(asMap(new Yaml().load(Files.readString(COMPOSE_FILE))).get("services"));

    assertThat(shippedServices).containsKeys(services.keySet().toArray(String[]::new));
    var reservations =
        asMap(
            asMap(asMap(asMap(services.get("transcode-worker")).get("deploy")).get("resources"))
                .get("reservations"));
    var device = asMap(asList(reservations.get("devices")).getFirst());
    assertThat(device).containsEntry("driver", "nvidia");
    assertThat(asList(device.get("capabilities")))
        .contains("gpu")
        .containsAll(NVENC_DRIVER_CAPABILITIES);
  }

  @Test
  @DisplayName(
      "Should request the NVENC driver capabilities when the worker starts with docker run")
  void shouldRequestTheNvencDriverCapabilitiesWhenTheWorkerStartsWithDockerRun()
      throws IOException {
    var command = listing("docker-run-nvidia-gpu");

    assertThat(command)
        .contains(
            "--gpus 'all,\"capabilities=" + String.join(",", NVENC_DRIVER_CAPABILITIES) + "\"'");
  }

  @Test
  @DisplayName("Should patch a GPU and the NVENC driver capabilities into the shipped worker pods")
  void shouldPatchAGpuAndTheNvencDriverCapabilitiesIntoTheShippedWorkerPods() throws IOException {
    var patch = asMap(new Yaml().load(listing("kubernetes-nvidia-gpu")));
    var worker = firstContainer(patch);

    assertThat(asMap(patch.get("metadata"))).containsEntry("name", WORKER_DEPLOYMENT);
    assertThat(worker).containsEntry("name", firstContainer(shippedWorkerDeployment()).get("name"));
    assertThat(asMap(asMap(worker.get("resources")).get("limits")))
        .containsEntry("nvidia.com/gpu", 1);
    assertThat(asList(worker.get("env")))
        .contains(
            Map.of(
                "name",
                "NVIDIA_DRIVER_CAPABILITIES",
                "value",
                String.join(",", NVENC_DRIVER_CAPABILITIES)));
  }

  // The body of the guide's listing block declared as [source#<id>,<language>].
  private static String listing(String id) throws IOException {
    var lines = Files.readAllLines(GUIDE);
    var start = -1;
    for (var index = 0; index < lines.size(); index++) {
      if (lines.get(index).startsWith("[source#" + id + ",")) {
        start = index + 2;
        break;
      }
    }

    assertThat(start).as("listing %s in %s", id, GUIDE).isPositive();
    assertThat(lines.get(start - 1)).isEqualTo("----");
    var end = lines.subList(start, lines.size()).indexOf("----");
    assertThat(end).as("end of listing %s", id).isNotNegative();
    return String.join("\n", lines.subList(start, start + end));
  }

  private static Map<String, Object> shippedWorkerDeployment() throws IOException {
    for (Object document : new Yaml().loadAll(Files.readString(MANIFEST))) {
      if (!(document instanceof Map<?, ?>)) {
        continue;
      }

      var resource = asMap(document);
      if ("Deployment".equals(resource.get("kind"))
          && WORKER_DEPLOYMENT.equals(asMap(resource.get("metadata")).get("name"))) {
        return resource;
      }
    }

    throw new AssertionError("No " + WORKER_DEPLOYMENT + " Deployment in " + MANIFEST);
  }

  private static Map<String, Object> firstContainer(Map<String, Object> deployment) {
    var podSpec = asMap(asMap(asMap(deployment.get("spec")).get("template")).get("spec"));
    return asMap(asList(podSpec.get("containers")).getFirst());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object value) {
    assertThat(value).isInstanceOf(List.class);
    return (List<Object>) value;
  }
}
