// Shared client-side pre-upload validation constants — kept in sync with app.media.max-image-bytes'/
// max-video-bytes' defaults (see application.yml). Used by both useMediaUpload (single image/video) and
// useGalleryUpload (multiple images) so the two don't each carry their own copy.
export const MAX_IMAGE_BYTES = 20 * 1024 * 1024;
export const MAX_VIDEO_BYTES = 200 * 1024 * 1024;

export const IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);
// GIFs are converted server-side to a muted looping video — MediaService classifies them as mediaType
// "video", not "image", so the post kind must match that, not the file's apparent image/* mime type.
export const VIDEO_TYPES = new Set(['video/mp4', 'video/quicktime', 'video/webm', 'image/gif']);
