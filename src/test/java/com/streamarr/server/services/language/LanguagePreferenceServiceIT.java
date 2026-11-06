package com.streamarr.server.services.language;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import com.streamarr.server.domain.language.SubtitlePreferenceMode;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.support.AuthTestSupport;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Tag("IntegrationTest")
@DisplayName("Language Preference Service Integration Tests")
class LanguagePreferenceServiceIT extends AbstractIntegrationTest {

  @Autowired private LanguagePreferenceService service;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private JwtDecoder jwtDecoder;

  private AuthTestSupport.TestIdentity viewer;

  @BeforeEach
  void createViewer() {
    viewer = authTestSupport.createIdentity();
  }

  @AfterEach
  void deleteViewer() {
    authTestSupport.deleteIdentity(viewer);
  }

  @Test
  @DisplayName("Should return the stored preferences when the selected Profile reads its own")
  void shouldReturnTheStoredPreferencesWhenTheSelectedProfileReadsItsOwn() {
    var watching = watchingAs(viewer);
    var stored =
        ProfileLanguagePreferences.builder()
            .audioLanguages(List.of(Language.JA, Language.EN))
            .subtitleLanguage(Optional.of(Language.EN))
            .subtitleMode(SubtitlePreferenceMode.ALWAYS)
            .build();
    service.setProfilePreferences(watching, stored);

    assertThat(service.profilePreferences(watching)).contains(stored);
  }

  private AuthenticatedIdentity watchingAs(AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.fromJwt(
        jwtDecoder.decode(authTestSupport.profileBearer(identity)));
  }
}
