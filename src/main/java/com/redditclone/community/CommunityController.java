package com.redditclone.community;

import com.redditclone.community.dto.CommunityRule;
import com.redditclone.community.dto.CreateCommunityRequest;
import com.redditclone.community.dto.SetFlairRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/r")
public class CommunityController {

    private final CommunityService communities;

    public CommunityController(CommunityService communities) {
        this.communities = communities;
    }

    @PostMapping
    public Community create(@AuthenticationPrincipal UUID userId, @Valid @RequestBody CreateCommunityRequest req) {
        return communities.create(userId, req.name(), req.description());
    }

    @PostMapping("/{name}/subscribe")
    public void subscribe(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        communities.join(userId, communities.findByName(name).getId());
    }

    @DeleteMapping("/{name}/subscribe")
    public void unsubscribe(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        communities.leave(userId, communities.findByName(name).getId());
    }

    @PatchMapping("/{name}/me/flair")
    public void setOwnFlair(@AuthenticationPrincipal UUID userId, @PathVariable String name, @RequestBody SetFlairRequest req) {
        communities.setOwnFlair(userId, communities.findByName(name).getId(), req.flairId());
    }

    @GetMapping("/{name}/rules")
    public List<CommunityRule> rules(@PathVariable String name) {
        return communities.getRules(communities.findByName(name).getId());
    }
}
