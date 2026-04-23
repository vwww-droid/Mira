'use client';

import { FitAddon } from '@xterm/addon-fit';
import { Terminal } from '@xterm/xterm';
import { useCallback, useEffect, useRef, useState } from 'react';
import { base64ToBytes, browserWsUrl, bytesToBase64, openSession } from '@/lib/relay';
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

  const measureCellSize = useCallback(() => {
    const terminal = terminalRef.current;
    const host = terminalHost.current;
    if (!terminal || !host || terminal.cols <= 0 || terminal.rows <= 0) return { cellWidth: 0, cellHeight: 0 };
    return {
      cellWidth: Math.max(Math.round(host.clientWidth / terminal.cols), 0),
      cellHeight: Math.max(Math.round(host.clientHeight / terminal.rows), 0),
    };
  }, []);

  const fitAndResize = useCallback(() => {
    const terminal = terminalRef.current;
    const fit = fitRef.current;
    if (!terminal || !fit) return;
    fit.fit();
    const { cellWidth, cellHeight } = measureCellSize();
    setSize({ cols: terminal.cols, rows: terminal.rows });
    if (sessionIdRef.current && deviceAttachedRef.current) {
      send({ type: 'terminal.resize', sessionId: sessionIdRef.current, cols: terminal.cols, rows: terminal.rows, cellWidth, cellHeight });
    }
  }, [measureCellSize, send]);

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
    const socket = new WebSocket(browserWsUrl());
    socketRef.current = socket;
    socket.addEventListener('open', () => {
      if (socketRef.current !== socket) return;
      setTransportStatus('connected');
      record('browser.attach', shortId(nextSessionId));
      send({ type: 'browser.attach', protocol: 1, installId: targetDevice.installId, sessionId: nextSessionId });
    });
    socket.addEventListener('message', (event) => {
      if (socketRef.current !== socket) return;
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
        autoOpenDeviceRef.current = null;
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
      if (socketRef.current !== socket) return;
      setTransportStatus('closed');
      deviceAttachedRef.current = false;
      sessionIdRef.current = null;
      autoOpenDeviceRef.current = null;
      setSessionId(null);
      setSessionStatus('closed');
      record('ws.close', shortId(nextSessionId));
      onRefreshDevices();
    });
    socket.addEventListener('error', () => {
      if (socketRef.current !== socket) return;
      setTransportStatus('error');
      record('ws.error', shortId(nextSessionId));
    });
  }, [fitAndResize, onRefreshDevices, record, send]);

  const handleOpen = useCallback(async () => {
    if (!device) return;
    if (sessionIdRef.current || sessionStatus === 'opening' || transportStatus === 'connecting') return;
    fitAndResize();
    terminalRef.current?.clear();
    setSessionStatus('opening');
    record('session.open', device.installId);
    try {
      const terminal = terminalRef.current;
      const { cellWidth, cellHeight } = measureCellSize();
      const opened = await openSession(device.installId, terminal?.cols || size.cols, terminal?.rows || size.rows, cellWidth, cellHeight);
      connectBrowser(device, opened.sessionId);
      onRefreshDevices();
    } catch (error) {
      autoOpenDeviceRef.current = null;
      setSessionStatus('error');
      setTransportStatus('error');
      const message = error instanceof Error ? error.message : String(error);
      record('open.failed', message);
      terminalRef.current?.writeln(`\r\n\x1b[31m${message}\x1b[0m`);
    }
  }, [connectBrowser, device, fitAndResize, measureCellSize, onRefreshDevices, record, sessionStatus, size.cols, size.rows, transportStatus]);

  const focusTerminal = useCallback(() => {
    const active = document.activeElement;
    if (active instanceof HTMLElement && active !== terminalHost.current && !terminalHost.current?.contains(active)) active.blur();
    terminalRef.current?.focus();
  }, []);

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
    const timer = window.setTimeout(() => {
      if (autoOpenDeviceRef.current === device.installId || sessionIdRef.current) return;
      autoOpenDeviceRef.current = device.installId;
      focusTerminal();
      void handleOpen();
    }, 180);
    return () => window.clearTimeout(timer);
  }, [device, focusTerminal, handleOpen, sessionId, sessionStatus, transportStatus]);

  useEffect(() => {
    return () => {
      socketRef.current?.close();
    };
  }, []);

  return (
    <section className="flex h-full min-h-0 flex-col overflow-hidden bg-[#111] text-[#e6e6e6]">
      <div className="relative min-h-0 flex-1 overflow-hidden bg-[#111]">
        {!device && (
          <div className="absolute inset-0 z-10 grid place-items-center bg-[#111] font-mono text-[12px] text-[#aaa]">
            Select a device.
          </div>
        )}
        <div ref={terminalHost} className="h-full w-full" onMouseDownCapture={focusTerminal} />
      </div>
    </section>
  );
}
