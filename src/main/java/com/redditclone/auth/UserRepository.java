package com.redditclone.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    // No IgnoreCase here: username/email are citext columns, already case-insensitive at the DB level.
    // Spring Data's IgnoreCase wraps the comparison in Hibernate's upper() HQL function, which rejects a
    // citext-mapped (JdbcTypeCode SqlTypes.OTHER) argument — plain equality is both correct and simpler.
    // (Case-insensitivity of plain equality itself depends on stringtype=unspecified on the JDBC URL —
    // see application.yml.)
    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    Optional<User> findByUsername(String username);

    // Batched counterpart to findByUsername, for resolving several u/{username} mentions in one query.
    List<User> findByUsernameIn(Set<String> usernames);

    // Native query: the GIN trigram index (users_username_trgm_idx) accelerates ILIKE's substring match;
    // similarity() orders best-match-first, karma_post breaks ties — same shape as
    // CommunityRepository.searchByName. status = 'active' excludes deleted/banned accounts from search
    // results, matching the "active".equals(status) check used throughout this class. No pagination, same
    // "a relevance ranking isn't a stable keyset sort key" reasoning as the post/community search queries.
    @Query(value = """
            SELECT * FROM users
            WHERE status = 'active' AND username ILIKE '%' || :query || '%'
            ORDER BY similarity(username, :query) DESC, karma_post DESC
            LIMIT 25
            """, nativeQuery = true)
    List<User> searchByUsername(@Param("query") String query);

    // clearAutomatically: same reasoning as CommentRepository.incrementChildCount — without it, a User
    // entity already loaded in this transaction would keep its stale pre-update karma in the persistence
    // context, and a later save of that entity would silently clobber this bulk update.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.karmaPost = u.karmaPost + :delta WHERE u.id = :id")
    void adjustKarmaPost(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.karmaComment = u.karmaComment + :delta WHERE u.id = :id")
    void adjustKarmaComment(@Param("id") UUID id, @Param("delta") int delta);
}
