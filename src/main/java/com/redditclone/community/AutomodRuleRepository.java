package com.redditclone.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AutomodRuleRepository extends JpaRepository<AutomodRule, UUID> {

    List<AutomodRule> findByCommunityId(UUID communityId);

    List<AutomodRule> findByCommunityIdAndEnabledTrue(UUID communityId);
}
