package com.streamarr.server.services.streaming;

import java.util.UUID;

public record RuntimeSessionReservation(UUID sessionId, UUID generation) {}
