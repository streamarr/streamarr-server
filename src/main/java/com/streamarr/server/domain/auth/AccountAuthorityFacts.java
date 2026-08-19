package com.streamarr.server.domain.auth;

/**
 * The live authority facts of an Account as stored right now — read with a jOOQ scalar query so a
 * stale managed entity can never answer for them.
 */
public record AccountAuthorityFacts(boolean enabled, boolean serverAdmin) {}
