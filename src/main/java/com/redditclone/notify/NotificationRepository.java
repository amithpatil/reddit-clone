package com.redditclone.notify;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, NotificationId> {

    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    // Callers (the mark-read endpoint) only ever have the bare id, not its createdAt — same reasoning and
    // shape as ReportRepository.findByReportId.
    @Query("SELECT n FROM Notification n WHERE n.id = :id")
    Optional<Notification> findByNotificationId(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :readAt WHERE n.id = :id AND n.createdAt = :createdAt")
    void markRead(@Param("id") UUID id, @Param("createdAt") Instant createdAt, @Param("readAt") Instant readAt);
}
