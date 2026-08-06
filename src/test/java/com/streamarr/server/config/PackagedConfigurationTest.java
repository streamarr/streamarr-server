package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.Argon2Properties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Packaged Configuration Tests")
class PackagedConfigurationTest {

  private static final int MIN_MEMORY_KIB = 19_456;
  private static final int MIN_ITERATIONS = 2;
  private static final int MIN_PARALLELISM = 1;

  private static final ApplicationContextRunner CONTEXT_RUNNER =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(PackagedConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(Argon2Properties.class)
  static class PackagedConfiguration {}

  /**
   * The packaged artifact must never choose its own runtime profile: a default boot that silently
   * activates dev would seed a known admin credential on every fresh install. Dev is activated by
   * the spring-boot-maven-plugin run configuration instead.
   */
  @Test
  @DisplayName("Should not activate any profile when packaged configuration loads")
  void shouldNotActivateAnyProfileWhenPackagedConfigurationLoads() throws IOException {
    try (var input = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
      Map<String, Object> root = new Yaml().load(input);

      @SuppressWarnings("unchecked")
      var spring = (Map<String, Object>) root.get("spring");

      assertThat(spring).doesNotContainKey("profiles");
    }
  }

  @Test
  @DisplayName("Should ship Argon2 defaults that meet the OWASP floor")
  void shouldShipArgon2DefaultsThatMeetOwaspFloor() {
    CONTEXT_RUNNER.run(
        context -> {
          assertThat(context).hasNotFailed();
          var properties = context.getBean(Argon2Properties.class);

          assertThat(properties.memoryKib()).isGreaterThanOrEqualTo(MIN_MEMORY_KIB);
          assertThat(properties.iterations()).isGreaterThanOrEqualTo(MIN_ITERATIONS);
          assertThat(properties.parallelism()).isGreaterThanOrEqualTo(MIN_PARALLELISM);
        });
  }

  @Test
  @DisplayName("Should ship separate server and transcode worker process types when packaged")
  void shouldShipSeparateServerAndTranscodeWorkerProcessTypesWhenPackaged() throws IOException {
    var processes = Set.copyOf(Files.readAllLines(Path.of("Procfile")));
    var buildAction = Files.readString(Path.of(".github/actions/pack-build/action.yml"));

    assertThat(processes)
        .contains(
            "web: java org.springframework.boot.loader.launch.JarLauncher",
            "worker: java -Dloader.main=com.streamarr.transcode.worker.TranscodeWorkerApplication "
                + "org.springframework.boot.loader.launch.PropertiesLauncher");
    assertThat(buildAction).contains("--buildpack paketo-buildpacks/procfile");
  }

  @Test
  @DisplayName("Should publish Pack builds that use a registry cache")
  void shouldPublishPackBuildsThatUseARegistryCache() throws IOException {
    var buildAction = Files.readString(Path.of(".github/actions/pack-build/action.yml"));
    var packBuildStart = buildAction.indexOf("pack build");
    var packBuild =
        buildAction.substring(packBuildStart, buildAction.indexOf("\n\n", packBuildStart));

    assertThat(packBuild.contains("--cache-image") && !packBuild.contains("--publish"))
        .as("Pack cannot use --cache-image without --publish")
        .isFalse();
  }

  @Test
  @DisplayName("Should publish immutable release tag before latest")
  void shouldPublishImmutableReleaseTagBeforeLatest() throws IOException {
    var releaseWorkflow = Files.readString(Path.of(".github/workflows/publish-release.yml"));
    var latestTag = releaseWorkflow.indexOf("streamarr/streamarr-server:latest");
    var immutableTag = releaseWorkflow.indexOf("streamarr/streamarr-server:${{ fromJSON");

    assertThat(immutableTag).isLessThan(latestTag);
  }

  @Test
  @DisplayName("Should not ship an Apt manifest when packaging without the Apt buildpack")
  void shouldNotShipAnAptManifestWhenPackagingWithoutTheAptBuildpack() throws IOException {
    var buildAction = Files.readString(Path.of(".github/actions/pack-build/action.yml"));

    assertThat(buildAction).doesNotContain("paketo-buildpacks/apt");
    assertThat(Path.of("apt.yml")).doesNotExist();
  }

  @Test
  @DisplayName(
      "Should pin and track independently versioned Pack inputs when packaging container images")
  void shouldPinAndTrackIndependentlyVersionedPackInputsWhenPackagingContainerImages()
      throws IOException {
    var buildAction = Files.readString(Path.of(".github/actions/pack-build/action.yml"));
    var renovateConfig = new ObjectMapper().readTree(Files.readString(Path.of("renovate.json")));
    var packDependencies =
        buildAction
            .lines()
            .map(String::strip)
            .filter(
                line ->
                    line.startsWith("--builder paketobuildpacks/ubuntu-noble-builder")
                        || line.startsWith("--buildpack paketo-buildpacks/procfile"))
            .toList();
    var renovateMatchers =
        StreamSupport.stream(renovateConfig.path("customManagers").spliterator(), false)
            .flatMap(
                manager -> StreamSupport.stream(manager.path("matchStrings").spliterator(), false))
            .map(node -> Pattern.compile(node.asString()))
            .toList();

    assertThat(packDependencies)
        .hasSize(2)
        .allMatch(
            dependency ->
                dependency.matches("--(?:builder|buildpack) [^\\s\\\\]+(?:[:@])\\d+\\.\\d+\\.\\d+"))
        .allSatisfy(
            dependency ->
                assertThat(renovateMatchers)
                    .anyMatch(matcher -> matcher.matcher(dependency).find()));
  }

  @Test
  @DisplayName("Should track the pinned FFmpeg runtime when packaging container images")
  void shouldTrackThePinnedFfmpegRuntimeWhenPackagingContainerImages() throws IOException {
    var renovateConfig = new ObjectMapper().readTree(Files.readString(Path.of("renovate.json")));
    var customManagers =
        StreamSupport.stream(renovateConfig.path("customManagers").spliterator(), false).toList();

    assertThat(customManagers)
        .anySatisfy(
            manager -> {
              assertThat(manager.path("managerFilePatterns").toString()).contains("install-ffmpeg");
              assertThat(manager.path("datasourceTemplate").asString())
                  .isEqualTo("github-releases");
              assertThat(manager.path("depNameTemplate").asString())
                  .isEqualTo("BtbN/FFmpeg-Builds");
            });
  }

  @Test
  @DisplayName("Should exercise every supported FFmpeg architecture before publishing releases")
  void shouldExerciseEverySupportedFfmpegArchitectureBeforePublishingReleases() throws IOException {
    var installer = Files.readString(Path.of(".github/actions/pack-build/install-ffmpeg.sh"));
    var releaseWorkflow = Files.readString(Path.of(".github/workflows/publish-release.yml"));
    var supportsArm64 = installer.contains("platform=linuxarm64");
    var buildsOnArm64 =
        Pattern.compile("ubuntu-(?:22|24|26)\\.04-arm").matcher(releaseWorkflow).find();

    assertThat(supportsArm64 && !buildsOnArm64)
        .as("linuxarm64 is declared but the release workflow has no ARM64 runner")
        .isFalse();
    assertThat(releaseWorkflow)
        .contains(
            "architecture: amd64",
            "architecture: arm64",
            "needs: build_release_images",
            "docker buildx imagetools create");
  }

  @Test
  @DisplayName("Should use a multi-architecture builder when packaging release architectures")
  void shouldUseMultiArchitectureBuilderWhenPackagingReleaseArchitectures() throws IOException {
    var buildAction = Files.readString(Path.of(".github/actions/pack-build/action.yml"));

    assertThat(buildAction).contains("--builder paketobuildpacks/ubuntu-noble-builder:");
  }

  @Test
  @DisplayName("Should ship an opt-in Docker Compose worker path when packaged")
  void shouldShipOptInDockerComposeWorkerPathWhenPackaged() throws IOException {
    var deployment = Files.readString(Path.of("deploy/compose/distributed-transcoding.yml"));

    assertThat(deployment)
        .contains(
            "entrypoint: worker",
            "STREAMING_REMOTE_ENABLED: \"true\"",
            "TRANSCODE_WORKER_CONTROL_PLANE_HOST: streamarr-server");
  }

  @Test
  @DisplayName(
      "Should ship opt-in hardware transcoding for the Docker Compose worker when packaged")
  void shouldShipOptInHardwareTranscodingForDockerComposeWorkerWhenPackaged() throws IOException {
    var deployment = Files.readString(Path.of("deploy/compose/hardware-transcoding.yml"));

    assertThat(deployment).contains("transcode-worker:", "devices:", "/dev/dri:/dev/dri");
  }

  @Test
  @DisplayName(
      "Should ship a single-server Kubernetes path with per-pod worker identity when packaged")
  void shouldShipSingleServerKubernetesPathWithPerPodWorkerIdentityWhenPackaged()
      throws IOException {
    var deployment = Files.readString(Path.of("deploy/kubernetes/distributed-transcoding.yaml"));

    assertThat(deployment)
        .contains(
            "replicas: 1",
            "replicas: 2",
            "fieldPath: metadata.uid",
            "spiffe://streamarr.example/streamarr/worker/${POD_UID}");
  }
}
