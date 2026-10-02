package com.redditclone.post;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PostMediaRepository extends JpaRepository<PostMedia, PostMediaId> {

    // Ordered by postId first (so PostService.attachGalleryMedia can group by it in one pass) then by
    // position (the user's chosen image order) — same batched-IN-query shape as every other attachX query
    // in this module, just over a join table instead of a single FK column.
    List<PostMedia> findByPostIdInOrderByPostIdAscPositionAsc(Collection<UUID> postIds);
}
