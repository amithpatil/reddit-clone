package com.redditclone.community;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommunityRepository extends JpaRepository<Community, UUID> {

    // No IgnoreCase: name is citext, already case-insensitive at the DB level — see UserRepository for why
    // IgnoreCase itself (Hibernate's upper() HQL function) is incompatible with a citext-mapped column.
    Optional<Community> findByName(String name);

    boolean existsByName(String name);

    @Modifying
    @Query("UPDATE Community c SET c.subscriberCount = c.subscriberCount + 1 WHERE c.id = :id")
    void incrementSubscriberCount(UUID id);

    @Modifying
    @Query("UPDATE Community c SET c.subscriberCount = c.subscriberCount - 1 WHERE c.id = :id")
    void decrementSubscriberCount(UUID id);

    // Browse/discovery — same keyset-pagination shape as every post-feed listing in this codebase. Every
    // community is included regardless of type: existence/description/subscriber count are metadata, the
    // same category GET /flairs, GET /rules and GET /pinned already treat as public even for private
    // communities.
    @Query("""
            SELECT c FROM Community c
            WHERE c.createdAt < :cursorCreatedAt OR (c.createdAt = :cursorCreatedAt AND c.id < :cursorId)
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    List<Community> findNewPage(@Param("cursorCreatedAt") Instant cursorCreatedAt,
                                 @Param("cursorId") UUID cursorId, Pageable limit);

    @Query("""
            SELECT c FROM Community c
            WHERE c.subscriberCount < :cursorSubscriberCount
               OR (c.subscriberCount = :cursorSubscriberCount AND c.id < :cursorId)
            ORDER BY c.subscriberCount DESC, c.id DESC
            """)
    List<Community> findPopularPage(@Param("cursorSubscriberCount") int cursorSubscriberCount,
                                     @Param("cursorId") UUID cursorId, Pageable limit);

    // Native query: the GIN trigram index (communities_name_trgm_idx) accelerates ILIKE's substring match;
    // similarity() orders best-match-first, subscriber_count breaks ties. No pagination — same "a
    // relevance ranking isn't a stable keyset sort key" reasoning PostService.search already documents.
    @Query(value = """
            SELECT * FROM communities
            WHERE name ILIKE '%' || :query || '%'
            ORDER BY similarity(name, :query) DESC, subscriber_count DESC
            LIMIT 25
            """, nativeQuery = true)
    List<Community> searchByName(@Param("query") String query);
}
