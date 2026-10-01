// Exported so the chat STOMP client (ChatContext) can derive its ws(s):// broker URL from the same single
// source of truth instead of duplicating the env var name and fallback literal in a second place.
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8081';

export class ApiError extends Error {
  status: number;
  body: unknown;

  constructor(status: number, body: unknown, message: string) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

type TokenGetter = () => string | null;
type RefreshFn = () => Promise<string | null>;

let getAccessToken: TokenGetter = () => null;
let refreshAccessToken: RefreshFn = async () => null;

export function configureApiClient(opts: { getAccessToken: TokenGetter; refresh: RefreshFn }) {
  getAccessToken = opts.getAccessToken;
  refreshAccessToken = opts.refresh;
}

// Exposed for the chat STOMP client (ChatContext), which needs the current bearer token to send as a
// native STOMP header on every connect/reconnect — reuses this same closure rather than threading a
// second copy of "the current token" through a separate context.
export function getCurrentAccessToken(): string | null {
  return getAccessToken();
}

async function parseBody(res: Response): Promise<unknown> {
  const text = await res.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

async function request(path: string, options: RequestInit = {}, isRetry = false): Promise<unknown> {
  const token = getAccessToken();
  const headers = new Headers(options.headers);
  headers.set('Content-Type', 'application/json');
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const res = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
    credentials: 'include',
  });

  // A 401 on an authenticated call means the access token expired mid-session — retry once after a
  // silent refresh before surfacing the error, so a short-TTL access token never surfaces to the user
  // as a spurious "logged out" moment.
  if (res.status === 401 && !isRetry && token) {
    const newToken = await refreshAccessToken();
    if (newToken) {
      return request(path, options, true);
    }
  }

  const body = await parseBody(res);
  if (!res.ok) {
    // GlobalExceptionHandler.java always includes "message" for a known exception (e.g. 409 "email
    // in use"), but Spring's default handler for @Valid failures (400) does not — fall back to a
    // generic status-appropriate message rather than showing the raw "Bad Request" reason phrase.
    const rawMessage =
      body && typeof body === 'object' && 'message' in body
        ? String((body as { message?: unknown }).message)
        : '';
    const message = rawMessage || (res.status === 400 ? 'Please check your input and try again.' : res.statusText);
    throw new ApiError(res.status, body, message);
  }
  return body;
}

export const api = {
  get: (path: string) => request(path, { method: 'GET' }),
  post: (path: string, data?: unknown, headers?: Record<string, string>) =>
    request(path, { method: 'POST', body: data !== undefined ? JSON.stringify(data) : undefined, headers }),
  patch: (path: string, data?: unknown) =>
    request(path, { method: 'PATCH', body: data !== undefined ? JSON.stringify(data) : undefined }),
  put: (path: string, data?: unknown) =>
    request(path, { method: 'PUT', body: data !== undefined ? JSON.stringify(data) : undefined }),
  del: (path: string, data?: unknown) =>
    request(path, { method: 'DELETE', body: data !== undefined ? JSON.stringify(data) : undefined }),
};
