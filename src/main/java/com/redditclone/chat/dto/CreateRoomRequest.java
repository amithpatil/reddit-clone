package com.redditclone.chat.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateRoomRequest(@NotEmpty List<String> participantUsernames) {
}
