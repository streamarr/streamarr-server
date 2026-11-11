package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Share Codes Tests")
class ShareCodesTest {

  private static final String OTHER_KEY = "b3RoZXItc2hhcmUtY29kZS10ZXN0LWtleS0zMi1ieXRlcyE=";

  private final ShareCodes shareCodes = ShareCodesFixture.shareCodes();

  @Test
  @DisplayName("Should issue the letters and groups of a Device linking code when a code is issued")
  void shouldIssueLettersAndGroupsOfDeviceLinkingCodeWhenCodeIsIssued() {
    var issued = shareCodes.issue();

    assertThat(issued.code()).matches("[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}");
  }

  @Test
  @DisplayName("Should store no plain digest of the code when a code is issued")
  void shouldStoreNoPlainDigestOfCodeWhenCodeIsIssued() {
    var issued = shareCodes.issue();

    assertThat(issued.digest())
        .hasSize(32)
        .isNotEqualTo(CredentialDigest.of(UserCode.normalize(issued.code())));
  }

  @Test
  @DisplayName("Should recognise a code under the same key when another server reads it")
  void shouldRecogniseCodeUnderSameKeyWhenAnotherServerReadsIt() {
    var issued = shareCodes.issue();

    assertThat(ShareCodesFixture.keyedBy(ShareCodesFixture.KEY).digestOf(issued.code()))
        .contains(issued.digest());
  }

  @Test
  @DisplayName("Should recognise no code issued under another key when the key changes")
  void shouldRecogniseNoCodeIssuedUnderAnotherKeyWhenKeyChanges() {
    var issued = shareCodes.issue();

    assertThat(ShareCodesFixture.keyedBy(OTHER_KEY).digestOf(issued.code()))
        .get()
        .isNotEqualTo(issued.digest());
  }

  @ParameterizedTest(name = "\"{0}\"")
  @NullSource
  @ValueSource(strings = {"", "   "})
  @DisplayName("Should make a key of its own when no key is configured")
  void shouldMakeKeyOfItsOwnWhenNoKeyIsConfigured(String configured) {
    var first = ShareCodesFixture.keyedBy(configured);
    var second = ShareCodesFixture.keyedBy(configured);
    var issued = first.issue();

    assertThat(first.digestOf(issued.code())).contains(issued.digest());
    assertThat(second.digestOf(issued.code())).get().isNotEqualTo(issued.digest());
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"not base64!", "c2hvcnQta2V5"})
  @DisplayName("Should refuse to start without naming the key when the configured key is unusable")
  void shouldRefuseToStartWithoutNamingKeyWhenConfiguredKeyIsUnusable(String configured) {
    assertThatThrownBy(() -> ShareCodesFixture.keyedBy(configured))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_SHARE_CODE_KEY")
        .hasMessageNotContaining(configured);
  }

  @Test
  @DisplayName("Should recognise the issued code when a person types it in another accepted form")
  void shouldRecogniseIssuedCodeWhenPersonTypesItInAnotherAcceptedForm() {
    var issued = shareCodes.issue();
    var lowerCaseWithSpaces = " " + issued.code().toLowerCase(Locale.ROOT).replace('-', ' ') + " ";
    var ungrouped = issued.code().replace("-", "");

    assertThat(shareCodes.digestOf(lowerCaseWithSpaces)).contains(issued.digest());
    assertThat(shareCodes.digestOf(ungrouped)).contains(issued.digest());
    assertThat(shareCodes.digestOf(issued.code())).contains(issued.digest());
  }

  @Test
  @DisplayName("Should recognise a different code when another code is issued")
  void shouldRecogniseDifferentCodeWhenAnotherCodeIsIssued() {
    var first = shareCodes.issue();
    var second = shareCodes.issue();

    assertThat(shareCodes.digestOf(second.code()).orElseThrow()).isNotEqualTo(first.digest());
  }

  @ParameterizedTest(name = "\"{0}\"")
  @NullSource
  @ValueSource(
      strings = {"", "   ", "BCDF-GHJ", "BCDF-GHJKL", "AEIO-UBCD", "1234-5678", "BCDF_GHJK"})
  @DisplayName("Should recognise no code when the typed text is not in the form of a share code")
  void shouldRecogniseNoCodeWhenTypedTextIsNotInFormOfShareCode(String typed) {
    assertThat(shareCodes.digestOf(typed)).isEmpty();
  }

  @Test
  @DisplayName("Should keep the issued digest intact when a caller changes the array it received")
  void shouldKeepIssuedDigestIntactWhenCallerChangesArrayItReceived() {
    var issued = shareCodes.issue();
    var received = issued.digest();
    received[0] = (byte) (received[0] + 1);

    assertThat(issued.digest()).isNotEqualTo(received);
    assertThat(shareCodes.digestOf(issued.code())).contains(issued.digest());
  }

  @Test
  @DisplayName("Should leave the code and its digest out when an issued code is rendered as text")
  void shouldLeaveCodeAndDigestOutWhenIssuedCodeIsRenderedAsText() {
    var issued = shareCodes.issue();

    assertThat(issued.toString())
        .doesNotContain(issued.code())
        .doesNotContain(issued.code().replace("-", ""))
        .contains("REDACTED");
  }
}
