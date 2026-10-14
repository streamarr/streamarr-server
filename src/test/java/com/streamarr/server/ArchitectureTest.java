package com.streamarr.server;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.RECORDS;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.core.domain.properties.HasParameterTypes.Predicates.rawParameterTypes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.architecturefixture.WorkerBoundaryFixtures.BenignRuntimeInspection;
import com.streamarr.server.architecturefixture.WorkerBoundaryFixtures.EngineDependency;
import com.streamarr.server.architecturefixture.WorkerBoundaryFixtures.ProbeDependency;
import com.streamarr.server.architecturefixture.WorkerBoundaryFixtures.ProcessBuilderLaunch;
import com.streamarr.server.architecturefixture.WorkerBoundaryFixtures.ProcessBuilderReference;
import com.streamarr.server.architecturefixture.WorkerBoundaryFixtures.RuntimeArrayCommand;
import com.streamarr.server.architecturefixture.WorkerBoundaryFixtures.RuntimeArrayDirectory;
import com.streamarr.server.architecturefixture.WorkerBoundaryFixtures.RuntimeArrayEnvironment;
import com.streamarr.server.architecturefixture.WorkerBoundaryFixtures.RuntimeMethodReference;
import com.streamarr.server.architecturefixture.WorkerBoundaryFixtures.RuntimeStringCommand;
import com.streamarr.server.architecturefixture.WorkerBoundaryFixtures.RuntimeStringDirectory;
import com.streamarr.server.architecturefixture.WorkerBoundaryFixtures.RuntimeStringEnvironment;
import com.streamarr.server.architecturefixture.WorkerBoundaryFixtures.WorkerDependency;
import com.streamarr.server.config.security.PasswordEncoderConfig;
import com.streamarr.server.controllers.architecturefixture.DirectControllerAccountPasswordMatchFixture;
import com.streamarr.server.graphql.architecturefixture.PasswordEncodingResolverFixture;
import com.streamarr.server.repositories.architecturefixture.RepositoryQueryFixture;
import com.streamarr.server.services.CompanyService;
import com.streamarr.server.services.CreditService;
import com.streamarr.server.services.GenreService;
import com.streamarr.server.services.PersonService;
import com.streamarr.server.services.RootServiceCycleFixture;
import com.streamarr.server.services.architecturefixture.DirectAccountPasswordMatchFixture;
import com.streamarr.server.services.architecturefixture.DirectGenreServiceFixture;
import com.streamarr.server.services.architecturefixture.IncomingHttpServiceFixture;
import com.streamarr.server.services.architecturefixture.IncomingWebRequestServiceFixture;
import com.streamarr.server.services.architecturefixture.SubdomainServiceCycleFixture;
import com.streamarr.server.services.auth.AccountPasswordVerifier;
import com.streamarr.server.services.auth.LoginService;
import com.streamarr.server.services.auth.PasswordTimingEqualizer;
import com.streamarr.server.services.auth.ProfilePinVerifier;
import com.streamarr.server.services.auth.architecturefixture.RequestServiceThreads;
import com.streamarr.server.services.authorization.AuthorizationDecider;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.DirectAuthorizationDeciderFixture;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.services.library.MovieFileProcessor;
import com.streamarr.server.services.library.SeriesFileProcessor;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.CommonForkJoinPool;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.DefaultAsyncExecutor;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.DefaultDelayedExecutor;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.OrdinaryExecutorReference;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.OrdinaryFixedPool;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.OrdinaryForkJoinPool;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.OrdinaryParallelStream;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.OrdinaryParallelStreamSwitch;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.OrdinaryScheduledPoolConstructor;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.OrdinarySpringTaskExecutor;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.OrdinaryThreadBuilder;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.OrdinaryThreadConstructor;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.OrdinaryThreadPoolConstructor;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.OrdinaryTimer;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.OrdinaryVirtualThreadStart;
import com.streamarr.server.services.library.architecturefixture.SystemWorkThreadFixtures.SystemWorkThreads;
import com.streamarr.server.services.streaming.remote.architecturefixture.AllowedWorkerProtocolFixture;
import com.streamarr.transcode.engine.architecturefixture.EngineImplementationFixture;
import com.streamarr.transcode.probe.architecturefixture.ProbeImplementationFixture;
import com.streamarr.transcode.worker.architecturefixture.WorkerImplementationFixture;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.function.Predicate;
import java.util.stream.BaseStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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

  @Test
  @DisplayName("Should exclude worker implementations when importing production code")
  void shouldExcludeWorkerImplementationsWhenImportingProductionCode() {
    var productionClasses =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.streamarr");

    noClasses()
        .that()
        .resideOutsideOfPackage("com.streamarr.transcode.v1..")
        .should()
        .resideInAPackage("com.streamarr.transcode..")
        .as("The server owns the wire contract and contains no worker implementation")
        .check(productionClasses);
  }

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

  private static final ArchRule workerProtocolTypesMustNotLeakOutsideRemotePackage =
      noClasses()
          .that()
          .resideOutsideOfPackage("..services.streaming.remote..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.streamarr.transcode.v1..",
              "com.streamarr.server.services.streaming.remote.protocol..")
          .as("Worker wire-protocol types must not be used outside services.streaming.remote");

  private static final ArchRule serverClassesMustNotDependOnProcessBuilder =
      noClasses()
          .that()
          .resideInAPackage("com.streamarr.server..")
          .should()
          .dependOnClassesThat(equivalentTo(ProcessBuilder.class))
          .as("Server classes must not depend on ProcessBuilder");

  private static final ArchRule serverClassesMustNotExecuteRuntimeProcesses =
      noClasses()
          .that()
          .resideInAPackage("com.streamarr.server..")
          .should()
          .accessTargetWhere(
              JavaAccess.Predicates.target(owner(equivalentTo(Runtime.class)))
                  .and(JavaAccess.Predicates.target(name("exec"))))
          .as("Server classes must not execute processes through Runtime.exec");

  private static final ArchRule serverClassesMustNotDependOnWorkerImplementations =
      noClasses()
          .that()
          .resideInAPackage("com.streamarr.server..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.streamarr.transcode.engine..",
              "com.streamarr.transcode.probe..",
              "com.streamarr.transcode.worker..")
          .as("Server classes must not depend on worker execution implementations");

  @ArchTest
  static final ArchRule serverWorkerBoundary =
      CompositeArchRule.of(workerProtocolTypesMustNotLeakOutsideRemotePackage)
          .and(serverClassesMustNotDependOnProcessBuilder)
          .and(serverClassesMustNotExecuteRuntimeProcesses)
          .and(serverClassesMustNotDependOnWorkerImplementations);

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
  static final ArchRule servicesMustNotDependOnDrivingAdapterTypes =
      noClasses()
          .that()
          .resideInAPackage("..services..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "jakarta.servlet..",
              "com.netflix.graphql.dgs..",
              "graphql..",
              "org.springframework.http..",
              "org.springframework.web.context.request..")
          .as("Services receive protocol data explicitly and must not depend on HTTP or DGS types");

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
  static final ArchRule controllersAndResolversMustNotAuthorizeUseCases =
      noClasses()
          .that()
          .resideInAnyPackage("..controllers..", "..graphql..")
          .should()
          .callMethodWhere(
              target(owner(assignableTo(AuthorizationService.class)))
                  .and(target(name("decide").or(name("requireAllowed")))))
          .as(
              "Resolvers and controllers adapt identity and protocol data; services authorize"
                  + " use cases");

  @ArchTest
  static final ArchRule servicesMustReceiveAuthenticatedIdentityFromAdapters =
      noClasses()
          .that()
          .resideInAPackage("..services..")
          .and()
          .resideOutsideOfPackage("..services.authorization..")
          .should()
          .callMethodWhere(
              target(owner(assignableTo(AuthorizationService.class)))
                  .and(target(name("currentIdentity"))))
          .as(
              "Application services receive authenticated identity explicitly from adapters;"
                  + " only the authorization facade resolves it from the security context");

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

  // A media title's people, genres and studios are saved together so that every save inserts
  // them in the order CreditService defines; a direct caller could insert them in another order.
  @ArchTest
  static final ArchRule onlyCreditServiceMaySaveCredits = onlyCreditServiceMaySaveCredits();

  // Authenticated Account-password checks share one throttle and timing contract. Login is exempt
  // because no authenticated Account exists yet.
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

  // ADR 0024 package direction: services.identity → services.authorization → services.auth.
  // Authentication, sessions, token issuance, and credential verification never reach back into
  // authorization or identity; the authorization module never imports identity.
  @ArchTest
  static final ArchRule authMustNotDependOnAuthorizationOrIdentity =
      noClasses()
          .that()
          .resideInAPackage("..services.auth..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..services.authorization..", "..services.identity..")
          .as("services.auth is the bottom of the identity stack");

  @ArchTest
  static final ArchRule authorizationMustNotDependOnIdentity =
      noClasses()
          .that()
          .resideInAPackage("..services.authorization..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..services.identity..")
          .as("services.authorization decides; it never calls the identity workflows above it");

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

  // Read models are named *Details (AccountInvitationDetails, SeasonDetails): they describe the
  // data a client shapes, not a rendered screen.
  @ArchTest
  static final ArchRule presentationDtosMustDescribeDetailsRatherThanViews =
      noClasses()
          .that(
              resideInAPackage("..graphql.dto..").or(resideInAPackage("..services..").and(RECORDS)))
          .should()
          .haveSimpleNameEndingWith("View")
          .as(
              "Presentation DTOs and service records are read models named *Details and must not"
                  + " use the View suffix");

  // A thread does not inherit the System actor binding of the thread that starts it, so a thread
  // that system work starts any other way writes rows with an empty auditor.
  @ArchTest
  static final ArchRule systemWorkMustStartThreadsThroughSystemWork =
      systemWorkMustStartThreadsThroughSystemWork();

  @ParameterizedTest
  @ValueSource(
      classes = {
        OrdinaryVirtualThreadStart.class,
        OrdinaryThreadBuilder.class,
        OrdinaryThreadConstructor.class,
        OrdinaryExecutorReference.class,
        OrdinaryFixedPool.class,
        DefaultAsyncExecutor.class,
        OrdinaryParallelStream.class,
        OrdinaryParallelStreamSwitch.class,
        CommonForkJoinPool.class,
        OrdinaryForkJoinPool.class,
        OrdinaryThreadPoolConstructor.class,
        OrdinaryScheduledPoolConstructor.class,
        DefaultDelayedExecutor.class,
        OrdinarySpringTaskExecutor.class,
        OrdinaryTimer.class
      })
  @DisplayName("Should reject a thread when system work starts it outside SystemWork")
  void shouldRejectThreadWhenSystemWorkStartsItOutsideSystemWork(Class<?> fixture) {
    var classes = new ClassFileImporter().importClasses(fixture);

    assertThat(
            systemWorkMustStartThreadsThroughSystemWork()
                .evaluate(classes)
                .getFailureReport()
                .getDetails())
        .anySatisfy(detail -> assertThat(detail).contains(fixture.getName()));
  }

  // Requests are not system work, so the rule leaves the threads they start alone.
  @Test
  @DisplayName("Should allow an ordinary thread when a request service starts it")
  void shouldAllowOrdinaryThreadWhenRequestServiceStartsIt() {
    var classes =
        new ClassFileImporter().importClasses(RequestServiceThreads.class, SystemWorkThreads.class);

    assertThat(
            systemWorkMustStartThreadsThroughSystemWork()
                .evaluate(classes)
                .getFailureReport()
                .getDetails())
        .isEmpty();
  }

  @Test
  @DisplayName("Should allow threads when system work starts them through SystemWork")
  void shouldAllowThreadsWhenSystemWorkStartsThemThroughSystemWork() {
    var classes = new ClassFileImporter().importClasses(SystemWorkThreads.class);

    assertThat(
            systemWorkMustStartThreadsThroughSystemWork()
                .evaluate(classes)
                .getFailureReport()
                .getDetails())
        .isEmpty();
  }

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
  @DisplayName("Should reject a direct genre save when a service bypasses the credit service")
  void shouldRejectADirectGenreSaveWhenAServiceBypassesTheCreditService() {
    var directGenreSave = new ClassFileImporter().importClasses(DirectGenreServiceFixture.class);

    assertThatThrownBy(() -> onlyCreditServiceMaySaveCredits().check(directGenreSave))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("GenreService");
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

  @ParameterizedTest
  @ValueSource(classes = {IncomingHttpServiceFixture.class, IncomingWebRequestServiceFixture.class})
  @DisplayName("Should reject request types when a service depends on an HTTP adapter")
  void shouldRejectRequestTypesWhenServiceDependsOnHttpAdapter(Class<?> fixture) {
    var services = new ClassFileImporter().importClasses(fixture);
    assertThatThrownBy(() -> servicesMustNotDependOnDrivingAdapterTypes.check(services))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("HTTP");
  }

  @ParameterizedTest
  @ValueSource(classes = ProcessBuilderLaunch.class)
  @DisplayName("Should reject ProcessBuilder dependencies when server code launches a process")
  void shouldRejectProcessBuilderDependenciesWhenServerCodeLaunchesProcess(Class<?> fixture) {
    var classes = new ClassFileImporter().importClasses(fixture);

    assertThat(serverWorkerBoundary.evaluate(classes).getFailureReport().getDetails())
        .anySatisfy(
            detail ->
                assertThat(detail).contains(fixture.getName(), ProcessBuilder.class.getName()));
  }

  @ParameterizedTest
  @ValueSource(
      classes = {
        RuntimeArrayCommand.class, RuntimeArrayEnvironment.class, RuntimeArrayDirectory.class,
        RuntimeStringCommand.class, RuntimeStringEnvironment.class, RuntimeStringDirectory.class,
        RuntimeMethodReference.class
      })
  @DisplayName("Should reject Runtime execution when server code launches a process")
  void shouldRejectRuntimeExecutionWhenServerCodeLaunchesProcess(Class<?> fixture) {
    var classes = new ClassFileImporter().importClasses(fixture);

    assertThat(serverWorkerBoundary.evaluate(classes).getFailureReport().getDetails())
        .singleElement()
        .satisfies(
            detail ->
                assertThat(detail).contains(fixture.getName(), Runtime.class.getName() + ".exec("));
  }

  @ParameterizedTest
  @MethodSource("executionDependencies")
  @DisplayName(
      "Should reject implementation dependencies when server code references execution types")
  void shouldRejectImplementationDependenciesWhenServerCodeReferencesExecutionTypes(
      Class<?> fixture, Class<?> executionType) {
    var classes = new ClassFileImporter().importClasses(fixture);

    assertThat(serverWorkerBoundary.evaluate(classes).getFailureReport().getDetails())
        .anySatisfy(
            detail -> assertThat(detail).contains(fixture.getName(), executionType.getName()));
  }

  @Test
  @DisplayName("Should allow protocol and runtime inspection when server code avoids execution")
  void shouldAllowProtocolAndRuntimeInspectionWhenServerCodeAvoidsExecution() {
    var classes =
        new ClassFileImporter()
            .importClasses(BenignRuntimeInspection.class, AllowedWorkerProtocolFixture.class);

    assertThat(serverWorkerBoundary.evaluate(classes).getFailureReport().getDetails()).isEmpty();
  }

  private static Stream<Arguments> executionDependencies() {
    return Stream.of(
        Arguments.of(ProcessBuilderReference.class, ProcessBuilder.class),
        Arguments.of(EngineDependency.class, EngineImplementationFixture.class),
        Arguments.of(ProbeDependency.class, ProbeImplementationFixture.class),
        Arguments.of(WorkerDependency.class, WorkerImplementationFixture.class));
  }

  private static ArchRule systemWorkMustStartThreadsThroughSystemWork() {
    return noClasses()
        .that()
        .resideInAnyPackage(
            "com.streamarr.server.services",
            "com.streamarr.server.services.library..",
            "com.streamarr.server.config..")
        .should()
        .accessTargetWhere(new StartsThreadOutsideSystemWork())
        .as(
            "Scans, refreshes, the file watcher, artwork and scheduled tasks start threads through"
                + " SystemWork, so system work keeps the System actor on every thread it starts");
  }

  private static final class StartsThreadOutsideSystemWork
      extends DescribedPredicate<JavaAccess<?>> {

    private static final String CONSTRUCTOR = "<init>";

    // Each source runs work on a thread that does not carry the System actor binding.
    private static final List<ThreadSource> THREAD_SOURCES =
        List.of(
            new ThreadSource(
                Thread.class,
                target ->
                    Set.of("ofVirtual", "ofPlatform", "startVirtualThread", CONSTRUCTOR)
                        .contains(target.getName())),
            new ThreadSource(
                Executors.class,
                target ->
                    target.getName().equals("defaultThreadFactory")
                        || (target.getName().startsWith("new")
                            && !takes(target, ThreadFactory.class))),
            new ThreadSource(
                Executor.class,
                target ->
                    target.getName().equals(CONSTRUCTOR) && !takes(target, ThreadFactory.class)),
            new ThreadSource(ForkJoinPool.class, target -> target.getName().equals("commonPool")),
            new ThreadSource(
                CompletableFuture.class,
                target ->
                    (target.getName().endsWith("Async")
                            || target.getName().equals("delayedExecutor"))
                        && !takes(target, Executor.class)),
            new ThreadSource(Collection.class, target -> target.getName().equals("parallelStream")),
            new ThreadSource(BaseStream.class, target -> target.getName().equals("parallel")),
            new ThreadSource(Timer.class, target -> target.getName().equals(CONSTRUCTOR)));

    private StartsThreadOutsideSystemWork() {
      super("start a thread outside SystemWork");
    }

    @Override
    public boolean test(JavaAccess<?> access) {
      if (!(access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target)) {
        return false;
      }

      return THREAD_SOURCES.stream().anyMatch(source -> source.startsThread(target));
    }

    private static boolean takes(AccessTarget.CodeUnitAccessTarget target, Class<?> type) {
      return target.getRawParameterTypes().stream().anyMatch(param -> param.isEquivalentTo(type));
    }

    private record ThreadSource(
        Class<?> owner, Predicate<AccessTarget.CodeUnitAccessTarget> startsThreadThrough) {

      boolean startsThread(AccessTarget.CodeUnitAccessTarget target) {
        return target.getOwner().isAssignableTo(owner) && startsThreadThrough.test(target);
      }
    }
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
            PasswordTimingEqualizer.class,
            ProfilePinVerifier.class)
        .should()
        .callMethodWhere(
            target(owner(assignableTo(PasswordEncoder.class)))
                .and(target(name("matches")))
                .and(target(rawParameterTypes(CharSequence.class, String.class))))
        .as(
            "Authenticated Account password checks must go through AccountPasswordVerifier; only"
                + " login, password-encoder composition, the timing equalizer, and the Profile"
                + " PIN verifier (distinct PROFILE_PIN target)"
                + " compare directly");
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

  private static ArchRule onlyCreditServiceMaySaveCredits() {
    return noClasses()
        .that()
        .doNotBelongToAnyOf(CreditService.class)
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(PersonService.class, GenreService.class, CompanyService.class)
        .as("Only CreditService may save people, genres and studios");
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
