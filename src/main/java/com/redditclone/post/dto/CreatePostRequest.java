package com.redditclone.post.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreatePostRequest(
        @NotBlank @Pattern(regexp = "text|link|image|video") String kind,
        @NotBlank @Size(max = 300) String title,
        @Size(max = 40000) String body,
        String url,
        UUID mediaId,
        UUID flairId
) {

    // Jakarta Bean Validation discovers any getter-shaped method (isXxx()/getXxx()) via reflection
    // regardless of whether the enclosing class is a record — these three close a real, previously
    // unenforced gap where any kind accepted any combination of empty fields.
    @AssertTrue(message = "url is required for kind=link")
    @JsonIgnore
    public boolean isUrlValidForKind() {
        return !"link".equals(kind) || (url != null && !url.isBlank());
    }

    @AssertTrue(message = "mediaId is required for kind=image or kind=video")
    @JsonIgnore
    public boolean isMediaValidForKind() {
        return !("image".equals(kind) || "video".equals(kind)) || mediaId != null;
    }

    @AssertTrue(message = "body is required for kind=text")
    @JsonIgnore
    public boolean isBodyValidForKind() {
        return !"text".equals(kind) || (body != null && !body.isBlank());
    }
}
