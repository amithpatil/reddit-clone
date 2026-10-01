// The backend's Sanitizer (OWASP html-sanitizer) HTML-escapes text before storing it — e.g. an apostrophe
// becomes the literal string "&#39;" — meant for direct embedding as raw HTML (see its own comment). This
// decodes those entities back to plain characters for display as plain React text, not re-rendered as
// HTML — see this fix's own plan for why a full raw-HTML render isn't done instead.
export function decodeHtmlEntities(raw: string): string;
export function decodeHtmlEntities(raw: string | null | undefined): string | null | undefined;
export function decodeHtmlEntities(raw: string | null | undefined): string | null | undefined {
  if (raw == null) return raw;
  const el = document.createElement('textarea');
  el.innerHTML = raw;
  return el.value;
}
