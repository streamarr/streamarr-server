package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.fixtures.MediaEntityFixture.buildMovie;
import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.InputArgument;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dataloaders.SelectedProfileDataLoader;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.identity.IdentityQueryService;
import com.streamarr.server.services.identity.SelectableProfilesList;
import com.streamarr.server.services.identity.UsableHouseholdsList;
import com.streamarr.server.services.pagination.PaginationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(
    classes = {
      SelectedProfileStateResolver.class,
      SelectedProfileDataLoader.class,
      MeResolver.class,
      PaginationService.class,
      PositionCursorCodec.class,
      RelayConnectionAdapter.class,
      JacksonAutoConfiguration.class,
      StreamarrDataFetcherExceptionHandler.class,
      SelectedProfileStateResolverTest.Fakes.class,
      SelectedProfileStateResolverTest.UngatedMovieRoot.class
    })
@DisplayName("Selected Profile State Resolver Tests")
class SelectedProfileStateResolverTest {

  private static final String STATE_FIELDS =
      """
      selectedProfileState {
        profile { id name selected }
        watchState { watchStatus watchProgress { positionSeconds } lastWatchedAt }
        languagePreference { audioLanguages }
      }
      """;

  @Autowired private DgsQueryExecutor executor;
  @Autowired private ObjectMapper mapper;
  @Autowired private AtomicReference<AuthenticatedIdentity> identity;
  @Autowired private FakeAuthorizationService authorization;
  @Autowired private FakeMovieRepository movies;
  @Autowired private FakeHouseholdRepository households;
  @Autowired private FakeUserAccountRepository accounts;
  @Autowired private ReadCountingProfileRepository profiles;
  @Autowired private FakeProfileHouseholdShareRepository shares;

  private Household home;
  private UserAccount serverAdmin;
  private Profile personal;
  private Movie movie;

  // Every real entry point to a media title requires a selected Profile. This root stands in for
  // one that does not, such as the media of an active stream.
  @DgsComponent
  static class UngatedMovieRoot {

    @Autowired private FakeMovieRepository movies;

    @DgsQuery
    public Movie movie(@InputArgument String id) {
      return movies.findById(UUID.fromString(id)).orElse(null);
    }
  }

  static class ReadCountingProfileRepository extends FakeProfileRepository {

    private final AtomicInteger availableProfileReads = new AtomicInteger();

    ReadCountingProfileRepository(FakeProfileHouseholdShareRepository shares) {
      super(shares);
    }

    int availableProfileReads() {
      return availableProfileReads.get();
    }

    @Override
    public List<Profile> findAvailableInHousehold(UUID householdId) {
      availableProfileReads.incrementAndGet();
      return super.findAvailableInHousehold(householdId);
    }
  }

  @TestConfiguration
  static class Fakes {

    @Bean
    AtomicReference<AuthenticatedIdentity> identity() {
      return new AtomicReference<>();
    }

    @Bean
    FakeAuthorizationService authorizationService(AtomicReference<AuthenticatedIdentity> identity) {
      return new FakeAuthorizationService(identity::get);
    }

    @Bean
    FakeMovieRepository movies() {
      return new FakeMovieRepository();
    }

    @Bean
    FakeProfileHouseholdShareRepository shares() {
      return new FakeProfileHouseholdShareRepository();
    }

    @Bean
    ReadCountingProfileRepository profiles(FakeProfileHouseholdShareRepository shares) {
      return new ReadCountingProfileRepository(shares);
    }

    @Bean
    FakeUserAccountRepository accounts(FakeProfileHouseholdShareRepository shares) {
      return new FakeUserAccountRepository(shares);
    }

    @Bean
    FakeHouseholdRepository households(FakeUserAccountRepository accounts) {
      return new FakeHouseholdRepository(accounts);
    }

    @Bean
    IdentityQueryService identityQueryService(
        FakeUserAccountRepository accounts,
        FakeHouseholdRepository households,
        ReadCountingProfileRepository profiles,
        FakeAuthorizationService authorizationService) {
      return new IdentityQueryService(
          accounts,
          households,
          profiles,
          authorizationService,
          new SelectableProfilesList(profiles),
          new UsableHouseholdsList(households));
    }
  }

