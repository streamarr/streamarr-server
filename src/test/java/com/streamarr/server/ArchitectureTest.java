package com.streamarr.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.services.RootServiceCycleFixture;
import com.streamarr.server.services.architecturefixture.SubdomainServiceCycleFixture;
import com.streamarr.server.services.library.MovieFileProcessor;
import com.streamarr.server.services.library.SeriesFileProcessor;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

@Tag("UnitTest")
@DisplayName("Architecture Rules")
@AnalyzeClasses(
    packages = "com.streamarr.server",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  private static final String SERVICES_PACKAGE = "com.streamarr.server.services";

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

  @ArchTest
  static final ArchRule servicesMustNotDependOnGraphql =
      noClasses()
          .that()
          .resideInAPackage("..services..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..graphql..")
          .as("Services must not depend on graphql");

  @ArchTest
  static final ArchRule authServicesMustNotDependOnJooq =
      noClasses()
          .that()
          .resideInAPackage("..services.auth..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.jooq..")
          .as("Auth services must not depend on jOOQ; DSLContext stays in repositories");

  // Transaction boundaries belong in services. A transactional controller/resolver would run
  // several service calls in one persistence context, where a JPA read can return Hibernate's
  // stale first-level-cache copy of a row a jOOQ write already changed (see AGENTS.md,
  // Persistence).
  private static final String TRANSACTION_BOUNDARY_REASON =
      "Controllers and resolvers must not be @Transactional — transaction boundaries belong in"
          + " services so each call gets its own persistence context";

  @ArchTest
  static final ArchRule controllersAndResolversMustNotBeTransactional =
      noClasses()
          .that()
          .resideInAnyPackage("..controllers..", "..graphql..")
          .should()
          .beAnnotatedWith(Transactional.class)
          .as(TRANSACTION_BOUNDARY_REASON);

  @ArchTest
  static final ArchRule controllerAndResolverMethodsMustNotBeTransactional =
      noMethods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAnyPackage("..controllers..", "..graphql..")
          .should()
          .beAnnotatedWith(Transactional.class)
          .as(TRANSACTION_BOUNDARY_REASON);

  // The library services call the filepath, parsers, streaming, and task services; a dependency
  // back the other way puts them in a cycle. FilepathCodec did exactly that from the library
  // package until it moved to services.filepath.
  @ArchTest
  static final ArchRule servicesBelowLibraryMustNotDependOnLibrary =
      noClasses()
          .that()
          .resideInAnyPackage(
              "..services.filepath..",
              "..services.parsers..",
              "..services.streaming..",
              "..services.task..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..services.library..")
          .as(
              "Filepath, parsers, streaming, and task services must remain below the library"
                  + " services that call them; shared helpers belong in a package both may depend"
                  + " on");

  @ArchTest
  static final ArchRule authRepositoriesMustNotDependOnAuthServices =
      noClasses()
          .that()
          .resideInAPackage("..repositories.auth..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..services.auth..")
          .as("Auth repositories must remain below auth services in the dependency direction");

  @ArchTest
  @DisplayName("Should avoid Path display text when media metadata is processed")
  static void shouldAvoidPathDisplayTextWhenMediaMetadataIsProcessed(JavaClasses classes) {
    noClasses()
        .that()
        .areAssignableTo(MovieFileProcessor.class)
        .or()
        .areAssignableTo(SeriesFileProcessor.class)
        .should()
        .dependOnClassesThat()
        .areAssignableTo(Path.class)
        .as("Media metadata must derive text from the filepath URI, not Path display text")
        .check(classes);
  }

  @ArchTest
  @DisplayName("Should keep service domains acyclic when dependencies cross domain boundaries")
  static void shouldKeepServiceDomainsAcyclicWhenDependenciesCrossDomainBoundaries(
      JavaClasses classes) {
    serviceDomainsMustBeFreeOfCycles().check(classes);
  }

  @Test
  @DisplayName("Should reject service domain cycle when root service depends on subdomain")
  void shouldRejectServiceDomainCycleWhenRootServiceDependsOnSubdomain() {
    var cyclicServiceDomains =
        new ClassFileImporter()
            .importClasses(RootServiceCycleFixture.class, SubdomainServiceCycleFixture.class);

    assertThatThrownBy(() -> serviceDomainsMustBeFreeOfCycles().check(cyclicServiceDomains))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Cycle detected");
  }

  private static ArchRule serviceDomainsMustBeFreeOfCycles() {
    return slices()
        .assignedFrom(new ServiceDomainSliceAssignment())
        .should()
        .beFreeOfCycles()
        .as(
            "Service domains must form a directed acyclic graph — a cycle means neither domain"
                + " can be understood, tested, or extracted without the other, and it lets a"
                + " change in one silently reach back through the other");
  }

  private static final class ServiceDomainSliceAssignment implements SliceAssignment {

    @Override
    public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
      var packageName = javaClass.getPackageName();
      if (packageName.equals(SERVICES_PACKAGE)) {
        return SliceIdentifier.of("root");
      }
      if (!packageName.startsWith(SERVICES_PACKAGE + ".")) {
        return SliceIdentifier.ignore();
      }
      return SliceIdentifier.of(
          packageName.substring(SERVICES_PACKAGE.length() + 1).split("\\.", 2)[0]);
    }

    @Override
    public String getDescription() {
      return "service domains including root services";
    }
  }
}
