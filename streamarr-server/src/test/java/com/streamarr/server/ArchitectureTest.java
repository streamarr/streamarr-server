package com.streamarr.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.repositories.auth.AuthSessionRepositoryCustom;
import com.streamarr.server.repositories.auth.RefreshTokenRepositoryCustom;
import com.streamarr.server.repositories.streaming.StreamSessionEnforcementRepository;
import com.streamarr.server.services.auth.SessionRevocationService;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.transaction.annotation.Transactional;

@Tag("UnitTest")
@DisplayName("Architecture Rules")
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
  static final ArchRule authSessionRevocationMustUseTheAtomicService =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .areAssignableTo(AuthSessionRepositoryCustom.class)
          .and()
          .haveName("revoke")
          .and()
          .haveRawParameterTypes(UUID.class, SessionRevocationReason.class, Instant.class)
          .should()
          .onlyBeCalled()
          .byClassesThat()
          .haveFullyQualifiedName(SessionRevocationService.class.getName())
          .as("Auth-session revocation must go through SessionRevocationService");

  @ArchTest
  static final ArchRule refreshFamilyRevocationMustUseTheAtomicService =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .areAssignableTo(RefreshTokenRepositoryCustom.class)
          .and()
          .haveName("revokeAllForSession")
          .and()
          .haveRawParameterTypes(UUID.class, Instant.class)
          .should()
          .onlyBeCalled()
          .byClassesThat()
          .haveFullyQualifiedName(SessionRevocationService.class.getName())
          .as("Refresh-family revocation must go through SessionRevocationService");

  @ArchTest
  static final ArchRule authStreamTerminationMustUseTheAtomicService =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .areAssignableTo(StreamSessionEnforcementRepository.class)
          .and()
          .haveName("terminalizeByAuthSession")
          .and()
          .haveRawParameterTypes(UUID.class, Instant.class)
          .should()
          .onlyBeCalled()
          .byClassesThat()
          .haveFullyQualifiedName(SessionRevocationService.class.getName())
          .as("Auth-session stream termination must go through SessionRevocationService");
}
