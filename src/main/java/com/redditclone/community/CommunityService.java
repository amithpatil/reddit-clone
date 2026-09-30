package com.redditclone.community;

import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.ConflictException;
import com.redditclone.common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class CommunityService {

    private final CommunityRepository communities;
    private final MembershipRepository memberships;
    private final CommunityModeratorRepository moderators;
    private final UuidV7Generator ids;

    public CommunityService(CommunityRepository communities, MembershipRepository memberships,
                             CommunityModeratorRepository moderators, UuidV7Generator ids) {
        this.communities = communities;
        this.memberships = memberships;
        this.moderators = moderators;
        this.ids = ids;
    }

    @Transactional
    public Community create(UUID creatorId, String name, String description) {
        if (communities.existsByName(name)) {
            throw new ConflictException("community name in use");
        }
        Community c = new Community();
        c.setId(ids.nextId());
        c.setName(name);
        c.setDescription(description);
        c.setCreatorId(creatorId);
        c.setSubscriberCount(1); // creator auto-joins as the first member
        communities.save(c);
        // Insert the creator's membership directly rather than calling join(): join()'s existsBy check
        // can only ever be false here (c.getId() was just generated), and calling it would also be a
        // Spring self-invocation that silently bypasses join()'s own @Transactional proxy.
        memberships.save(new Membership(creatorId, c.getId(), Instant.now()));
        moderators.save(new CommunityModerator(c.getId(), creatorId, CommunityModerator.OWNER_PERMISSIONS, creatorId));
        return c;
    }

    public Community findByName(String name) {
        return communities.findByName(name).orElseThrow(() -> new NotFoundException("no such community"));
    }

    @Transactional
    public void join(UUID userId, UUID communityId) {
        if (memberships.existsByUserIdAndCommunityId(userId, communityId)) {
            return; // idempotent
        }
        memberships.save(new Membership(userId, communityId, Instant.now()));
        communities.incrementSubscriberCount(communityId);
    }

    @Transactional
    public void leave(UUID userId, UUID communityId) {
        if (memberships.deleteByUserIdAndCommunityId(userId, communityId) > 0) {
            communities.decrementSubscriberCount(communityId);
        }
    }
}
