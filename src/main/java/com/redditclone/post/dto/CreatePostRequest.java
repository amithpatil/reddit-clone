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
        UUID flairId,
        Boolean nsfw,
        Boolean spoiler
) {

    // Records deserialize through their canonical constructor, not individual setters — unlike a
    // JavaBean, where Jackson simply skips calling the setter for an absent field (leaving the field's
    // Java-assigned default), an absent record component is passed to the constructor as null. A
    // primitive boolean nsfw/spoiler would crash unboxing null the moment any caller omits the field
    // (every script/client written before these fields existed). Boolean wrapper + this compact
    // constructor normalizes that null to false right here, once, so every other accessor/caller still
    // sees a plain non-null boolean.
    public CreatePostRequest {
        if (nsfw == null) {
            nsfw = false;
        }
        if (spoiler == null) {
            spoiler = false;
        }
    }

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
