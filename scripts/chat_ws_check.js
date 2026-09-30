#!/usr/bin/env node
// Minimal, dependency-free STOMP-over-WebSocket client for verifying chat's real-time delivery path from
// scripts/seed_and_verify_chat.sh — bash can't speak STOMP framing, and the wire format is simple enough
// (newline-delimited headers, null-byte-terminated frames) that a hand-rolled client is less overhead than
// adding a library dependency this project doesn't otherwise need. Uses Node's built-in global WebSocket
// (stable since Node 22) — no npm install, no package.json.
//
// Usage:
//   node chat_ws_check.js subscribe-wait <wsUrl> <token> <timeoutSeconds> [expectedBodySubstring]
//   node chat_ws_check.js send <wsUrl> <token> <roomId> <body>
//   node chat_ws_check.js send-many <wsUrl> <token> <roomId> <count> <bodyPrefix>
//   node chat_ws_check.js reject-check <wsUrl> [token]   # token omitted or garbage -> expects rejection
//
// Exit code 0 = the check the mode describes was observed; exit code 1 = it wasn't (timeout, unexpected
// success/failure). Prints one line of diagnostic JSON to stdout on success for the caller to inspect.

const [, , mode, wsUrl, ...rest] = process.argv;

function frame(command, headers, body) {
  let out = command + "\n";
  for (const [k, v] of Object.entries(headers)) {
    out += `${k}:${v}\n`;
  }
  out += "\n" + (body ?? "") + "\0";
  return out;
}

// Splits a growing text buffer on NUL frame terminators, returning complete frames and leaving any
// trailing partial frame in the buffer for the next chunk.
function extractFrames(buffer) {
  const frames = [];
  let idx;
  while ((idx = buffer.indexOf("\0")) !== -1) {
    const raw = buffer.slice(0, idx);
    buffer = buffer.slice(idx + 1);
    if (raw.length === 0 || raw === "\n") continue; // STOMP heart-beat newlines
    const lines = raw.split("\n");
    const command = lines[0];
    let i = 1;
    const headers = {};
    for (; i < lines.length && lines[i] !== ""; i++) {
      const sep = lines[i].indexOf(":");
      headers[lines[i].slice(0, sep)] = lines[i].slice(sep + 1);
    }
    const body = lines.slice(i + 1).join("\n");
    frames.push({ command, headers, body });
  }
  return { frames, buffer };
}

function connect(wsUrl, token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    let buf = "";
    let connected = false;
    const onFrame = { current: null };

    ws.addEventListener("open", () => {
      const headers = { "accept-version": "1.2", host: "localhost" };
      if (token !== undefined) {
        headers["Authorization"] = `Bearer ${token}`;
      }
      ws.send(frame("CONNECT", headers));
    });

    ws.addEventListener("message", (event) => {
      buf += event.data;
      const result = extractFrames(buf);
      buf = result.buffer;
      for (const f of result.frames) {
        if (f.command === "CONNECTED" && !connected) {
          connected = true;
          resolve({ ws, onFrame });
        } else if (onFrame.current) {
          onFrame.current(f);
        }
      }
    });

    ws.addEventListener("close", (event) => {
      if (!connected) {
        reject(new Error(`closed before CONNECTED (code=${event.code})`));
      }
    });

    ws.addEventListener("error", () => {
      if (!connected) {
        reject(new Error("connection error before CONNECTED"));
      }
    });

    setTimeout(() => {
      if (!connected) reject(new Error("timed out waiting for CONNECTED"));
    }, 5000);
  });
}

async function subscribeWait(token, timeoutSeconds, expectedSubstring) {
  const { ws, onFrame } = await connect(wsUrl, token);
  ws.send(frame("SUBSCRIBE", { id: "sub-0", destination: "/user/queue/chat" }));

  const result = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), timeoutSeconds * 1000);
    onFrame.current = (f) => {
      if (f.command !== "MESSAGE") return;
      if (expectedSubstring && !f.body.includes(expectedSubstring)) return;
      clearTimeout(timer);
      resolve(f.body);
    };
  });

  ws.close();
  if (result === null) {
    console.error("timed out waiting for a MESSAGE frame");
    process.exit(1);
  }
  console.log(result);
  process.exit(0);
}

async function send(token, roomId, body) {
  const { ws, onFrame } = await connect(wsUrl, token);
  // Also subscribe to our own error queue so a server-side rejection (bad room, not a participant, etc.)
  // is visible here instead of silently vanishing.
  ws.send(frame("SUBSCRIBE", { id: "sub-err", destination: "/user/queue/errors" }));
  let errorFrame = null;
  onFrame.current = (f) => {
    if (f.command === "MESSAGE" && f.headers.destination === "/user/queue/errors") {
      errorFrame = f.body;
    }
  };
  await new Promise((r) => setTimeout(r, 200)); // let the SUBSCRIBE register before SEND races it
  ws.send(frame("SEND", { destination: "/app/chat.send", "content-type": "application/json" },
      JSON.stringify({ roomId, body })));
  // Give the server a moment to fully process the SEND frame before the socket closes.
  await new Promise((r) => setTimeout(r, 800));
  ws.close();
  if (errorFrame) {
    console.error("server returned an error: " + errorFrame);
    process.exit(1);
  }
  console.log(JSON.stringify({ sent: true }));
  process.exit(0);
}

async function sendMany(token, roomId, count, bodyPrefix) {
  const { ws } = await connect(wsUrl, token);
  for (let i = 1; i <= count; i++) {
    ws.send(frame("SEND", { destination: "/app/chat.send", "content-type": "application/json" },
        JSON.stringify({ roomId, body: `${bodyPrefix}-${i}` })));
  }
  // One connection, many SENDs in quick succession — a real client keeps its session open across
  // messages rather than reconnecting per message; also far faster than one process per message.
  await new Promise((r) => setTimeout(r, 1500));
  ws.close();
  console.log(JSON.stringify({ sent: count }));
  process.exit(0);
}

async function rejectCheck(token) {
  try {
    await connect(wsUrl, token);
    console.error("connection was unexpectedly accepted");
    process.exit(1);
  } catch (e) {
    console.log(JSON.stringify({ rejected: true, reason: e.message }));
    process.exit(0);
  }
}

(async () => {
  try {
    if (mode === "subscribe-wait") {
      const [token, timeoutSeconds, expectedSubstring] = rest;
      await subscribeWait(token, Number(timeoutSeconds), expectedSubstring);
    } else if (mode === "send") {
      const [token, roomId, body] = rest;
      await send(token, roomId, body);
    } else if (mode === "send-many") {
      const [token, roomId, count, bodyPrefix] = rest;
      await sendMany(token, roomId, Number(count), bodyPrefix);
    } else if (mode === "reject-check") {
      const [token] = rest;
      await rejectCheck(token === "" ? undefined : token);
    } else {
      console.error(`unknown mode: ${mode}`);
      process.exit(2);
    }
  } catch (e) {
    console.error("unhandled error: " + e.message);
    process.exit(1);
  }
})();
