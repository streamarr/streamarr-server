package com.streamarr.server.services.streaming;

/**
 * Why a playback session did not start: the request's capability declaration is invalid, or the
 * first stream session was refused.
 */
public sealed interface StartPlaybackSessionRejection
    permits CapabilityDeclarationRejection, CreateStreamSessionRejection {}
