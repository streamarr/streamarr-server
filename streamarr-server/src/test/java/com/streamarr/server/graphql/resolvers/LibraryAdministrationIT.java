package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.AuthTestSupport.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Library administration requires the server ADMIN role (ADR 0015: account-scoped surfaces never
 * reach administration). Ordinary accounts are denied with the FORBIDDEN machine code.
 */
@Tag("IntegrationTest")
@DisplayName("Library Administration Integration Tests")
class LibraryAdministrationIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private LibraryRepository libraryRepository;

  private AuthTestSupport.TestIdentity identity;

  @AfterEach
  void deleteIdentity() {
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "mutation { removeLibrary(id: \\\"58edcb42-4c93-4a05-876f-c48e0c48ff30\\\") }",
        "mutation { scanLibrary(id: \\\"58edcb42-4c93-4a05-876f-c48e0c48ff30\\\") }",
        "mutation { refreshLibrary(id: \\\"58edcb42-4c93-4a05-876f-c48e0c48ff30\\\") }",
        "mutation { addLibrary(input: {name: \\\"Denied\\\", filepath: \\\"file:///denied\\\","
            + " type: MOVIE, backend: LOCAL}) { id } }"
      })
  @DisplayName("Should deny library administration when account role is user")
  void shouldDenyLibraryAdministrationWhenAccountRoleIsUser(String mutation) throws Exception {
    identity = authTestSupport.createIdentity();

    postGraphQl(mutation, authTestSupport.profileBearer(identity))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("Should preserve library when remove denied for user role")
  void shouldPreserveLibraryWhenRemoveDeniedForUserRole() throws Exception {
    identity = authTestSupport.createIdentity();
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    try {
      postGraphQl(removeLibraryMutation(library.getId()), authTestSupport.profileBearer(identity))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

      assertThat(libraryRepository.existsById(library.getId())).isTrue();
    } finally {
      libraryRepository.deleteById(library.getId());
    }
  }

  @Test
  @DisplayName("Should remove library when account role is admin")
  void shouldRemoveLibraryWhenAccountRoleIsAdmin() throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    postGraphQl(removeLibraryMutation(library.getId()), authTestSupport.profileBearer(identity))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.removeLibrary").value(true));

    assertThat(libraryRepository.existsById(library.getId())).isFalse();
  }

  private String removeLibraryMutation(UUID libraryId) {
    return "mutation { removeLibrary(id: \\\"%s\\\") }".formatted(libraryId);
  }

  private ResultActions postGraphQl(String query, String token) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"query\": \"%s\"}".formatted(query))
            .with(bearer(token)));
  }
}
