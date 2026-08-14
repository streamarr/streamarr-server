package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Portable Identity Documentation Tests")
class PortableIdentityDocumentationTest {

  @Test
  @DisplayName("Should not document deleted household token scope in security configuration")
  void shouldNotDocumentDeletedHouseholdTokenScopeInSecurityConfiguration() throws IOException {
    assertThat(source("src/main/java/com/streamarr/server/config/security/SecurityConfig.java"))
        .doesNotContain("household and profile tokens");
  }

  @Test
  @DisplayName("Should not document deleted household selection in session repository")
  void shouldNotDocumentDeletedHouseholdSelectionInSessionRepository() throws IOException {
    assertThat(
            source(
                "src/main/java/com/streamarr/server/repositories/auth/AuthSessionRepositoryCustom.java"))
        .doesNotContain("household/profile selection");
  }

  @Test
  @DisplayName("Should not document obsolete bounded authorization staleness")
  void shouldNotDocumentObsoleteBoundedAuthorizationStaleness() throws IOException {
    assertThat(
            source("src/main/java/com/streamarr/server/config/security/AuthTokenProperties.java"))
        .doesNotContain("Bounded API staleness");
  }

  @Test
  @DisplayName("Should not document deleted setup link statement")
  void shouldNotDocumentDeletedSetupLinkStatement() throws IOException {
    assertThat(source("src/main/java/com/streamarr/server/services/auth/SetupService.java"))
        .doesNotContain("claim and link run as direct SQL");
  }

  @Test
  @DisplayName("Should not advertise deleted household scope in device pairing contract")
  void shouldNotAdvertiseDeletedHouseholdScopeInDevicePairingContract() throws IOException {
    assertThat(source("docs/device-pairing-contract.adoc"))
        .doesNotContain("<account | household | profile>");
  }

  @Test
  @DisplayName("Should document global portable identity guard order beside database guard")
  void shouldDocumentGlobalPortableIdentityGuardOrderBesideDatabaseGuard() throws IOException {
    assertThat(
            source("src/main/resources/db/migration/V054__Create_Portable_Profile_Foundation.sql"))
        .contains("profile guards before household guards");
  }

  @Test
  @DisplayName("Should link household safety preflight to authoritative database assertion")
  void shouldLinkHouseholdSafetyPreflightToAuthoritativeDatabaseAssertion() throws IOException {
    assertThat(
            source(
                "src/main/java/com/streamarr/server/services/auth/HouseholdProfileSafetyService.java"))
        .contains("assert_household_profile_safety");
  }

  @Test
  @DisplayName("Should link kid manager preflight to authoritative database assertion")
  void shouldLinkKidManagerPreflightToAuthoritativeDatabaseAssertion() throws IOException {
    assertThat(
            source("src/main/java/com/streamarr/server/services/auth/KidProfileManagerPolicy.java"))
        .contains("assert_local_kid_manager");
  }

  @Test
  @DisplayName("Should document profile deletion authorization transaction lifetime")
  void shouldDocumentProfileDeletionAuthorizationTransactionLifetime() throws IOException {
    assertThat(
            source(
                "src/main/java/com/streamarr/server/domain/auth/ProfileDeletionAuthorization.java"))
        .containsIgnoringCase("same transaction");
  }

  @Test
  @DisplayName("Should document identity data erased by portable foundation migration")
  void shouldDocumentIdentityDataErasedByPortableFoundationMigration() throws IOException {
    var migration =
        source("src/main/resources/db/migration/V054__Create_Portable_Profile_Foundation.sql");
    var resetDocumentation = migration.substring(0, migration.indexOf("ALTER TABLE"));

    assertThat(resetDocumentation)
        .contains("auth_session")
        .contains("refresh_token")
        .contains("session_progress")
        .contains("watch_history");
  }

  private String source(String path) throws IOException {
    return Files.readString(Path.of(path));
  }
}
