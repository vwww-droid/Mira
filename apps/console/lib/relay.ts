import type {
  DevicesResponse,
  OpenSessionResponse,
  ScreenFrame,
  ScreenFrameMetadata,
  ScreenInputRequest,
  ScreenInputResponse,
  DeviceLogcatResponse,
  DeviceProcAuditResponse,
  ServerLogsResponse,
} from './types';

const RELAY_ORIGIN = process.env.NEXT_PUBLIC_RELAY_ORIGIN?.replace(/\/$/, '') || '';
const RELAY_WS = process.env.NEXT_PUBLIC_RELAY_WS?.replace(/\/$/, '') || '';

export function apiUrl(path: string): string {
  return `${RELAY_ORIGIN}${path}`;
}

export function browserWsUrl(): string {
  if (RELAY_WS) return `${RELAY_WS}/ws/browser`;
  if (typeof window === 'undefined') return '/ws/browser';
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws/browser`;
}

export function screenVideoWsUrl(): string {
  if (RELAY_WS) return `${RELAY_WS}/ws/screen/browser`;
  if (typeof window === 'undefined') return '/ws/screen/browser';
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws/screen/browser`;
}

async function request<T>(path: string, body?: unknown, options?: { acceptActiveSessionConflict?: boolean }): Promise<T> {
  const response = await fetch(apiUrl(path), {
    method: body ? 'POST' : 'GET',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  });
  const text = await response.text();
  let data: unknown = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    data = { error: text };
  }
  if (!response.ok) {
    if (
      options?.acceptActiveSessionConflict &&
      response.status === 409 &&
      typeof data === 'object' &&
      data &&
      'sessionId' in data &&
      typeof (data as { sessionId?: unknown }).sessionId === 'string'
    ) {
      return { sessionId: (data as { sessionId: string }).sessionId } as T;
    }
    const message = typeof data === 'object' && data && 'error' in data ? String((data as { error: unknown }).error) : response.statusText;
    throw new Error(message);
  }
  return data as T;
}

export function listDevices(): Promise<DevicesResponse> {
  return request<DevicesResponse>('/api/devices');
}

export function openSession(installId: string, cols: number, rows: number, cellWidth = 0, cellHeight = 0): Promise<OpenSessionResponse> {
  return request<OpenSessionResponse>('/api/open', { installId, cols, rows, cellWidth, cellHeight }, { acceptActiveSessionConflict: true });
}

export function closeSession(sessionId: string): Promise<{ ok: boolean }> {
  return request<{ ok: boolean }>('/api/close', { sessionId });
}

export function latestScreenFrame(installId: string): Promise<ScreenFrame> {
  return request<ScreenFrame>(`/api/screen/latest?installId=${encodeURIComponent(installId)}`);
}

export function latestScreenFrameMetadata(installId: string): Promise<ScreenFrameMetadata> {
  return request<ScreenFrameMetadata>(`/api/screen/latest?installId=${encodeURIComponent(installId)}&metadata=1`);
}

export function screenStreamUrl(installId: string, nonce = 0): string {
  return apiUrl(`/api/screen/stream?installId=${encodeURIComponent(installId)}&t=${nonce}`);
}

export function sendScreenInput(input: ScreenInputRequest): Promise<ScreenInputResponse> {
  const payload = { ...input };
  if (typeof payload.x === 'number') payload.x = Math.round(payload.x);
  if (typeof payload.y === 'number') payload.y = Math.round(payload.y);
  return request<ScreenInputResponse>('/api/screen/input', payload);
}

export function sendScreenTap(installId: string, x: number, y: number): Promise<ScreenInputResponse> {
  return sendScreenInput({
    installId,
    kind: 'tap',
    x,
    y,
  });
}

export function fetchServerLogs(cursor = 0, limit = 300): Promise<ServerLogsResponse> {
  return request<ServerLogsResponse>(`/api/server/logs?cursor=${encodeURIComponent(String(cursor))}&limit=${encodeURIComponent(String(limit))}`);
}

export function fetchDeviceLogcat(input: {
  installId: string;
  count?: number;
  buffer?: string;
  tag?: string;
  level?: string;
  timeoutMs?: number;
}): Promise<DeviceLogcatResponse> {
  return request<DeviceLogcatResponse>('/api/device/logcat', input);
}

export function fetchDeviceProcAudit(input: {
  installId: string;
  startPid?: number;
  maxPid?: number;
  count?: number;
  chunkSize?: number;
  timeoutMs?: number;
}): Promise<DeviceProcAuditResponse> {
  return request<DeviceProcAuditResponse>('/api/device/proc-audit', input);
}

export function postBrowserLog(scope: string, message: string, installId: string, details?: unknown): Promise<{ ok: boolean }> {
  return request<{ ok: boolean }>('/api/browser/log', {
    scope,
    message,
    installId,
    details,
  });
}

export function bytesToBase64(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

export function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value || '');
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}
