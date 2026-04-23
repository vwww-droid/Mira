'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
  }, []);

  return (
    <main className="relative h-screen overflow-hidden bg-[#f5f5f5] text-[#111]">
      <BackgroundGlow />
      <div className="relative z-10 flex h-full flex-col">
        <MiraBrandBar />
        {selectedDevice ? (
          <Workbench
            devices={devices}
            selectedDevice={selectedDevice}
            events={events}
            onSelect={selectDevice}
            onBack={backToLobby}
            onEvent={pushEvent}
            onRefreshDevices={refreshDevices}
          />
        ) : (
          <Lobby devices={devices} onSelect={selectDevice} />
        )}
      </div>
    </main>
  );
}
