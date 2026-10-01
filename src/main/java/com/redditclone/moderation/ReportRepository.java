package com.redditclone.moderation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, ReportId> {

    // Report's JPA id is the composite (id, created_at) the partitioned table actually requires (see
    // Report's class comment), but callers only ever have the report's own id (e.g. from a URL path) —
    // this looks it up by that alone, the same as the plain findById(UUID) this replaced.
    @Query("SELECT r FROM Report r WHERE r.id = :id")
    Optional<Report> findByReportId(@Param("id") UUID id);

    // The individual reports behind one mod-queue entry (F8) — every status, not just "open", so a
    // moderator can see the full history, not only what's still actionable.
    List<Report> findByCommunityIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
            UUID communityId, String targetType, UUID targetId);
}
