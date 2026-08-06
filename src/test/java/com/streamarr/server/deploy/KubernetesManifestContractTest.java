package com.streamarr.server.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins the deployment invariants the example Kubernetes manifest documents: the single-server
 * topology must survive rollouts, probes must target the auto-enabled actuator endpoints, and
 * workload certificates must declare an explicit lifetime because they are only read at startup.
 */
@Tag("UnitTest")
@DisplayName("Kubernetes Manifest Contract Tests")
class KubernetesManifestContractTest {

  private static final Path MANIFEST = Path.of("deploy/kubernetes/distributed-transcoding.yaml");
  private static final Path HARDWARE_PATCH =
      Path.of("deploy/kubernetes/hardware-transcoding-patch.yaml");

  private static Map<String, Object> serverDeployment;
  private static Map<String, Object> workerDeployment;

  @BeforeAll
  static void loadManifest() throws IOException {
    for (Object document : new Yaml().loadAll(Files.readString(MANIFEST))) {
      if (!(document instanceof Map<?, ?> resource) || !"Deployment".equals(resource.get("kind"))) {
        continue;
      }
      var typed = asMap(resource);
      var name = asMap(typed.get("metadata")).get("name");
      if ("streamarr-server".equals(name)) {
        serverDeployment = typed;
      }
      if ("streamarr-transcode-worker".equals(name)) {
        workerDeployment = typed;
      }
    }
    assertThat(serverDeployment).isNotNull();
    assertThat(workerDeployment).isNotNull();
  }

  @Test
  @DisplayName(
      "Should recreate the single server instead of rolling out a second replica when deployed")
  void shouldRecreateSingleServerInsteadOfRollingOutSecondReplicaWhenDeployed() {
    var spec = asMap(serverDeployment.get("spec"));

    assertThat(spec).containsEntry("replicas", 1);
    assertThat(asMap(spec.get("strategy"))).containsEntry("type", "Recreate");
  }

  @Test
  @DisplayName("Should probe the server through the actuator health groups when deployed")
  void shouldProbeServerThroughActuatorHealthGroupsWhenDeployed() {
    var container = container(serverDeployment);

    assertThat(probePath(container, "startupProbe")).isEqualTo("/actuator/health/liveness");
    assertThat(probePath(container, "livenessProbe")).isEqualTo("/actuator/health/liveness");
    assertThat(probePath(container, "readinessProbe")).isEqualTo("/actuator/health/readiness");
  }

  @Test
  @DisplayName("Should declare resource requests for both workloads when deployed")
  void shouldDeclareResourceRequestsForBothWorkloadsWhenDeployed() {
    for (var deployment : List.of(serverDeployment, workerDeployment)) {
      var requests = asMap(asMap(container(deployment).get("resources")).get("requests"));

      assertThat(requests).containsKeys("cpu", "memory");
    }
  }

  @Test
  @DisplayName("Should harden both containers to the restricted pod security profile when deployed")
  void shouldHardenBothContainersToRestrictedPodSecurityProfileWhenDeployed() {
    for (var deployment : List.of(serverDeployment, workerDeployment)) {
      var securityContext = asMap(container(deployment).get("securityContext"));

      assertThat(securityContext).containsEntry("runAsNonRoot", true);
      assertThat(securityContext).containsEntry("allowPrivilegeEscalation", false);
      assertThat(asMap(securityContext.get("capabilities"))).containsEntry("drop", List.of("ALL"));
      assertThat(asMap(securityContext.get("seccompProfile")))
          .containsEntry("type", "RuntimeDefault");
    }
  }

  @Test
  @DisplayName("Should pin both images to an explicit tag when deployed")
  void shouldPinBothImagesToExplicitTagWhenDeployed() {
    for (var deployment : List.of(serverDeployment, workerDeployment)) {
      assertThat((String) container(deployment).get("image")).doesNotEndWith(":latest");
    }
  }

  @Test
  @DisplayName(
      "Should declare certificate lifetimes because certificates load only at startup when deployed")
  void shouldDeclareCertificateLifetimesBecauseCertificatesLoadOnlyAtStartupWhenDeployed() {
    for (var deployment : List.of(serverDeployment, workerDeployment)) {
      var attributes = csiVolumeAttributes(deployment);

      assertThat(attributes)
          .containsKey("csi.cert-manager.io/duration")
          .containsKey("csi.cert-manager.io/renew-before");
    }
  }

  @Test
  @DisplayName("Should ship opt-in DRI passthrough for the transcode worker when deployed")
  void shouldShipOptInDriPassthroughForTranscodeWorkerWhenDeployed() throws IOException {
    var patch = asMap(new Yaml().load(Files.readString(HARDWARE_PATCH)));
    var podSpec = asMap(asMap(asMap(patch.get("spec")).get("template")).get("spec"));
    var worker = asMap(((List<?>) podSpec.get("containers")).getFirst());
    var volumeMount = asMap(((List<?>) worker.get("volumeMounts")).getFirst());
    var volume = asMap(((List<?>) podSpec.get("volumes")).getFirst());

    assertThat(asMap(patch.get("metadata"))).containsEntry("name", "streamarr-transcode-worker");
    assertThat(volumeMount).containsEntry("name", "dri").containsEntry("mountPath", "/dev/dri");
    assertThat(asMap(volume.get("hostPath")))
        .containsEntry("path", "/dev/dri")
        .containsEntry("type", "Directory");
    assertThat(asMap(podSpec.get("securityContext"))).containsKey("supplementalGroups");
  }

  private static Map<String, Object> container(Map<String, Object> deployment) {
    var podSpec = asMap(asMap(asMap(deployment.get("spec")).get("template")).get("spec"));
    var containers = (List<?>) podSpec.get("containers");
    return asMap(containers.getFirst());
  }

  private static String probePath(Map<String, Object> container, String probeName) {
    var probe = asMap(container.get(probeName));
    return (String) asMap(probe.get("httpGet")).get("path");
  }

  private static Map<String, Object> csiVolumeAttributes(Map<String, Object> deployment) {
    var podSpec = asMap(asMap(asMap(deployment.get("spec")).get("template")).get("spec"));
    for (var volume : (List<?>) podSpec.get("volumes")) {
      var csi = asMap(volume).get("csi");
      if (csi != null) {
        return asMap(asMap(csi).get("volumeAttributes"));
      }
    }
    throw new AssertionError("No CSI volume found in deployment");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }
}
