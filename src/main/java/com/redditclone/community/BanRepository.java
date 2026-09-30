package com.redditclone.community;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BanRepository extends JpaRepository<Ban, BanId> {
}
