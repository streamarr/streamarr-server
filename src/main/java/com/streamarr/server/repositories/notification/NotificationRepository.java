package com.streamarr.server.repositories.notification;

import com.streamarr.server.domain.notification.Notification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository
    extends JpaRepository<Notification, UUID>, NotificationRepositoryCustom {}
