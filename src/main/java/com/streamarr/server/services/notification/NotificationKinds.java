package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.NotificationForm;

/**
 * What a family of kinds brings to the writer (ADR 0063 §The kinds): each kind's form, and how a
 * later event of the kind combines its facts with the stored ones of the unread news it updates.
 *
 * @param <K> the kinds
 * @param <F> what the kinds carry beyond their subject
 */
public interface NotificationKinds<K, F> {

  NotificationForm formOf(K kind);

  F combine(K kind, F stored, F later);
}
