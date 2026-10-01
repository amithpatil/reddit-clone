export interface ChatRoomSummary {
  roomId: string;
  otherParticipants: string[];
  lastMessageBody: string | null;
  lastMessageSenderId: string | null;
  lastMessageAt: string | null;
  unreadCount: number;
}

export interface ChatMessageItem {
  id: string;
  roomId: string;
  senderId: string;
  senderUsername: string | null;
  body: string;
  createdAt: string;
}
