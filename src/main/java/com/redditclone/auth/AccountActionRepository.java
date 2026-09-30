package com.redditclone.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccountActionRepository extends JpaRepository<AccountAction, UUID> {
}
