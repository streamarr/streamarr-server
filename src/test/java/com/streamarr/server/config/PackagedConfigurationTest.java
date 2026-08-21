package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.Argon2Properties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
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
  @DisplayName("Should launch Procfile process types through the procfile buildpack when packaged")
  void shouldLaunchProcfileProcessTypesThroughProcfileBuildpackWhenPackaged() throws IOException {
    var buildAction = Files.readString(Path.of(".github/actions/pack-build/action.yml"));

    assertThat(buildAction)
        .contains("--buildpack paketo-buildpacks/procfile", "BP_INCLUDE_FILES=Procfile");
  }

  @Test
  @DisplayName("Should wire Cedar engine verification into every package image build")
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  void shouldWireCedarEngineVerificationIntoEveryPackageImageBuild() throws IOException {
    var action = yaml(".github/actions/pack-build/action.yml");
    var buildStep =
        stepNamed(listOfMaps(map(action.get("runs")).get("steps")), "Build with pack CLI");
    var buildCommand = (String) buildStep.get("run");
    var verifyScript =
        Files.readString(Path.of(".github/actions/pack-build/verify-cedar-image.sh"));

    assertThat(buildCommand)
        .contains(".github/actions/pack-build/verify-cedar-image.sh \"${build_image}\"");
    assertThat(verifyScript)
        .contains(
            "--enable-native-access=ALL-UNNAMED",
            "-Dloader.main=com.streamarr.server.services.authorization.cedar"
                + ".CedarEngineSelfCheckLauncher",
            "Cedar self-check passed: permittedAllowed=true strangerDenied=true");
    assertThat(Path.of(".github/actions/pack-build/verify-cedar-image.sh")).isExecutable();
  }

  @Test
  @DisplayName("Should rebuild package images when Maven or wrapper inputs change")
  void shouldRebuildPackageImagesWhenMavenOrWrapperInputsChange() throws IOException {
    var workflow = yaml(".github/workflows/ci.yml");
    var changes = map(map(workflow.get("jobs")).get("changes"));
    var filterStep = stepNamed(listOfMaps(changes.get("steps")), "Filter changed paths");
    var filters = (String) map(filterStep.get("with")).get("filters");

    assertThat(filters)
        .contains(
            "- 'pom.xml'",
            "- 'mvnw'",
            "- 'mvnw.cmd'",
            "- '.mvn/**'",
            "- 'Procfile'",
            "- 'src/main/java/com/streamarr/server/services/authorization/cedar/**'");
  }

  @Test
  @DisplayName("Should package FFmpeg through its launch buildpack when building an image")
  void shouldPackageFfmpegThroughItsLaunchBuildpackWhenBuildingAnImage() throws IOException {
    var action = yaml(".github/actions/pack-build/action.yml");
    var steps = listOfMaps(map(action.get("runs")).get("steps"));
    var buildStep = stepNamed(steps, "Build with pack CLI");
    var buildCommand = (String) buildStep.get("run");

    assertThat(buildCommand).contains("--buildpack ./buildpacks/ffmpeg");
    assertThat(steps).noneMatch(step -> "Install pinned FFmpeg runtime".equals(step.get("name")));
    assertThat(buildCommand).doesNotContain(".profile", "BP_INCLUDE_FILES=.ffmpeg");
    assertThat(Path.of(".profile")).doesNotExist();
    assertThat(Path.of(".github/actions/pack-build/install-ffmpeg.sh")).doesNotExist();
  }

  @Test
  @DisplayName("Should grant repository contents access only to release image build job")
  void shouldGrantRepositoryContentsAccessOnlyToReleaseImageBuildJob() throws IOException {
    var workflow = yaml(".github/workflows/publish-release.yml");
    var jobs = map(workflow.get("jobs"));
    var buildReleaseImages = map(jobs.get("build_release_images"));
    var publishRelease = map(jobs.get("publish_release"));

    assertThat(map(workflow.get("permissions"))).isEmpty();
    assertThat(map(buildReleaseImages.get("permissions"))).isEqualTo(Map.of("contents", "read"));
    assertThat(publishRelease).doesNotContainKey("permissions");
  }

  @Test
  @DisplayName("Should derive OCI revision from checked out source when packaging an image")
  void shouldDeriveOciRevisionFromCheckedOutSourceWhenPackagingAnImage() throws IOException {
    var action = yaml(".github/actions/pack-build/action.yml");
    var buildStep =
        stepNamed(listOfMaps(map(action.get("runs")).get("steps")), "Build with pack CLI");
    var buildCommand = (String) buildStep.get("run");
    var releaseWorkflow = yaml(".github/workflows/publish-release.yml");
    var releaseBuild = map(map(releaseWorkflow.get("jobs")).get("build_release_images"));
    var releasePackStep =
        listOfMaps(releaseBuild.get("steps")).stream()
            .filter(step -> "./.github/actions/pack-build".equals(step.get("uses")))
            .findFirst()
            .orElseThrow();

    assertThat(map(action.get("inputs"))).containsKey("image-version");
    assertThat(buildCommand)
        .contains(
            "image_revision=\"$(git rev-parse HEAD)\"",
            "BP_OCI_SOURCE=${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}",
            "BP_OCI_REVISION=${image_revision}",
            "BP_OCI_VERSION=${INPUT_IMAGE_VERSION}",
            "\"${image_revision}\"")
        .doesNotContain("BP_OCI_REVISION=${GITHUB_SHA}");
    assertThat(map(releasePackStep.get("with")))
        .containsEntry(
            "image-version",
            "${{ fromJSON(steps.meta.outputs.json).labels['org.opencontainers.image.version'] }}");
  }

  @Test
  @DisplayName("Should build and verify every supported architecture when a pull request changes")
  void shouldBuildAndVerifyEverySupportedArchitectureWhenAPullRequestChanges() throws IOException {
    var workflow = yaml(".github/workflows/ci.yml");
    var jobs = map(workflow.get("jobs"));

    assertThat(jobs).containsKey("package_image");

    var packageImage = map(jobs.get("package_image"));
    var strategy = map(packageImage.get("strategy"));
    var matrix = map(strategy.get("matrix"));
    var architectures = listOfMaps(matrix.get("include"));
    var steps = listOfMaps(packageImage.get("steps"));
    var packStep =
        steps.stream()
            .filter(step -> "./.github/actions/pack-build".equals(step.get("uses")))
            .findFirst()
            .orElseThrow();

    assertThat(packageImage).containsEntry("runs-on", "${{ matrix.runner }}");
    assertThat(architectures)
        .containsExactlyInAnyOrder(
            Map.of("architecture", "amd64", "runner", "ubuntu-24.04"),
            Map.of("architecture", "arm64", "runner", "ubuntu-24.04-arm"));
    assertThat(map(packStep.get("with")))
        .containsEntry("publish", "false")
        .doesNotContainKeys("dockerhub-username", "dockerhub-token");
  }

  @Test
  @DisplayName("Should publish build when registry cache is used")
  void shouldPublishBuildWhenRegistryCacheIsUsed() throws IOException {
    var action = yaml(".github/actions/pack-build/action.yml");
    var buildStep =
        stepNamed(listOfMaps(map(action.get("runs")).get("steps")), "Build with pack CLI");
    var cachedBuilds =
        packBuildCommands((String) buildStep.get("run")).stream()
            .filter(command -> command.contains("--cache-image"))
            .toList();

    assertThat(cachedBuilds)
        .as("Pack requires every cached build to publish directly to its candidate image")
        .isNotEmpty()
        .allMatch(command -> command.contains("--publish"));
  }

  @Test
  @DisplayName(
      "Should publish immutable release image before latest when release architectures are verified")
  void shouldPublishImmutableReleaseImageBeforeLatestWhenReleaseArchitecturesAreVerified()
      throws IOException {
    var workflow = yaml(".github/workflows/publish-release.yml");
    var publishRelease = map(map(workflow.get("jobs")).get("publish_release"));
    var stepNames =
        listOfMaps(publishRelease.get("steps")).stream().map(step -> step.get("name")).toList();

    assertThat(publishRelease).containsEntry("needs", "build_release_images");
    assertThat(stepNames)
        .containsSubsequence(
            "Publish immutable multi-architecture image",
            "Publish latest multi-architecture image");
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
    var dependencyPattern =
        Pattern.compile(
            "--(?:builder|buildpack) (?<depName>[^\\s\\\\]+?)(?:[:@])"
                + "(?<currentValue>\\d+\\.\\d+\\.\\d+)");
    var pinnedDependencies = dependencyPins(dependencyPattern, buildAction);
    var trackedDependencies =
        StreamSupport.stream(renovateConfig.path("customManagers").spliterator(), false)
            .filter(manager -> managesFile(manager, ".github/actions/pack-build/action.yml"))
            .flatMap(manager -> dependencyPins(manager, buildAction).stream())
            .collect(Collectors.toSet());

    assertThat(pinnedDependencies)
        .as("every independently pinned Pack input must be extractable by Renovate")
        .isNotEmpty()
        .isEqualTo(trackedDependencies);
  }

  @Test
  @DisplayName("Should build every supported architecture when publishing a release")
  void shouldBuildEverySupportedArchitectureWhenPublishingARelease() throws IOException {
    var workflow = yaml(".github/workflows/publish-release.yml");
    var buildReleaseImages = map(map(workflow.get("jobs")).get("build_release_images"));
    var matrix = map(map(buildReleaseImages.get("strategy")).get("matrix"));
    var architectures = listOfMaps(matrix.get("include"));
    var buildStep =
        listOfMaps(buildReleaseImages.get("steps")).stream()
            .filter(step -> "./.github/actions/pack-build".equals(step.get("uses")))
            .findFirst()
            .orElseThrow();

    assertThat(buildReleaseImages).containsEntry("runs-on", "${{ matrix.runner }}");
    assertThat(architectures)
        .containsExactlyInAnyOrder(
            Map.of("architecture", "amd64", "runner", "ubuntu-24.04"),
            Map.of("architecture", "arm64", "runner", "ubuntu-24.04-arm"));
    assertThat(map(buildStep.get("with"))).containsEntry("publish", "true");
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

  private static Map<String, Object> yaml(String file) throws IOException {
    try (var input = Files.newInputStream(Path.of(file))) {
      return new Yaml().load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> listOfMaps(Object value) {
    return (List<Map<String, Object>>) value;
  }

  private static Map<String, Object> stepNamed(
      List<Map<String, Object>> steps, String expectedName) {
    return steps.stream()
        .filter(step -> expectedName.equals(step.get("name")))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> packBuildCommands(String script) {
    var commands = new ArrayList<String>();
    var command = new StringBuilder();

    for (var line : script.lines().toList()) {
      if (command.isEmpty() && !line.stripLeading().startsWith("pack build ")) {
        continue;
      }

      command.append(' ').append(line.strip());
      if (!line.stripTrailing().endsWith("\\")) {
        commands.add(command.toString());
        command.setLength(0);
      }
    }

    return commands;
  }

  private static Set<String> dependencyPins(Pattern pattern, String content) {
    var pins = new HashSet<String>();
    var matcher = pattern.matcher(content);
    while (matcher.find()) {
      pins.add(matcher.group("depName") + "@" + matcher.group("currentValue"));
    }
    return pins;
  }

  private static Set<String> dependencyPins(JsonNode manager, String content) {
    return StreamSupport.stream(manager.path("matchStrings").spliterator(), false)
        .flatMap(node -> dependencyPins(Pattern.compile(node.asString()), content).stream())
        .collect(Collectors.toSet());
  }

  private static boolean managesFile(JsonNode manager, String file) {
    return StreamSupport.stream(manager.path("managerFilePatterns").spliterator(), false)
        .map(JsonNode::asString)
        .map(PackagedConfigurationTest::patternBetweenSlashes)
        .anyMatch(pattern -> pattern.matcher(file).matches());
  }

  private static Pattern patternBetweenSlashes(String renovatePattern) {
    var lastSlash = renovatePattern.lastIndexOf('/');
    return Pattern.compile(renovatePattern.substring(1, lastSlash));
  }
}
