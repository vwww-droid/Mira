'use client';

import { FitAddon } from '@xterm/addon-fit';
import { Terminal } from '@xterm/xterm';
import { useCallback, useEffect, useRef, useState } from 'react';
import { base64ToBytes, browserWsUrl, bytesToBase64, closeSession, openSession } from '@/lib/relay';
import type { MiraDevice, SessionStatus } from '@/lib/types';
import { shortId } from '@/lib/format';

type RelayMessage = {
  type?: string;
  sessionId?: string;
  state?: SessionStatus;
  dataBase64?: string;
  error?: string;
};

export type ConsoleEvent = {
  at: string;
  type: string;
  detail: string;
};

export function TerminalStage({
  device,
  onEvent,
  onRefreshDevices,
}: {
  device: MiraDevice | null;
  onEvent: (event: ConsoleEvent) => void;
  onRefreshDevices: () => void;
}) {
  const terminalHost = useRef<HTMLDivElement | null>(null);
  const terminalRef = useRef<Terminal | null>(null);
  const fitRef = useRef<FitAddon | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const sessionIdRef = useRef<string | null>(null);
  const deviceAttachedRef = useRef(false);
  const autoOpenDeviceRef = useRef<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [sessionStatus, setSessionStatus] = useState<SessionStatus>('idle');
  const [transportStatus, setTransportStatus] = useState<'idle' | 'connecting' | 'connected' | 'closed' | 'error'>('idle');
  const [size, setSize] = useState({ cols: 120, rows: 36 });

  const record = useCallback((type: string, detail: string) => {
    onEvent({ at: new Date().toLocaleTimeString(), type, detail });
  }, [onEvent]);

  const send = useCallback((message: Record<string, unknown>) => {
    const socket = socketRef.current;
    if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
  }, []);

  const fitAndResize = useCallback(() => {
    const terminal = terminalRef.current;
    const fit = fitRef.current;
    if (!terminal || !fit) return;
    fit.fit();
    setSize({ cols: terminal.cols, rows: terminal.rows });
    if (sessionIdRef.current && deviceAttachedRef.current) {
      send({ type: 'terminal.resize', sessionId: sessionIdRef.current, cols: terminal.cols, rows: terminal.rows });
    }
  }, [send]);

  useEffect(() => {
    if (!terminalHost.current || terminalRef.current) return;
    const terminal = new Terminal({
      cursorBlink: true,
      convertEol: true,
      fontFamily: '"JetBrains Mono", "SFMono-Regular", "SF Mono", Menlo, Consolas, monospace',
      fontSize: 13,
      lineHeight: 1.14,
      letterSpacing: 0,
      scrollback: 5000,
      theme: {
        background: '#111111',
        foreground: '#e6e6e6',
        cursor: '#54d6bd',
        selectionBackground: '#3a3a3a',
        black: '#000000',
        brightBlack: '#555555',
        red: '#e06c75',
        brightRed: '#ff7b86',
        green: '#54d6bd',
        brightGreen: '#70e8ce',
        yellow: '#d7ba7d',
        brightYellow: '#f0d48a',
        blue: '#61afef',
        brightBlue: '#7ec7ff',
        magenta: '#c678dd',
        brightMagenta: '#dc8cff',
        cyan: '#56b6c2',
        brightCyan: '#6ed3df',
        white: '#d6d6d6',
        brightWhite: '#ffffff',
      },
    });
    const fit = new FitAddon();
    terminal.loadAddon(fit);
    terminal.open(terminalHost.current);
    terminalRef.current = terminal;
    fitRef.current = fit;
    terminal.writeln('\x1b[38;5;245mMira PTY. Open a session to attach Android shell.\x1b[0m');
    window.setTimeout(fitAndResize, 0);

    const inputDisposable = terminal.onData((data) => {
      if (!sessionIdRef.current || !deviceAttachedRef.current) return;
      send({ type: 'terminal.input', sessionId: sessionIdRef.current, dataBase64: bytesToBase64(data) });
    });
    const resizeObserver = new ResizeObserver(() => window.setTimeout(fitAndResize, 60));
    resizeObserver.observe(terminalHost.current);

    return () => {
      inputDisposable.dispose();
      resizeObserver.disconnect();
      terminal.dispose();
      terminalRef.current = null;
      fitRef.current = null;
    };
  }, [fitAndResize, send]);

  const connectBrowser = useCallback((targetDevice: MiraDevice, nextSessionId: string) => {
    const oldSocket = socketRef.current;
    if (oldSocket) oldSocket.close();
    deviceAttachedRef.current = false;
    sessionIdRef.current = nextSessionId;
    setSessionId(nextSessionId);
    setSessionStatus('opening');
    setTransportStatus('connecting');
    terminalRef.current?.clear();
    terminalRef.current?.writeln('\x1b[38;5;245mMira relay session requested. Waiting for Android PTY attach...\x1b[0m');
    const socket = new WebSocket(browserWsUrl());
    socketRef.current = socket;
    socket.addEventListener('open', () => {
      setTransportStatus('connected');
      record('browser.attach', shortId(nextSessionId));
      send({ type: 'browser.attach', protocol: 1, installId: targetDevice.installId, sessionId: nextSessionId });
    });
    socket.addEventListener('message', (event) => {
      let message: RelayMessage;
      try {
        message = JSON.parse(event.data as string) as RelayMessage;
      } catch {
        return;
      }
      if (message.type === 'terminal.output') {
        deviceAttachedRef.current = true;
        terminalRef.current?.focus();
        terminalRef.current?.write(base64ToBytes(message.dataBase64 || ''));
      } else if (message.type === 'session.status') {
        const nextState = message.state || 'unknown';
        deviceAttachedRef.current = nextState === 'active';
        setSessionStatus(nextState);
        record('session.status', nextState);
        if (nextState === 'active') {
          fitAndResize();
          window.setTimeout(() => terminalRef.current?.focus(), 0);
        }
      } else if (message.type === 'session.close') {
        deviceAttachedRef.current = false;
        sessionIdRef.current = null;
        setSessionId(null);
        setSessionStatus('closed');
        record('session.close', shortId(message.sessionId));
        onRefreshDevices();
      } else if (message.type === 'error') {
        setTransportStatus('error');
        record('error', message.error || 'unknown error');
        terminalRef.current?.writeln(`\r\n\x1b[31m${message.error || 'relay error'}\x1b[0m`);
      }
    });
    socket.addEventListener('close', () => {
      setTransportStatus('closed');
      deviceAttachedRef.current = false;
      record('ws.close', shortId(nextSessionId));
    });
    socket.addEventListener('error', () => {
      setTransportStatus('error');
      record('ws.error', shortId(nextSessionId));
    });
  }, [fitAndResize, onRefreshDevices, record, send]);

  const handleOpen = useCallback(async () => {
    if (!device) return;
    if (sessionIdRef.current || sessionStatus === 'opening' || transportStatus === 'connecting') return;
    fitAndResize();
    setSessionStatus('opening');
    record('session.open', device.installId);
    try {
      const terminal = terminalRef.current;
      const opened = await openSession(device.installId, terminal?.cols || size.cols, terminal?.rows || size.rows);
      connectBrowser(device, opened.sessionId);
      onRefreshDevices();
    } catch (error) {
      setSessionStatus('error');
      setTransportStatus('error');
      const message = error instanceof Error ? error.message : String(error);
      record('open.failed', message);
      terminalRef.current?.writeln(`\r\n\x1b[31m${message}\x1b[0m`);
    }
  }, [connectBrowser, device, fitAndResize, onRefreshDevices, record, sessionStatus, size.cols, size.rows, transportStatus]);

  const handleClose = useCallback(async () => {
    const closingSession = sessionIdRef.current;
    if (!closingSession) return;
    record('session.close.request', shortId(closingSession));
    await closeSession(closingSession).catch((error) => record('close.failed', error instanceof Error ? error.message : String(error)));
    socketRef.current?.close();
    socketRef.current = null;
    sessionIdRef.current = null;
    deviceAttachedRef.current = false;
    setSessionId(null);
    setSessionStatus('closed');
    setTransportStatus('closed');
    onRefreshDevices();
  }, [onRefreshDevices, record]);

  useEffect(() => {
    if (!device) {
      autoOpenDeviceRef.current = null;
      return;
    }
    if (device.state === 'offline') {
      if (autoOpenDeviceRef.current === device.installId) autoOpenDeviceRef.current = null;
      return;
    }
    if (sessionId || sessionStatus === 'opening' || transportStatus === 'connecting') return;
    if (autoOpenDeviceRef.current === device.installId) return;
    autoOpenDeviceRef.current = device.installId;
    const timer = window.setTimeout(() => {
      terminalRef.current?.focus();
      void handleOpen();
    }, 180);
    return () => window.clearTimeout(timer);
  }, [device, handleOpen, sessionId, sessionStatus, transportStatus]);

  useEffect(() => {
    return () => {
      socketRef.current?.close();
    };
  }, []);

  const canOpen = Boolean(device && device.state !== 'offline' && !sessionId && sessionStatus !== 'opening' && transportStatus !== 'connecting');
  const openButtonClass = canOpen
    ? 'border-[#777] bg-[#f0f0f0] text-[#111] hover:bg-white'
    : 'cursor-not-allowed border-[#444] bg-[#222] text-[#777]';

  return (
    <section className="flex h-full min-h-0 flex-col overflow-hidden bg-[#111] text-[#e6e6e6]">
      <header className="flex h-7 shrink-0 items-center justify-between border-b border-[#2a2a2a] bg-[#0d0d0d] px-2 font-mono text-[12px] text-[#cfcfcf]">
        <div className="min-w-0 truncate">
          <span className="text-white">λ</span> {sessionStatus} · {transportStatus} · {size.cols} x {size.rows}
        </div>
        <div className="flex h-full items-center">
          <button type="button" onClick={fitAndResize} className="h-full border-l border-[#2a2a2a] px-2 text-[#cfcfcf] hover:bg-[#1b1b1b]">
            fit
          </button>
          {sessionId ? (
            <button type="button" onClick={handleClose} className="h-full border-l border-[#2a2a2a] px-2 text-[#f2b8b8] hover:bg-[#1b1b1b]">
              close
            </button>
          ) : (
            <button type="button" onClick={handleOpen} disabled={!canOpen} className={`ml-2 border px-2 py-0.5 ${openButtonClass}`}>
              open
            </button>
          )}
        </div>
      </header>
      <div className="relative min-h-0 flex-1 overflow-hidden bg-[#111]">
        {!device && (
          <div className="absolute inset-0 z-10 grid place-items-center bg-[#111] font-mono text-[12px] text-[#aaa]">
            Select a device.
          </div>
        )}
        <div ref={terminalHost} className="h-full w-full" onMouseDown={() => terminalRef.current?.focus()} />
      </div>
    </section>
  );
}
