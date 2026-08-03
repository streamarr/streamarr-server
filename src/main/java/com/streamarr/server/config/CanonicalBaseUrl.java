package com.streamarr.server.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The validated canonical base URL, or the explicit absence of one. Absence is a supported state:
 * the server runs, reports {@code devicePairingEnabled: false}, and refuses to invent an address it
 * cannot know.
 *
 * <p>Validation here is deliberately light. Clients re-normalize every URL they receive against the
 * shared endpoint table in {@code docs/contracts/server-endpoint/v1}, so implementing that table
 * again on the server would be a second implementation with no consumer. What this check must catch
 * is operator error in the one value every client contract hangs off.
 */
public final class CanonicalBaseUrl {

  private static final String HTTPS = "https";
  private static final String HTTP = "http";

  private final URI baseUri;

  private CanonicalBaseUrl(URI baseUri) {
    this.baseUri = baseUri;
  }

  public static CanonicalBaseUrl absent() {
    return new CanonicalBaseUrl(null);
  }

  /**
   * @param insecureTransportAllowed both the insecure-http flag and a development or test profile;
   *     either alone must not unlock cleartext.
   */
  public static CanonicalBaseUrl of(String rawBaseUrl, boolean insecureTransportAllowed) {
    if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
      return absent();
    }

    var uri = parse(rawBaseUrl.trim());
    requireSupportedScheme(uri, insecureTransportAllowed);
    requireEndpointOnly(uri);

    return new CanonicalBaseUrl(withoutTrailingSlash(uri));
  }

  public boolean isConfigured() {
    return baseUri != null;
  }

  public String value() {
    requireConfigured();
    return baseUri.toString();
  }

  /** Joins an absolute path beneath the configured base, preserving any base path. */
  public String resolve(String absolutePath) {
    requireConfigured();
    return baseUri + absolutePath;
  }

  private void requireConfigured() {
    if (!isConfigured()) {
      throw new IllegalStateException("No canonical base URL is configured.");
    }
  }

  private static URI parse(String rawBaseUrl) {
    // Percent-encoding is rejected outright rather than decoded: nobody types an encoded LAN URL,
    // and relaxing this later is additive where guessing an encoding is not.
    if (rawBaseUrl.indexOf('%') >= 0) {
      throw invalid(rawBaseUrl, "must not contain percent-encoding");
    }

    try {
      return new URI(rawBaseUrl);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(reason(rawBaseUrl, "is not a valid URL"), e);
    }
  }

  private static void requireSupportedScheme(URI uri, boolean insecureTransportAllowed) {
    var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);

    // No scheme completion, ever. Silently assuming https would mask an operator typo in the one
    // value every client contract hangs off.
    if (!HTTPS.equals(scheme) && !HTTP.equals(scheme)) {
      throw invalid(uri, "must be an absolute http or https URL");
    }
    if (HTTP.equals(scheme) && !insecureTransportAllowed) {
      throw invalid(
          uri,
          "must use https; cleartext requires STREAMARR_ALLOW_INSECURE_HTTP together with a"
              + " development or test profile");
    }
  }

  private static void requireEndpointOnly(URI uri) {
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw invalid(uri, "must name a host");
    }
    if (uri.getUserInfo() != null) {
      throw invalid(uri, "must not carry userinfo");
    }
    if (uri.getQuery() != null) {
      throw invalid(uri, "must not carry a query string");
    }
    if (uri.getFragment() != null) {
      throw invalid(uri, "must not carry a fragment");
    }
  }

  private static URI withoutTrailingSlash(URI uri) {
    var text = uri.toString();
    return text.endsWith("/") ? URI.create(text.substring(0, text.length() - 1)) : uri;
  }

  private static IllegalStateException invalid(Object rawBaseUrl, String requirement) {
    return new IllegalStateException(reason(rawBaseUrl, requirement));
  }

  private static String reason(Object rawBaseUrl, String requirement) {
    return "STREAMARR_BASE_URL (%s) %s.".formatted(rawBaseUrl, requirement);
  }

  @Override
  public String toString() {
    return isConfigured() ? "CanonicalBaseUrl[%s]".formatted(baseUri) : "CanonicalBaseUrl[absent]";
  }
}
