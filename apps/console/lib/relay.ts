import type { DevicesResponse, OpenSessionResponse } from './types';

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

export function openSession(installId: string, cols: number, rows: number): Promise<OpenSessionResponse> {
  return request<OpenSessionResponse>('/api/open', { installId, cols, rows }, { acceptActiveSessionConflict: true });
}

export function closeSession(sessionId: string): Promise<{ ok: boolean }> {
  return request<{ ok: boolean }>('/api/close', { sessionId });
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
