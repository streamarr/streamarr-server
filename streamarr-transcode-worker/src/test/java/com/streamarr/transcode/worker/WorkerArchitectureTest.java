package com.streamarr.transcode.worker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("UnitTest")
@DisplayName("Worker Architecture Rules")
@AnalyzeClasses(
    packages = "com.streamarr.transcode.worker",
    importOptions = ImportOption.DoNotIncludeTests.class)
class WorkerArchitectureTest {

  @ArchTest
  static final ArchRule workerMustRemainOutsideServerAndApiPersistenceRuntimes =
      noClasses()
          .that()
          .resideInAPackage("com.streamarr.transcode.worker..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.streamarr.server..",
              "com.netflix.graphql..",
              "graphql..",
              "jakarta.persistence..",
              "org.flywaydb..",
              "org.hibernate..",
              "org.jooq..",
              "org.springframework.data..",
              "org.springframework.orm..",
              "org.springframework.security..",
              "org.springframework.web..",
              "reactor..")
          .as("Worker source must stay outside server, API, persistence, and auth runtimes");
}
