'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { BackgroundGlow, Lobby, TopBar } from '@/components/ConsoleChrome';
import type { ConsoleEvent } from '@/components/TerminalStage';
import { Workbench } from '@/components/Workbench';
import { copyText, shortId } from '@/lib/format';
import { listDevices } from '@/lib/relay';
import type { MiraDevice } from '@/lib/types';

export default function RelayConsolePage() {
  const [devices, setDevices] = useState<MiraDevice[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [relayUrl, setRelayUrl] = useState('');
  const [events, setEvents] = useState<ConsoleEvent[]>([]);
  const [copied, setCopied] = useState(false);
  const devicesSnapshot = useRef('');

  const selectedDevice = useMemo(
    () => devices.find((device) => device.installId === selectedId) || null,
    [devices, selectedId],
  );
  const onlineDevices = devices.filter((device) => device.state !== 'offline');

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
      setError(null);
    } catch (fetchError) {
      setError(fetchError instanceof Error ? fetchError.message : String(fetchError));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    setRelayUrl(window.location.origin);
    const hash = new URLSearchParams(window.location.hash.replace(/^#/, ''));
    const initialDevice = hash.get('device');
    if (initialDevice) setSelectedId(initialDevice);
    refreshDevices();
    const timer = window.setInterval(refreshDevices, 2000);
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

  const handleCopyRelay = useCallback(async () => {
    await copyText(relayUrl);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1200);
  }, [relayUrl]);

  return (
    <main className="relative h-screen overflow-hidden bg-[#050816] text-slate-100">
      <BackgroundGlow />
      <div className="relative z-10 flex h-full flex-col p-4 lg:p-5">
        <TopBar
          relayUrl={relayUrl}
          devices={devices}
          onlineCount={onlineDevices.length}
          loading={loading}
          error={error}
          copied={copied}
          onCopyRelay={handleCopyRelay}
          onRefresh={refreshDevices}
        />
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
