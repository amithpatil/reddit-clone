package com.redditclone.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    // No IgnoreCase here: username/email are citext columns, already case-insensitive at the DB level.
    // Spring Data's IgnoreCase wraps the comparison in Hibernate's upper() HQL function, which rejects a
    // citext-mapped (JdbcTypeCode SqlTypes.OTHER) argument — plain equality is both correct and simpler.
    boolean existsByEmail(String email);

    Optional<User> findByUsername(String username);
}
