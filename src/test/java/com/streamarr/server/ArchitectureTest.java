package com.streamarr.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;

@Tag("UnitTest")
@AnalyzeClasses(
    packages = "com.streamarr.server",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domainMustNotDependOnOuterLayers =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..services..", "..repositories..", "..graphql..")
          .as("Domain must not depend on services, repositories, or graphql");

  @ArchTest
  static final ArchRule tmdbTypesMustNotLeakOutsideMetadata =
      noClasses()
          .that()
          .resideOutsideOfPackage("..services.metadata..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..services.metadata.tmdb..")
          .as("TMDB types must not be used outside metadata package");

  @ArchTest
  static final ArchRule controllersMustNotDependOnRepositories =
      noClasses()
          .that()
          .resideInAPackage("..controllers..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..repositories..")
          .as("Controllers must not depend on repositories");
}
