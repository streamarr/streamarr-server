package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("IntegrationTest")
@DisplayName("Worker Packaged Dependencies Integration Tests")
class WorkerPackagedDependenciesIT {

  private static final String PACKAGED_JAR_PROPERTY = "worker.packaged-jar";
  private static final String BOOT_LIB = "BOOT-INF/lib/";
  private static final List<String> FORBIDDEN_DEPENDENCY_PREFIXES =
      List.of(
          "streamarr-server-",
          "HikariCP-",
          "postgresql-",
          "jakarta.persistence-",
          "spring-boot-data-",
          "spring-boot-hibernate-",
          "spring-data-",
          "spring-orm-",
          "spring-jdbc-",
          "hibernate-",
          "flyway-",
          "jooq-",
          "graphql-",
          "dgs-",
          "spring-web-",
          "spring-webmvc-",
          "spring-webflux-",
          "reactive-streams-",
          "reactor-core-",
          "spring-security-");

  @Test
  @DisplayName("Should package only the narrow executable runtime")
  void shouldPackageOnlyNarrowExecutableRuntime() throws IOException {
    var packagedJar = Path.of(System.getProperty(PACKAGED_JAR_PROPERTY));

    try (var jar = new JarFile(packagedJar.toFile())) {
      var attributes = jar.getManifest().getMainAttributes();
      var entries = jar.stream().map(JarEntry::getName).toList();

      assertThat(attributes.getValue(Attributes.Name.MAIN_CLASS))
          .isEqualTo("org.springframework.boot.loader.launch.JarLauncher");
      assertThat(attributes.getValue("Start-Class"))
          .isEqualTo(StreamarrTranscodeWorkerApplication.class.getName());
      assertThat(entries)
          .contains(
              "BOOT-INF/classes/application.yml",
              "BOOT-INF/classes/com/streamarr/transcode/worker/WorkerGrpcConfiguration.class")
          .anyMatch(name -> name.startsWith(BOOT_LIB + "streamarr-transcode-engine-"))
          .anyMatch(name -> name.startsWith(BOOT_LIB + "streamarr-transcode-contract-"))
          .anyMatch(name -> name.startsWith(BOOT_LIB + "grpc-services-"))
          .anyMatch(name -> name.startsWith(BOOT_LIB + "grpc-netty-shaded-"))
          .noneMatch(WorkerPackagedDependenciesIT::isForbiddenDependency);
    }
  }

  private static boolean isForbiddenDependency(String entry) {
    if (!entry.startsWith(BOOT_LIB)) {
      return false;
    }

    var artifact = entry.substring(BOOT_LIB.length());
    return isUnshadedGrpcNetty(artifact)
        || FORBIDDEN_DEPENDENCY_PREFIXES.stream().anyMatch(artifact::startsWith);
  }

  private static boolean isUnshadedGrpcNetty(String artifact) {
    return artifact.startsWith("grpc-netty-") && !artifact.startsWith("grpc-netty-shaded-");
  }
}
