package com.streamarr.server.services.auth.invalidation;

interface CounterNotificationConnectionSource {

  CounterNotificationConnection open();
}
