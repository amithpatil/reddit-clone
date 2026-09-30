package com.redditclone.chat;

import com.redditclone.chat.dto.CreateRoomRequest;
import com.redditclone.common.paging.Cursor;
import com.redditclone.common.paging.CursorCodec;
import com.redditclone.common.paging.Listing;
import com.redditclone.common.paging.Thing;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final String MESSAGE_KIND = "chat_message";
    private static final int PAGE_SIZE = 50;

    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    @PostMapping("/rooms")
    public ChatRoom createRoom(@AuthenticationPrincipal UUID userId, @Valid @RequestBody CreateRoomRequest req) {
        return chat.findOrCreateRoom(userId, req.participantUsernames());
    }

    @GetMapping("/rooms")
    public List<ChatRoomSummary> listRooms(@AuthenticationPrincipal UUID userId) {
        return chat.listRooms(userId);
    }

    @GetMapping("/rooms/{roomId}/messages")
    public Listing<ChatMessage> history(@AuthenticationPrincipal UUID userId, @PathVariable UUID roomId,
                                         @RequestParam(required = false) String after) {
        Cursor cursor = CursorCodec.decode(after);
        List<ChatMessage> page = chat.history(userId, roomId, cursor.createdAt(), cursor.id(), PAGE_SIZE);
        List<Thing<ChatMessage>> children = page.stream().map(m -> new Thing<>(MESSAGE_KIND, m)).toList();
        String next = page.isEmpty() ? null
                : CursorCodec.encode(page.getLast().getCreatedAt(), page.getLast().getId());
        return Listing.of(children, next);
    }

    @PostMapping("/rooms/{roomId}/read")
    public void markRead(@AuthenticationPrincipal UUID userId, @PathVariable UUID roomId) {
        chat.markRead(userId, roomId);
    }
}
