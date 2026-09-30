CREATE TABLE chat_rooms (
  id          UUID PRIMARY KEY,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chat_room_participants (
  room_id       UUID NOT NULL REFERENCES chat_rooms(id),
  user_id       UUID NOT NULL REFERENCES users(id),
  joined_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_read_at  TIMESTAMPTZ,
  PRIMARY KEY (room_id, user_id)
);
CREATE INDEX chat_room_participants_user_idx ON chat_room_participants (user_id);

CREATE TABLE chat_messages (
  id          UUID PRIMARY KEY,
  room_id     UUID NOT NULL REFERENCES chat_rooms(id),
  sender_id   UUID NOT NULL REFERENCES users(id),
  body        TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX chat_messages_room_idx ON chat_messages (room_id, created_at DESC, id DESC);
