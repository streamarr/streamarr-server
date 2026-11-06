package com.streamarr.server.domain.streaming;

/**
 * How the client says it reached the server when it starts playback. The server never classifies a
 * viewer by the viewer's IP address (ADR 0050); it takes the client's word. A false claim only
 * keeps a stream session out of the upload budget, and grants no access.
 */
public enum NetworkRoute {
  /**
   * A private address (RFC 1918, IPv4 or IPv6 link-local, or IPv6 unique local fd00::/8 outside
   * Tailscale's fd7a:115c:a1e0::/48), a .local or .home.arpa name (RFC 8375), or local discovery.
   * The stream session never counts against the upload budget.
   */
  LOCAL_NETWORK,
  /**
   * Every other route: loopback (a tunnel can forward a loopback port), a public hostname, a
   * tunnel, carrier-grade NAT (100.64.0.0/10, which includes Tailscale), Tailscale's IPv6 prefix
   * fd7a:115c:a1e0::/48, or hairpin NAT through a public name.
   */
  INTERNET
}
