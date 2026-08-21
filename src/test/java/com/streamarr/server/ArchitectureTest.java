package com.streamarr.server;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.core.domain.properties.HasParameterTypes.Predicates.rawParameterTypes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.PasswordEncoderConfig;
import com.streamarr.server.controllers.architecturefixture.DirectControllerAccountPasswordMatchFixture;
import com.streamarr.server.graphql.architecturefixture.PasswordEncodingResolverFixture;
import com.streamarr.server.repositories.architecturefixture.RepositoryQueryFixture;
import com.streamarr.server.services.RootServiceCycleFixture;
import com.streamarr.server.services.architecturefixture.DirectAccountPasswordMatchFixture;
import com.streamarr.server.services.architecturefixture.SubdomainServiceCycleFixture;
import com.streamarr.server.services.auth.AccountPasswordVerifier;
import com.streamarr.server.services.auth.LoginService;
import com.streamarr.server.services.auth.PasswordTimingEqualizer;
import com.streamarr.server.services.authorization.AuthorizationDecider;
import com.streamarr.server.services.authorization.DirectAuthorizationDeciderFixture;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
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
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
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
  static final ArchRule workerProtocolTypesMustNotLeakOutsideRemotePackage =
      noClasses()
          .that()
          .resideOutsideOfPackage("..services.streaming.remote..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.streamarr.transcode.v1..", "com.streamarr.transcode.protocol..")
          .as("Worker wire-protocol types must not be used outside services.streaming.remote");

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
  static final ArchRule repositoryMethodsMustNotUseQueryAnnotations =
      repositoryMethodsMustNotUseQueryAnnotations();

  // Every authenticated Account-password check must get the shared budget, the disabled-Account
  // rule, and the one-full-cost-operation timing rule; a direct encoder comparison gets none of
  // them. Login is the allow-listed exception (no authenticated Account yet, email+source key),
  // and the equalizer is the burn itself.
  @ArchTest
  static final ArchRule accountPasswordMatchesMustUseVerifier =
      accountPasswordMatchesMustUseVerifier();

  // GraphQL adapts input to services; if a resolver could reach the encoder it could hash or
  // compare a password and own policy the services are supposed to own.
  @ArchTest
  static final ArchRule graphqlMustNotOwnPasswordPolicy = graphqlMustNotOwnPasswordPolicy();

  // Cedar is an implementation detail of the authorization module (ADR 0025): if any other
  // package could reach the engine or the native bridge it could assemble facts its own way.
  @ArchTest
  static final ArchRule onlyTheCedarPackageMayImportCedar =
      noClasses()
          .that()
          .resideOutsideOfPackage("..services.authorization.cedar..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.cedarpolicy..", "com.fizzed.jne..")
          .as("Only services.authorization.cedar may import Cedar or JNE");

  // The facade is the single decision point; only it and the engine implementation know the
  // decider interface.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ArchTest
  static final ArchRule onlyTheFacadeMayKnowTheDecider =
      noClasses()
          .that()
          .doNotBelongToAnyOf(SecurityContextAuthorizationService.class)
          .and()
          .doNotHaveFullyQualifiedName(
              "com.streamarr.server.services.authorization.cedar.CedarAuthorizationDecider")
          .should()
          .dependOnClassesThat()
          .areAssignableTo(AuthorizationDecider.class)
          .as(
              "Only SecurityContextAuthorizationService and CedarAuthorizationDecider know"
                  + " AuthorizationDecider");

  // Actions, checks, slices, and contributors stay inside the engine package so no caller can
  // name a Cedar action or supply its own authority facts.
  @ArchTest
  static final ArchRule cedarInternalsMustStayPackagePrivate =
      noClasses()
          .that()
          .resideInAPackage("..services.authorization.cedar..")
          .and()
          .doNotHaveSimpleName("CedarEngineSelfCheck")
          .and()
          .doNotHaveSimpleName("CedarEngineSelfCheckLauncher")
          .and()
          .doNotHaveSimpleName("CedarSelfCheckException")
          .and()
          .areTopLevelClasses()
          .should()
          .bePublic()
          .as("Cedar engine types are package-private; only the image self-check is public");

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
    var serviceDomainRule = serviceDomainsMustBeFreeOfCycles();

    assertThatThrownBy(() -> serviceDomainRule.check(cyclicServiceDomains))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Cycle detected");
  }

  @Test
  @DisplayName("Should reject authorization-module classes that bypass the facade")
  void shouldRejectAuthorizationModuleClassesThatBypassTheFacade() {
    var directDeciderDependency =
        new ClassFileImporter()
            .importClasses(DirectAuthorizationDeciderFixture.class, AuthorizationDecider.class);

    assertThat(
            onlyTheFacadeMayKnowTheDecider
                .allowEmptyShould(true)
                .evaluate(directDeciderDependency)
                .hasViolation())
        .isTrue();
  }

  @Test
  @DisplayName("Should reject @Query annotations when repository methods use JPQL")
  void shouldRejectQueryAnnotationsWhenRepositoryMethodsUseJpql() {
    var repositoryWithQueryAnnotation =
        new ClassFileImporter().importClasses(RepositoryQueryFixture.class);
    var repositoryRule = repositoryMethodsMustNotUseQueryAnnotations();

    assertThatThrownBy(() -> repositoryRule.check(repositoryWithQueryAnnotation))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Query");
  }

  @Test
  @DisplayName(
      "Should reject direct Account password matching when a service bypasses the verifier")
  void shouldRejectDirectAccountPasswordMatchingWhenServiceBypassesVerifier() {
    var directMatcher =
        new ClassFileImporter().importClasses(DirectAccountPasswordMatchFixture.class);
    var verifierRule = accountPasswordMatchesMustUseVerifier();

    assertThatThrownBy(() -> verifierRule.check(directMatcher))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("AccountPasswordVerifier");
  }

  @Test
  @DisplayName("Should reject direct Account password matching when a controller bypasses services")
  void shouldRejectDirectAccountPasswordMatchingWhenControllerBypassesServices() {
    var directMatcher =
        new ClassFileImporter().importClasses(DirectControllerAccountPasswordMatchFixture.class);
    var verifierRule = accountPasswordMatchesMustUseVerifier();

    assertThatThrownBy(() -> verifierRule.check(directMatcher))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("AccountPasswordVerifier");
  }

  @Test
  @DisplayName("Should reject password encoder dependencies when a resolver owns password policy")
  void shouldRejectPasswordEncoderDependenciesWhenResolverOwnsPasswordPolicy() {
    var encodingResolver =
        new ClassFileImporter().importClasses(PasswordEncodingResolverFixture.class);
    var graphqlRule = graphqlMustNotOwnPasswordPolicy();

    assertThatThrownBy(() -> graphqlRule.check(encodingResolver))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("password");
  }

  private static ArchRule accountPasswordMatchesMustUseVerifier() {
    return noClasses()
        .that()
        .resideInAPackage("com.streamarr.server..")
        .and()
        .doNotBelongToAnyOf(
            AccountPasswordVerifier.class,
            LoginService.class,
            PasswordEncoderConfig.class,
            PasswordTimingEqualizer.class)
        .should()
        .callMethodWhere(
            target(owner(assignableTo(PasswordEncoder.class)))
                .and(target(name("matches")))
                .and(target(rawParameterTypes(CharSequence.class, String.class))))
        .as(
            "Authenticated Account password checks must go through AccountPasswordVerifier; only"
                + " login (distinct email+source throttle), password-encoder composition, and the"
                + " timing equalizer compare directly");
  }

  private static ArchRule graphqlMustNotOwnPasswordPolicy() {
    return noClasses()
        .that()
        .resideInAPackage("..graphql..")
        .should()
        .dependOnClassesThat()
        .areAssignableTo(PasswordEncoder.class)
        .as("GraphQL adapts input; services own password and PIN policy");
  }

  private static ArchRule repositoryMethodsMustNotUseQueryAnnotations() {
    return noMethods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("..repositories..")
        .should()
        .beAnnotatedWith(Query.class)
        .as("Repository queries must use derived methods or jOOQ custom fragments");
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
