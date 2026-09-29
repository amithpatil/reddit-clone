package com.redditclone.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface CommunityRepository extends JpaRepository<Community, UUID> {

    // No IgnoreCase: name is citext, already case-insensitive at the DB level — see UserRepository for why
    // IgnoreCase itself (Hibernate's upper() HQL function) is incompatible with a citext-mapped column.
    Optional<Community> findByName(String name);

    @Modifying
    @Query("UPDATE Community c SET c.subscriberCount = c.subscriberCount + 1 WHERE c.id = :id")
    void incrementSubscriberCount(UUID id);

    @Modifying
    @Query("UPDATE Community c SET c.subscriberCount = c.subscriberCount - 1 WHERE c.id = :id")
    void decrementSubscriberCount(UUID id);
}
