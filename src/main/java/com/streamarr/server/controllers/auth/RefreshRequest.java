package com.streamarr.server.controllers.auth;

public record RefreshRequest(String refreshToken, boolean cookieMode) {}
