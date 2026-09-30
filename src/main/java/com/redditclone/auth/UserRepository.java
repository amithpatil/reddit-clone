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
