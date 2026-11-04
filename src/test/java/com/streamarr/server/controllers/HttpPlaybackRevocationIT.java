package com.streamarr.server.controllers;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.directPlaySessionBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.support.AuthTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies that the HTTP boundary enforces live playback authority through the real service and
 * PostgreSQL. Unlike {@code StreamControllerIT}, no streaming beans are stubbed — this drives
 * {@code StreamController} → {@code HlsStreamingService.accessSession} → {@code
 * LivePlaybackAuthorityGate} → the live authority query.
 */
@Tag("IntegrationTest")
@DisplayName("HTTP Playback Revocation Integration Tests")
class HttpPlaybackRevocationIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private RuntimeStreamSessionRegistry runtimeRegistry;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private PlaybackTokenIssuer playbackTokenIssuer;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private TransactionTemplate transactionTemplate;

  @TempDir private Path directory;

  private AuthTestSupport.TestIdentity identity;
  private AuthTestSupport.TestIdentity host;
  private StreamSession streamSession;

  @BeforeEach
  void setUp() {
    identity = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    if (streamSession != null) {
      runtimeRegistry.removeById(streamSession.getSessionId());
    }

    if (host != null) {
      authTestSupport.deleteIdentity(host);
    }

    authTestSupport.deleteIdentity(identity);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(StreamDelivery.class)
  @DisplayName("Should deny the next request over HTTP when logout revokes the auth session")
  void shouldDenyNextRequestOverHttpWhenLogoutRevokesAuthSession(StreamDelivery delivery)
      throws Exception {
    var token = startStreamSession(delivery, authenticatedIdentity());
    assertMediaAvailable(token);

    authSessionRepository.revoke(
        identity.session().getId(), SessionRevocationReason.LOGOUT, Instant.now());

    assertMediaDenied(token);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(StreamDelivery.class)
  @DisplayName("Should deny the next request over HTTP when the Account is disabled")
  void shouldDenyNextRequestOverHttpWhenAccountIsDisabled(StreamDelivery delivery)
      throws Exception {
    var token = startStreamSession(delivery, authenticatedIdentity());
    assertMediaAvailable(token);

    var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
    account.setEnabled(false);
    userAccountRepository.saveAndFlush(account);

    assertMediaDenied(token);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(StreamDelivery.class)
  @DisplayName("Should deny the next request over HTTP when the selected Profile is cleared")
  void shouldDenyNextRequestOverHttpWhenSelectedProfileIsCleared(StreamDelivery delivery)
      throws Exception {
    var token = startStreamSession(delivery, authenticatedIdentity());
    assertMediaAvailable(token);

    var session = authSessionRepository.findById(identity.session().getId()).orElseThrow();
    session.setSelectedProfileId(null);
    authSessionRepository.saveAndFlush(session);

    assertMediaDenied(token);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(StreamDelivery.class)
  @DisplayName("Should deny the next request over HTTP when the selected Profile share ends")
  void shouldDenyNextRequestOverHttpWhenSelectedProfileShareEnds(StreamDelivery delivery)
      throws Exception {
    host = authTestSupport.createIdentity();
    var managedShare = createManagedProfileShare();
    share(identity.profile().getId(), host.household().getId());
    var visiting = useHousehold(host.household().getId(), managedShare.getProfileId());
    var token = startStreamSession(delivery, visiting);
    assertMediaAvailable(token);

    end(managedShare);

    assertMediaDenied(token);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(StreamDelivery.class)
  @DisplayName("Should deny the next request over HTTP when Household access ends")
  void shouldDenyNextRequestOverHttpWhenHouseholdAccessEnds(StreamDelivery delivery)
      throws Exception {
    host = authTestSupport.createIdentity();
    var visit = share(identity.profile().getId(), host.household().getId());
    var visiting = useHousehold(host.household().getId(), host.profile().getId());
    var token = startStreamSession(delivery, visiting);
    assertMediaAvailable(token);

    end(visit);

    assertMediaDenied(token);
  }

  private AuthenticatedIdentity authenticatedIdentity() {
    return AuthenticatedIdentity.fromJwt(
        jwtDecoder.decode(authTestSupport.profileBearer(identity)));
  }

  private String startStreamSession(
      StreamDelivery delivery, AuthenticatedIdentity authenticatedIdentity) throws IOException {
    var session =
        switch (delivery) {
          case HLS -> defaultSessionBuilder();
          case DIRECT_PLAY ->
              directPlaySessionBuilder()
                  .sourcePath(Files.write(directory.resolve("movie.mp4"), new byte[100]));
        };
    streamSession = session.authority(authenticatedIdentity.playbackAuthority()).build();
    runtimeRegistry.save(streamSession);
    return playbackTokenIssuer
        .issue(authenticatedIdentity, streamSession, Duration.ofHours(1))
        .value();
  }

  private AuthenticatedIdentity useHousehold(UUID contextHouseholdId, UUID profileId) {
    var base = authenticatedIdentity();
    var session = authSessionRepository.findById(base.authSessionId()).orElseThrow();
    session.setContextHouseholdId(contextHouseholdId);
    session.setSelectedProfileId(profileId);
    authSessionRepository.saveAndFlush(session);
    return AuthenticatedIdentity.builder()
        .accountId(base.accountId())
        .authSessionId(base.authSessionId())
        .scope(TokenScope.PROFILE)
        .householdId(base.householdId())
        .householdRole(base.householdRole())
        .contextHouseholdId(contextHouseholdId)
        .profileId(profileId)
        .build();
  }

  private ProfileHouseholdShare createManagedProfileShare() {
    return transactionTemplate.execute(
        _ -> {
          var managed =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(host.household().getId())
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(host.account().getId())
                  .profileId(managed.getId())
                  .build());
          return share(managed.getId(), host.household().getId());
        });
  }

  private ProfileHouseholdShare share(UUID profileId, UUID householdId) {
    return shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
  }

  private void end(ProfileHouseholdShare share) {
    var ending = shareRepository.findById(share.getId()).orElseThrow();
    ending.setStatus(ProfileShareStatus.ENDED);
    ending.setEndedAt(Instant.now());
    shareRepository.saveAndFlush(ending);
  }

  private void assertMediaAvailable(String token) throws Exception {
    var expected =
        switch (streamSession.getDelivery()) {
          case HLS -> status().isOk();
          case DIRECT_PLAY -> status().isPartialContent();
        };
    mockMvc.perform(mediaRequest(token)).andExpect(expected);
  }

  private void assertMediaDenied(String token) throws Exception {
    mockMvc.perform(mediaRequest(token)).andExpect(status().isNotFound());
  }

  private MockHttpServletRequestBuilder mediaRequest(String token) {
    var sessionId = streamSession.getSessionId();
    return switch (streamSession.getDelivery()) {
      case HLS -> get("/api/stream/{id}/multivariant.m3u8", sessionId).param("t", token);
      case DIRECT_PLAY ->
          get("/api/stream/{id}/source.mp4", sessionId)
              .param("t", token)
              .header("Range", "bytes=0-9");
    };
  }
}
