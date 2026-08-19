package com.streamarr.server.services.authorization.cedar;

/**
 * Image-only entry point: {@code java -Dloader.main=…CedarEngineSelfCheckLauncher
 * PropertiesLauncher} inside the packaged container prints one line and exits non-zero when the
 * native engine cannot run on that architecture. Excluded from coverage; {@link
 * CedarEngineSelfCheck} itself is unit-tested.
 */
public final class CedarEngineSelfCheckLauncher {

  private CedarEngineSelfCheckLauncher() {}

  // stdout is the contract: verify-cedar-image.sh greps the line, and the launcher runs without
  // the application's logging configuration.
  @SuppressWarnings({"java:S106", "java:S1172"})
  public static void main(String[] args) {
    var result = new CedarEngineSelfCheck().run();
    System.out.println(
        "Cedar self-check "
            + (result.passed() ? "passed" : "FAILED")
            + ": permittedAllowed="
            + result.permittedAccountAllowed()
            + " strangerDenied="
            + result.strangerAccountDenied());
    if (!result.passed()) {
      System.exit(1);
    }
  }
}
