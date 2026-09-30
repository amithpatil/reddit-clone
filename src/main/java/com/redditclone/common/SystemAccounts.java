package com.redditclone.common;

import java.util.UUID;

/**
 * Well-known system-account ids shared across modules — e.g. the AutoModerator bot account that
 * automod-triggered moderation_actions rows attribute to (real Reddit's AutoModerator genuinely is just
 * a bot account, not a special-cased actor type; this project does the same). Lives in `common` rather
 * than `auth` or `community` because both the module that creates the account (auth, via
 * SystemAccountBootstrap) and the module that references it when writing an audit row (community, via
 * CommunityService.evaluateAutomod) need it, and neither should depend on the other for this.
 */
public final class SystemAccounts {

    public static final UUID AUTOMOD_USER_ID = UUID.fromString("00000000-0000-7000-0000-000000000001");

    private SystemAccounts() {
    }
}
