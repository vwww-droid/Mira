'use client';

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { ScrollText, Server } from 'lucide-react';
import { BackgroundGlow, Lobby, MiraBrandBar } from '@/components/ConsoleChrome';
import type { ConsoleEvent } from '@/components/TerminalStage';
import { Workbench } from '@/components/Workbench';
import { shortId } from '@/lib/format';
import { listDevices } from '@/lib/relay';
import type { MiraDevice } from '@/lib/types';

export default function RelayConsolePage() {
  const [devices, setDevices] = useState<MiraDevice[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [events, setEvents] = useState<ConsoleEvent[]>([]);
  const [activePanel, setActivePanel] = useState<'server-logs' | 'android-logcat' | null>(null);
  const devicesSnapshot = useRef('');

  const selectedDevice = useMemo(
    () => devices.find((device) => device.installId === selectedId) || null,
    [devices, selectedId],
  );
  const pushEvent = useCallback((event: ConsoleEvent) => {
    setEvents((current) => [event, ...current].slice(0, 80));
  }, []);

  const refreshDevices = useCallback(async () => {
    try {
      const data = await listDevices();
      const nextDevices = data.devices || [];
      const nextSnapshot = JSON.stringify(nextDevices);
      if (nextSnapshot !== devicesSnapshot.current) {
        devicesSnapshot.current = nextSnapshot;
        setDevices(nextDevices);
      }
    } catch {
      // Top chrome is intentionally hidden, so polling errors stay silent here.
    }
  }, []);

  useEffect(() => {
    const hash = new URLSearchParams(window.location.hash.replace(/^#/, ''));
    const initialDevice = hash.get('device');
    if (initialDevice) setSelectedId(initialDevice);
    refreshDevices();
    const timer = window.setInterval(refreshDevices, 1000);
    const onHash = () => {
      const nextHash = new URLSearchParams(window.location.hash.replace(/^#/, ''));
      setSelectedId(nextHash.get('device'));
    };
    window.addEventListener('hashchange', onHash);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('hashchange', onHash);
    };
  }, [refreshDevices]);

  const selectDevice = useCallback(
    (device: MiraDevice) => {
      setSelectedId(device.installId);
      window.location.hash = `device=${encodeURIComponent(device.installId)}`;
      pushEvent({ at: new Date().toLocaleTimeString(), type: 'device.select', detail: shortId(device.installId) });
    },
    [pushEvent],
  );

  const backToLobby = useCallback(() => {
    setSelectedId(null);
    window.location.hash = '';
    setActivePanel(null);
  }, []);

  return (
    <main className="relative h-screen overflow-hidden bg-[#f5f5f5] text-[#111]">
      <BackgroundGlow />
      <div className="relative z-10 flex h-full flex-col">
        <MiraBrandBar />
        {selectedDevice ? (
          <div className="pointer-events-none absolute right-3 top-1.5 z-30 flex items-center gap-1">
            <ToolbarIconButton
              title={activePanel === 'server-logs' ? '关闭服务端日志' : '打开服务端日志'}
              active={activePanel === 'server-logs'}
              onClick={() => setActivePanel((current) => (current === 'server-logs' ? null : 'server-logs'))}
            >
              <Server className="h-3.5 w-3.5" strokeWidth={1.8} />
            </ToolbarIconButton>
            <ToolbarIconButton
              title={activePanel === 'android-logcat' ? '关闭 Android logcat' : '打开 Android logcat'}
              active={activePanel === 'android-logcat'}
              onClick={() => setActivePanel((current) => (current === 'android-logcat' ? null : 'android-logcat'))}
            >
              <ScrollText className="h-3.5 w-3.5" strokeWidth={1.8} />
            </ToolbarIconButton>
          </div>
        ) : null}
        {selectedDevice ? (
          <Workbench
            devices={devices}
            selectedDevice={selectedDevice}
            events={events}
            onSelect={selectDevice}
            onBack={backToLobby}
            onEvent={pushEvent}
            onRefreshDevices={refreshDevices}
            activePanel={activePanel}
            onClosePanel={() => setActivePanel(null)}
          />
        ) : (
          <Lobby devices={devices} onSelect={selectDevice} />
        )}
      </div>
    </main>
  );
}

function ToolbarIconButton({
  title,
  active,
  onClick,
  children,
}: {
  title: string;
  active: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      title={title}
      onClick={onClick}
      className={`pointer-events-auto grid h-7 w-7 place-items-center rounded border transition ${
        active
          ? 'border-[#6c9ff0] bg-[#e9f2ff] text-[#2f6fd0] shadow-sm'
          : 'border-[#c2c2c2] bg-[#fafafa] text-[#555] hover:bg-white'
      }`}
    >
      {children}
    </button>
  );
}