  @BeforeEach
  void signInAServerAdminWithAPersonalProfileAndOneMovie() {
    authorization.allowAll();
    home = households.save(HouseholdFixture.defaultHouseholdBuilder().name("Home").build());
    serverAdmin =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(home.getId())
                .householdRole(HouseholdRole.ADMIN)
                .serverAdmin(true)
                .build());
    personal =
        profiles.save(
            ProfileFixture.defaultProfileBuilder()
                .id(serverAdmin.getPersonalProfileId())
                .householdId(home.getId())
                .name("Admin")
                .build());
    shares.share(personal.getId(), home.getId(), true);
    movie = movies.save(buildMovie("Movie"));
  }

  @AfterEach
  void forgetTheAccountAndTheMovie() {
    movies.deleteAll();
    profiles.deleteAll();
    accounts.deleteAll();
    households.deleteAll();
    shares.deleteAll();
  }

  @Test
  @DisplayName(
      "Should return a null selected Profile state without an error when the Account session has"
          + " no selected Profile")
  void
      shouldReturnANullSelectedProfileStateWithoutAnErrorWhenTheAccountSessionHasNoSelectedProfile() {
    identity.set(session(null));

    var response =
        execute("{ movie(id: \"%s\") { title %s } }".formatted(movie.getId(), STATE_FIELDS));

    assertThat(response.has("errors")).as("errors in %s", response).isFalse();
    assertThat(response.at("/data/movie/title").asString()).isEqualTo("Movie");
    assertThat(response.at("/data/movie/selectedProfileState").isNull()).isTrue();
  }

  @Test
  @DisplayName("Should return the selected Profile when the session has a selected Profile")
  void shouldReturnTheSelectedProfileWhenTheSessionHasASelectedProfile() {
    var listedFirst =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(home.getId()).name("Abby").build());
    shares.share(listedFirst.getId(), home.getId(), true);
    identity.set(session(personal.getId()));

    var response =
        execute(
            "{ movie(id: \"%s\") { selectedProfileState { profile { id name selected } } } }"
                .formatted(movie.getId()));

    assertThat(response.has("errors")).as("errors in %s", response).isFalse();
    assertThat(response.at("/data/movie/selectedProfileState/profile"))
        .isEqualTo(
            mapper.valueToTree(
                Map.of("id", personal.getId().toString(), "name", "Admin", "selected", true)));
  }

  @Test
  @DisplayName("Should read the selected Profile once when a response holds several titles")
  void shouldReadTheSelectedProfileOnceWhenAResponseHoldsSeveralTitles() {
    var second = movies.save(buildMovie("Second"));
    var third = movies.save(buildMovie("Third"));
    identity.set(session(personal.getId()));
    var readsBefore = profiles.availableProfileReads();

    var response =
        execute(
            """
            {
              first: movie(id: "%s") { selectedProfileState { profile { id } } }
              second: movie(id: "%s") { selectedProfileState { profile { id } } }
              third: movie(id: "%s") { selectedProfileState { profile { id } } }
            }
            """
                .formatted(movie.getId(), second.getId(), third.getId()));

    assertThat(response.has("errors")).as("errors in %s", response).isFalse();
    assertThat(List.of("first", "second", "third"))
        .allSatisfy(
            alias ->
                assertThat(
                        response
                            .at("/data/" + alias + "/selectedProfileState/profile/id")
                            .asString())
                    .isEqualTo(personal.getId().toString()));
    assertThat(profiles.availableProfileReads() - readsBefore).isOne();
  }

  @Test
  @DisplayName("Should null only the selected Profile state when a non-null field under it fails")
  void shouldNullOnlyTheSelectedProfileStateWhenANonNullFieldUnderItFails() {
    identity.set(session(personal.getId()));
    authorization.denyAll();

    var response =
        execute(
            "{ movie(id: \"%s\") { title selectedProfileState { profile { id } } } }"
                .formatted(movie.getId()));

    assertThat(response.at("/data/movie/title").asString()).isEqualTo("Movie");
    assertThat(response.at("/data/movie/selectedProfileState").isNull()).isTrue();
    assertThat(response.at("/errors/0/path"))
        .isEqualTo(mapper.valueToTree(List.of("movie", "selectedProfileState", "profile")));
  }

  private AuthenticatedIdentity session(UUID profileId) {
    return AuthenticatedIdentity.builder()
        .accountId(serverAdmin.getId())
        .authSessionId(UUID.randomUUID())
        .scope(profileId == null ? TokenScope.ACCOUNT : TokenScope.PROFILE)
        .householdId(home.getId())
        .householdRole(HouseholdRole.ADMIN)
        .contextHouseholdId(home.getId())
        .profileId(profileId)
        .build();
  }

  private JsonNode execute(String document) {
    return mapper.valueToTree(executor.execute(document).toSpecification());
  }
}
