package com.redditclone.moderation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ModMailMuteRepository extends JpaRepository<ModMailMute, ModMailMuteId> {
}
