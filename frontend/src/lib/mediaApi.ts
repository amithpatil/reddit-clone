import { api } from './apiClient';

interface UploadUrlResponse {
  mediaId: string;
  uploadUrl: string;
}

export function requestUploadUrl(filename: string, contentType: string, byteSize: number): Promise<UploadUrlResponse> {
  return api.post('/api/media/upload-url', { filename, contentType, byteSize }) as Promise<UploadUrlResponse>;
}

export function completeUpload(mediaId: string): Promise<unknown> {
  return api.post(`/api/media/${mediaId}/complete`);
}

// Goes straight to the presigned storage URL, not through this app's own API — no Authorization header
// (the presigned URL itself carries its authorization) and no apiClient wrapper (that attaches the
// Bearer token and targets VITE_API_BASE_URL, neither of which applies here).
export async function uploadFileDirectly(uploadUrl: string, file: File, contentType: string): Promise<void> {
  const res = await fetch(uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': contentType },
    body: file,
  });
  if (!res.ok) {
    throw new Error(`Upload failed with status ${res.status}`);
  }
}
