package com.streamarr.server.services.events.notification;

/**
 * What a recipient was told, as its kind's record type holds it (ADR 0063 §What a Notification
 * stores). It is written when the event happens, and nothing reads the subject to change it.
 */
public interface NotificationSnapshot {}
