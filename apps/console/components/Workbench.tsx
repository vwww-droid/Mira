'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import { DeviceFrame } from '@/components/DeviceFrame';
import { ConsoleEvent, TerminalStage } from '@/components/TerminalStage';
import { deviceTitle, shortId } from '@/lib/format';
import { fetchDeviceLogcat, fetchDeviceProcAudit, fetchServerLogs } from '@/lib/relay';
import type { MiraDevice } from '@/lib/types';

export function Workbench({
  selectedDevice,
  onEvent,
  onRefreshDevices,
  activePanel,
  onClosePanel,
}: {
  devices: MiraDevice[];
  selectedDevice: MiraDevice;
  events: ConsoleEvent[];
  onSelect: (device: MiraDevice) => void;
  onBack: () => void;
  onEvent: (event: ConsoleEvent) => void;
  onRefreshDevices: () => void;
  activePanel: 'server-logs' | 'android-logcat' | null;
  onClosePanel: () => void;
}) {
  const hostRef = useRef<HTMLElement | null>(null);
  const serverLogsCursorRef = useRef(0);
  const [hostSize, setHostSize] = useState({ width: 0, height: 0 });
  const [manualLeftWidth, setManualLeftWidth] = useState<number | null>(null);
  const [infoHeight, setInfoHeight] = useState(168);
  const [serverLogsText, setServerLogsText] = useState('');
  const [serverLogsLoading, setServerLogsLoading] = useState(false);
  const [serverLogsError, setServerLogsError] = useState('');
  const [serverLogsAt, setServerLogsAt] = useState('');
  const [logcatText, setLogcatText] = useState('');
  const [logcatLoading, setLogcatLoading] = useState(false);
  const [logcatError, setLogcatError] = useState('');
  const [logcatAt, setLogcatAt] = useState('');
  const [logcatCount, setLogcatCount] = useState(5000);
  const [logcatBuffer, setLogcatBuffer] = useState('all');
  const [logcatTag, setLogcatTag] = useState('');
  const [logcatLevel, setLogcatLevel] = useState('');
  const [logcatSearch, setLogcatSearch] = useState('');
  const adaptiveLeftWidth = useMemo(() => adaptiveDevicePaneWidth(selectedDevice, hostSize), [hostSize, selectedDevice]);
  const leftWidth = clampPaneSize(manualLeftWidth ?? adaptiveLeftWidth, minDevicePaneWidth(hostSize), maxDevicePaneWidth(hostSize));
  const platform = devicePlatform(selectedDevice);
  const filteredLogcat = useMemo(() => filterLogText(logcatText, logcatSearch), [logcatSearch, logcatText]);

  const loadServerLogs = useCallback(
    async (mode: 'reset' | 'append' = 'append') => {
      const shouldReset = mode === 'reset';
      if (shouldReset) {
        serverLogsCursorRef.current = 0;
        setServerLogsLoading(true);
      }
      setServerLogsError('');
      try {
        const response = await fetchServerLogs(serverLogsCursorRef.current, shouldReset ? 300 : 200);
        serverLogsCursorRef.current = response.nextCursor || serverLogsCursorRef.current;
        const incoming = response.lines.join('\n').trim();
        setServerLogsText((current) => {
          if (!incoming) return shouldReset || response.reset ? '' : current;
          if (shouldReset || response.reset || !current) return incoming;
          return `${current}\n${incoming}`.split('\n').slice(-1200).join('\n');
        });
        setServerLogsAt(new Date().toLocaleTimeString());
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setServerLogsError(message);
        if (shouldReset) setServerLogsText(message || 'Server log request failed');
      } finally {
        if (shouldReset) setServerLogsLoading(false);
      }
    },
    [],
  );

  useEffect(() => {
    if (activePanel !== 'server-logs') return;
    void loadServerLogs('reset');
  }, [activePanel, loadServerLogs]);

  useEffect(() => {
    if (activePanel !== 'server-logs') return;
    const timer = window.setInterval(() => {
      void loadServerLogs('append');
    }, 1000);
    return () => window.clearInterval(timer);
  }, [activePanel, loadServerLogs]);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    const update = () => setHostSize({ width: host.clientWidth, height: host.clientHeight });
    update();
    const observer = new ResizeObserver(update);
    observer.observe(host);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    setManualLeftWidth(null);
    setLogcatText('');
    setLogcatError('');
    setLogcatAt('');
    setLogcatSearch('');
  }, [selectedDevice.installId]);

  const loadLogcat = useCallback(
    async () => {
      if (devicePlatform(selectedDevice) !== 'android') {
        setLogcatError('当前只支持 Android 设备的 logcat.');
        setLogcatText('');
        return;
      }
      setLogcatLoading(true);
      setLogcatError('');
      try {
        const response = await fetchDeviceLogcat({
          installId: selectedDevice.installId,
          count: logcatCount,
          buffer: logcatBuffer,
          tag: logcatTag.trim(),
          level: logcatLevel,
          timeoutMs: 10000,
        });
        const stderr = response.stderr ? `\n\n[stderr]\n${response.stderr.trim()}` : '';
        const output = `${response.stdout || ''}${stderr}`.trim();
        setLogcatText((output || '(logcat returned no output)').split('\n').slice(-5000).join('\n'));
        setLogcatAt(new Date().toLocaleTimeString());
        if (!response.ok) setLogcatError(response.error || response.stderr || `logcat exited with ${response.exitCode}`);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setLogcatError(message);
        setLogcatText(message || 'logcat request failed');
      } finally {
        setLogcatLoading(false);
      }
    },
    [logcatBuffer, logcatCount, logcatLevel, logcatTag, selectedDevice],
  );

  const runProcAuditScan = useCallback(
    async () => {
      if (devicePlatform(selectedDevice) !== 'android') {
        setLogcatError('当前只支持 Android 设备的 proc audit scan.');
        setLogcatText('');
        return;
      }
      setLogcatLoading(true);
      setLogcatError('');
      try {
        const response = await fetchDeviceProcAudit({
          installId: selectedDevice.installId,
          maxPid: 10000,
          count: logcatCount,
          chunkSize: 500,
          timeoutMs: 70000,
        });
        const stderr = response.stderr ? `\n\n[stderr]\n${response.stderr.trim()}` : '';
        const output = `${response.stdout || ''}${stderr}`.trim();
        setLogcatText(output || '(mira-proc-audit returned no output)');
        setLogcatAt(new Date().toLocaleTimeString());
        if (!response.ok) {
          setLogcatError(response.error || response.stderr || `mira-proc-audit exited with ${response.exitCode}`);
        }
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setLogcatError(message);
        setLogcatText(message || 'mira-proc-audit request failed');
      } finally {
        setLogcatLoading(false);
      }
    },
    [logcatCount, selectedDevice],
  );

  useEffect(() => {
    if (activePanel !== 'android-logcat') return;
    if (logcatText || logcatLoading) return;
    void loadLogcat();
  }, [activePanel, loadLogcat, logcatLoading, logcatText]);

  useEffect(() => {
    setInfoHeight((current) => clampPaneSize(current, minInfoPanelHeight(hostSize), maxInfoPanelHeight(hostSize)));
  }, [hostSize]);

  const startDeviceResize = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      event.preventDefault();
      const pointerId = event.pointerId;
      event.currentTarget.setPointerCapture(pointerId);
      const startX = event.clientX;
      const startWidth = leftWidth;
      const minWidth = minDevicePaneWidth(hostSize);
      const maxWidth = maxDevicePaneWidth(hostSize);

      const onMove = (moveEvent: PointerEvent) => {
        setManualLeftWidth(clampPaneSize(startWidth + moveEvent.clientX - startX, minWidth, maxWidth));
      };
      const onUp = () => {
        window.removeEventListener('pointermove', onMove);
        window.removeEventListener('pointerup', onUp);
        window.removeEventListener('pointercancel', onUp);
      };
      window.addEventListener('pointermove', onMove);
      window.addEventListener('pointerup', onUp);
      window.addEventListener('pointercancel', onUp);
    },
    [hostSize, leftWidth],
  );

  const startInfoResize = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      event.preventDefault();
      const pointerId = event.pointerId;
      event.currentTarget.setPointerCapture(pointerId);
      const startY = event.clientY;
      const startHeight = infoHeight;
      const minHeight = minInfoPanelHeight(hostSize);
      const maxHeight = maxInfoPanelHeight(hostSize);

      const onMove = (moveEvent: PointerEvent) => {
        setInfoHeight(clampPaneSize(startHeight + startY - moveEvent.clientY, minHeight, maxHeight));
      };
      const onUp = () => {
        window.removeEventListener('pointermove', onMove);
        window.removeEventListener('pointerup', onUp);
        window.removeEventListener('pointercancel', onUp);
      };
      window.addEventListener('pointermove', onMove);
      window.addEventListener('pointerup', onUp);
      window.addEventListener('pointercancel', onUp);
    },
    [hostSize, infoHeight],
  );

  return (
    <section
      ref={hostRef}
      className="relative grid min-h-0 flex-1 overflow-hidden bg-[#f5f5f5] text-[#111]"
      style={{ gridTemplateColumns: `${leftWidth}px 6px minmax(0,1fr)` }}
    >
      <DeviceFrame device={selectedDevice} />
      <div
        role="separator"
        aria-label="Resize screen pane"
        className="relative z-20 cursor-col-resize border-r border-[#cfcfcf] bg-[#f5f5f5] hover:bg-[#dceaff]"
        onPointerDown={startDeviceResize}
      />
      <div className="grid min-h-0" style={{ gridTemplateRows: `minmax(0,1fr) 6px ${infoHeight}px` }}>
        <TerminalStage device={selectedDevice} onEvent={onEvent} onRefreshDevices={onRefreshDevices} />
        <div
          role="separator"
          aria-label="Resize info pane"
          className="relative z-20 cursor-row-resize border-y border-[#cfcfcf] bg-[#f5f5f5] hover:bg-[#dceaff]"
          onPointerDown={startInfoResize}
        />
        <InfoPanel device={selectedDevice} />
      </div>
      <ServerLogsPanel
        open={activePanel === 'server-logs'}
        onClose={onClosePanel}
        text={serverLogsText}
        loading={serverLogsLoading}
        error={serverLogsError}
        updatedAt={serverLogsAt}
        onRefresh={() => loadServerLogs('reset')}
      />
      <AndroidLogcatPanel
        open={activePanel === 'android-logcat'}
        onClose={onClosePanel}
        platform={platform}
        text={filteredLogcat.text}
        rawLineCount={filteredLogcat.rawLineCount}
        shownLineCount={filteredLogcat.shownLineCount}
        loading={logcatLoading}
        error={logcatError}
        updatedAt={logcatAt}
        count={logcatCount}
        buffer={logcatBuffer}
        tag={logcatTag}
        level={logcatLevel}
        search={logcatSearch}
        onCountChange={setLogcatCount}
        onBufferChange={setLogcatBuffer}
        onTagChange={setLogcatTag}
        onLevelChange={setLogcatLevel}
        onSearchChange={setLogcatSearch}
        onRefresh={() => loadLogcat()}
        onProcAudit={() => runProcAuditScan()}
      />
    </section>
  );
}

function adaptiveDevicePaneWidth(device: MiraDevice, hostSize: { width: number; height: number }) {
  const screen = device.outline?.screen;
  const screenWidth = Number(screen?.width) || 1080;
  const screenHeight = Number(screen?.height) || 2280;
  const aspect = screenWidth > 0 && screenHeight > 0 ? screenWidth / screenHeight : 9 / 19.5;
  const target = (hostSize.height || 720) * aspect;
  const maxByWidth = hostSize.width ? hostSize.width * 0.48 : 620;
  const max = Math.max(240, Math.min(640, maxByWidth));
  const min = Math.min(300, max);
  return Math.round(Math.max(min, Math.min(target, max)));
}

function minDevicePaneWidth(hostSize: { width: number; height: number }) {
  return Math.min(220, Math.max(160, hostSize.width - 360));
}

function maxDevicePaneWidth(hostSize: { width: number; height: number }) {
  if (!hostSize.width) return 640;
  return Math.max(minDevicePaneWidth(hostSize), Math.min(hostSize.width - 360, Math.round(hostSize.width * 0.72)));
}

function minInfoPanelHeight(hostSize: { width: number; height: number }) {
  return Math.min(96, Math.max(72, hostSize.height - 260));
}

function maxInfoPanelHeight(hostSize: { width: number; height: number }) {
  if (!hostSize.height) return 360;
  return Math.max(minInfoPanelHeight(hostSize), hostSize.height - 220);
}

function clampPaneSize(value: number, min: number, max: number) {
  return Math.round(Math.max(min, Math.min(value, max)));
}

function InfoPanel({
  device,
}: {
  device: MiraDevice;
}) {
  const platform = devicePlatform(device);
  const rows: Array<[string, string]> = [];
  addKnownRow(rows, 'Architecture', device.arch);
  addKnownRow(rows, 'Model', modelSummary(device));
  rows.push(['Device ID', shortId(device.installId, 36)]);
  addKnownRow(rows, 'Screen', screenStateText(device));
  rows.push(...currentSurfaceRows(device, platform));

  return (
    <section className="grid h-full min-h-0 grid-cols-[minmax(0,1fr)_180px] border-t border-[#cfcfcf] bg-[#f5f5f5] text-[#111]">
      <div className="h-full overflow-auto px-3 py-2 font-mono text-[12px] leading-5">
        <div className="mb-2 border-b border-[#d8d8d8] pb-1 text-[13px] font-semibold tracking-[0.12em] text-[#3f7fd3]">INFO</div>
        <div className="grid max-w-[920px] grid-cols-[132px_minmax(0,1fr)] gap-x-6">
          {rows.map(([key, value]) => (
            <div key={key} className="contents">
              <div className="text-[#555]">{key}:</div>
              <div className="truncate text-[#111]">{value}</div>
            </div>
          ))}
        </div>
        <div className="mt-2 text-[11px] text-[#666]">Selected: {deviceTitle(device)}</div>
      </div>
      <DeviceMetricsPanel device={device} />
    </section>
  );
}

function addKnownRow(rows: Array<[string, string]>, key: string, value: string | number | null | undefined) {
  if (value === null || value === undefined) return;
  const text = String(value).trim();
  if (!text || text === 'unknown' || text === 'not reported') return;
  rows.push([key, text]);
}

type DevicePlatform = 'android' | 'ios' | 'unknown';

function devicePlatform(device: MiraDevice): DevicePlatform {
  const platform = `${device.platform || ''} ${device.osName || ''}`.toLowerCase();
  if (platform.includes('ios')) return 'ios';
  if (platform.includes('android')) return 'android';

  const model = String(device.model || '').toLowerCase();
  const packageName = String(device.packageName || '').toLowerCase();
  if (model === 'ios' || model.includes('iphone') || model.includes('ipad') || packageName.endsWith('.ios')) return 'ios';
  if (device.androidIdHash || device.outline?.activityName) return 'android';
  return 'unknown';
}

function modelSummary(device: MiraDevice) {
  const model = String(device.model || device.deviceName || '').trim();
  if (!model) return null;
  const details = [systemVersionText(device), screenSizeText(device)].filter(Boolean);
  if (!details.length) return model;
  return `${model} (${details.join(', ')})`;
}

function systemVersionText(device: MiraDevice): string | null {
  const osName = String(device.osName || device.platform || '').trim();
  const osVersion = String(device.osVersion || '').trim();
  if (osName && osVersion) return `${osName} ${osVersion}`;
  if (osVersion) return osVersion;
  if (isAndroidDevice(device) && device.sdk) return androidVersionText(device.sdk);
  return null;
}

function isAndroidDevice(device: MiraDevice) {
  return devicePlatform(device) === 'android';
}

function androidVersionText(sdk: number | string) {
  const apiLevel = Number(sdk);
  const release = androidReleaseByApi[apiLevel];
  if (release) return `Android ${release}`;
  return Number.isFinite(apiLevel) && apiLevel > 0 ? `Android SDK ${Math.round(apiLevel)}` : null;
}

const androidReleaseByApi: Record<number, string> = {
  21: '5.0',
  22: '5.1',
  23: '6.0',
  24: '7.0',
  25: '7.1',
  26: '8.0',
  27: '8.1',
  28: '9',
  29: '10',
  30: '11',
  31: '12',
  32: '12L',
  33: '13',
  34: '14',
  35: '15',
  36: '16',
};

function screenSizeText(device: MiraDevice) {
  const outlineScreen = device.outline?.screen;
  const info = device.screenInfo;
  const width = positiveNumber(info?.sourceWidth) ?? positiveNumber(outlineScreen?.width) ?? positiveNumber(device.outline?.width);
  const height = positiveNumber(info?.sourceHeight) ?? positiveNumber(outlineScreen?.height) ?? positiveNumber(device.outline?.height);
  return width && height ? `${width}x${height}` : null;
}

function screenStateText(device: MiraDevice) {
  if (device.screenSource || device.screenInfo || device.screenLastSeen || device.outline) return 'on,unlocked';
  return null;
}

function currentSurfaceRows(device: MiraDevice, platform: DevicePlatform): Array<[string, string]> {
  if (platform === 'ios') {
    const rows: Array<[string, string]> = [];
    addKnownRow(rows, 'Application', device.packageName || device.outline?.packageName);
    addKnownRow(rows, 'View', iosViewText(device));
    return rows;
  }

  const rows: Array<[string, string]> = [];
  addKnownRow(rows, 'Application', device.outline?.packageName || device.packageName);
  addKnownRow(rows, 'Activity', device.outline?.activityName);
  return rows;
}

function iosViewText(device: MiraDevice) {
  if (device.screenSource === 'app-key-window') return 'App key window';
  return device.screenSource || 'App view';
}

function positiveNumber(value: unknown) {
  const numberValue = Number(value);
  return Number.isFinite(numberValue) && numberValue > 0 ? Math.round(numberValue) : null;
}

type MetricPoint = {
  at: number;
  cpu: number;
  mem: number;
  net: number;
};

function DeviceMetricsPanel({ device }: { device: MiraDevice }) {
  const [history, setHistory] = useState<MetricPoint[]>([]);

  useEffect(() => {
    setHistory([]);
  }, [device.installId]);

  useEffect(() => {
    const metrics = device.metrics;
    if (!metrics) return;
    const next: MetricPoint = {
      at: Number(metrics.sampledAt) || Date.now(),
      cpu: normalizedMetric(metrics.cpuPercent),
      mem: normalizedMetric(metrics.memoryPercent),
      net: Math.max(0, Number(metrics.networkBps) || 0),
    };
    setHistory((current) => {
      const last = current[current.length - 1];
      if (last && last.at === next.at) return current;
      return [...current, next].slice(-48);
    });
  }, [device.metrics]);

  const latest = history[history.length - 1];
  const stale = !latest || Date.now() - latest.at > 3000;
  const netBps = Number(device.metrics?.networkBps) || 0;
  const netScale = Math.max(1024, ...history.map((point) => point.net)) * 1.25;

  return (
    <div className="grid h-full min-h-0 grid-rows-3 border-l border-[#d8d8d8] bg-[#f5f5f5] font-mono text-[10px] leading-3 text-[#555]">
      <MetricChart label="CPU" value={latest?.cpu ?? -1} history={history.map((point) => point.cpu)} stale={stale} color="#59ca58" />
      <MetricChart label="MEM" value={latest?.mem ?? -1} history={history.map((point) => point.mem)} stale={stale} color="#3f7fd3" />
      <MetricChart label="NET" value={netBps} history={history.map((point) => point.net)} stale={stale} format={formatBytesPerSecond} scaleMax={netScale} color="#e29b3f" />
    </div>
  );
}

function MetricChart({
  label,
  value,
  history,
  stale,
  format = formatPercent,
  scaleMax = 100,
  color,
}: {
  label: string;
  value: number;
  history: number[];
  stale: boolean;
  format?: (value: number) => string;
  scaleMax?: number;
  color: string;
}) {
  return (
    <div className="grid min-h-0 grid-cols-[1fr_54px] items-stretch gap-1 border-b border-[#e1e1e1] px-1 py-1 last:border-b-0">
      <svg viewBox="0 0 104 36" className="h-full w-full overflow-visible">
        <path d={sparkPath(history, 104, 36, scaleMax)} fill="none" stroke={stale ? '#bdbdbd' : color} strokeWidth="1.3" />
        <line x1="0" y1="35" x2="104" y2="35" stroke="#d7d7d7" strokeWidth="0.8" />
      </svg>
      <div className="flex flex-col justify-center text-right">
        <div className="text-[#333]">{format(value)}</div>
        <div className="text-[9px] text-[#777]">{label}</div>
      </div>
    </div>
  );
}

function normalizedMetric(value: unknown) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric) || numeric < 0) return 0;
  return Math.max(0, Math.min(100, numeric));
}

function sparkPath(values: number[], width: number, height: number, maxValue = 100) {
  if (!values.length) return '';
  const scale = Math.max(1, maxValue);
  if (values.length === 1) {
    const y = height - (Math.max(0, Math.min(scale, values[0])) / scale) * height;
    return `M 0 ${y.toFixed(1)} L ${width} ${y.toFixed(1)}`;
  }
  return values
    .map((value, index) => {
      const x = (index / Math.max(1, values.length - 1)) * width;
      const y = height - (Math.max(0, Math.min(scale, value)) / scale) * height;
      return `${index === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`;
    })
    .join(' ');
}

function formatPercent(value: number) {
  if (!Number.isFinite(value) || value < 0) return '--';
  return `${value.toFixed(1)}%`;
}

function formatBytesPerSecond(value: number) {
  if (!Number.isFinite(value) || value < 0) return '--';
  if (value < 1024) return `${value.toFixed(0)}B/s`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)}K/s`;
  return `${(value / 1024 / 1024).toFixed(1)}M/s`;
}



function filterLogText(text: string, query: string) {
  const lines = text ? text.split('\n') : [];
  const terms = query
    .trim()
    .toLowerCase()
    .split(/[,\s|]+/)
    .map((term) => term.trim())
    .filter(Boolean);
  if (!terms.length) {
    return {
      text,
      rawLineCount: lines.filter(Boolean).length,
      shownLineCount: lines.filter(Boolean).length,
    };
  }
  const filtered = lines.filter((line) => {
    const lower = line.toLowerCase();
    return terms.some((term) => lower.includes(term));
  });
  return {
    text: filtered.join('\n'),
    rawLineCount: lines.filter(Boolean).length,
    shownLineCount: filtered.filter(Boolean).length,
  };
}

function ServerLogsPanel({
  open,
  text,
  loading,
  error,
  updatedAt,
  onRefresh,
  onClose,
}: {
  open: boolean;
  text: string;
  loading: boolean;
  error: string;
  updatedAt: string;
  onRefresh: () => void;
  onClose: () => void;
}) {
  if (!open) return null;
  return (
    <div className="absolute inset-0 z-40 flex items-center justify-center bg-black/25 backdrop-blur-[1px]">
      <div className="flex h-[min(78vh,560px)] w-[min(84vw,900px)] flex-col overflow-hidden rounded border border-[#cfd9e4] bg-[#fff] shadow-2xl">
        <div className="flex items-center justify-between border-b border-[#d8d8d8] bg-[#f0f4fb] px-4 py-2">
          <div className="text-[12px] font-semibold tracking-[0.12em] text-[#3f7fd3]">SERVER LOGS</div>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={onRefresh}
              className="rounded border border-[#8f8f8f] bg-white px-2 py-0.5 text-[12px] font-semibold text-[#333] transition hover:bg-[#f0f7ff] disabled:cursor-not-allowed disabled:opacity-60"
              disabled={loading}
            >
              {loading ? 'Loading...' : 'Refresh'}
            </button>
            <button
              type="button"
              onClick={onClose}
              className="rounded border border-[#8f8f8f] bg-white px-2 py-0.5 text-[12px] text-[#333] transition hover:bg-[#f0f7ff]"
            >
              Close
            </button>
          </div>
        </div>
        {updatedAt ? <div className="px-3 py-1.5 text-[12px] text-[#666]">updated {updatedAt}</div> : null}
        <div className="min-h-0 flex-1 overflow-auto px-3 py-2 text-[12px] text-[#2e2e2e]">
          {error ? <div className="mb-2 text-[12px] text-[#d85a47]">{error}</div> : null}
          <pre className="whitespace-pre-wrap break-words font-mono text-[12px] leading-5 text-[#2e2e2e]">{text || 'No server logs yet.'}</pre>
        </div>
      </div>
    </div>
  );
}

function AndroidLogcatPanel({
  open,
  platform,
  text,
  rawLineCount,
  shownLineCount,
  loading,
  error,
  updatedAt,
  count,
  buffer,
  tag,
  level,
  search,
  onCountChange,
  onBufferChange,
  onTagChange,
  onLevelChange,
  onSearchChange,
  onRefresh,
  onProcAudit,
  onClose,
}: {
  open: boolean;
  platform: DevicePlatform;
  text: string;
  rawLineCount: number;
  shownLineCount: number;
  loading: boolean;
  error: string;
  updatedAt: string;
  count: number;
  buffer: string;
  tag: string;
  level: string;
  search: string;
  onCountChange: (value: number) => void;
  onBufferChange: (value: string) => void;
  onTagChange: (value: string) => void;
  onLevelChange: (value: string) => void;
  onSearchChange: (value: string) => void;
  onRefresh: () => void;
  onProcAudit: () => void;
  onClose: () => void;
}) {
  if (!open) return null;
  const disabled = platform !== 'android' || loading;
  const hasSearch = search.trim().length > 0;
  return (
    <div className="absolute inset-0 z-40 flex items-center justify-center bg-black/25 backdrop-blur-[1px]">
      <div className="flex h-[min(86vh,760px)] w-[min(88vw,1040px)] flex-col overflow-hidden rounded border border-[#cfd9e4] bg-[#fff] shadow-2xl">
        <div className="flex items-center justify-between border-b border-[#d8d8d8] bg-[#f0f4fb] px-4 py-2">
          <div className="font-mono text-[12px] font-semibold tracking-[0.12em] text-[#3f7fd3]">
            ANDROID LOGCAT
            {updatedAt ? <span className="ml-2 font-normal tracking-normal text-[#777]">updated {updatedAt}</span> : null}
          </div>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={onRefresh}
              className="rounded border border-[#8f8f8f] bg-white px-2 py-0.5 text-[12px] font-semibold text-[#333] transition hover:bg-[#f0f7ff] disabled:cursor-not-allowed disabled:opacity-60"
              disabled={disabled}
            >
              {loading ? 'Loading...' : 'Refresh'}
            </button>
            <button
              type="button"
              onClick={onProcAudit}
              className="rounded border border-[#8f8f8f] bg-white px-2 py-0.5 text-[12px] font-semibold text-[#333] transition hover:bg-[#f0f7ff] disabled:cursor-not-allowed disabled:opacity-60"
              disabled={disabled}
              title="在 App 内扫描 /proc 并读取 App 可见 proc audit 日志"
            >
              Proc audit
            </button>
            <button
              type="button"
              onClick={onClose}
              className="rounded border border-[#8f8f8f] bg-white px-2 py-0.5 text-[12px] text-[#333] transition hover:bg-[#f0f7ff]"
            >
              Close
            </button>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2 border-b border-[#ececec] bg-[#fafafa] px-3 py-2 font-mono text-[11px] text-[#555]">
          <label className="flex items-center gap-1">
            keep
            <input
              type="number"
              min={1}
              max={5000}
              value={count}
              onChange={(event) => onCountChange(clampPaneSize(Number(event.target.value) || 1, 1, 5000))}
              className="h-6 w-20 rounded border border-[#bdbdbd] bg-white px-1 text-[#222]"
              disabled={loading}
            />
          </label>
          <label className="flex items-center gap-1">
            buffer
            <select
              value={buffer}
              onChange={(event) => onBufferChange(event.target.value)}
              className="h-6 rounded border border-[#bdbdbd] bg-white px-1 text-[#222]"
              disabled={loading}
            >
              <option value="all">all</option>
              <option value="main">main</option>
              <option value="events">events</option>
              <option value="system">system</option>
              <option value="crash">crash</option>
              <option value="radio">radio</option>
              <option value="kernel">kernel</option>
            </select>
          </label>
          <label className="flex items-center gap-1">
            level
            <select
              value={level}
              onChange={(event) => onLevelChange(event.target.value)}
              className="h-6 rounded border border-[#bdbdbd] bg-white px-1 text-[#222]"
              disabled={loading}
            >
              <option value="">all</option>
              <option value="V">V</option>
              <option value="D">D</option>
              <option value="I">I</option>
              <option value="W">W</option>
              <option value="E">E</option>
              <option value="F">F</option>
            </select>
          </label>
          <label className="flex items-center gap-1">
            tag
            <input
              type="text"
              value={tag}
              onChange={(event) => onTagChange(event.target.value)}
              placeholder="optional"
              className="h-6 w-32 rounded border border-[#bdbdbd] bg-white px-1 text-[#222]"
              disabled={loading}
            />
          </label>
          <label className="flex min-w-[240px] flex-1 items-center gap-1">
            search
            <input
              type="search"
              value={search}
              onChange={(event) => onSearchChange(event.target.value)}
              placeholder="keyword, pid, context, path..."
              className="h-6 min-w-0 flex-1 rounded border border-[#bdbdbd] bg-white px-2 text-[#222]"
            />
          </label>
          <div className="ml-auto whitespace-nowrap text-[#777]">
            {hasSearch ? `${shownLineCount}/${rawLineCount} matched` : `${rawLineCount}/5000 lines`}
          </div>
        </div>
        {platform !== 'android' ? (
          <div className="border-b border-[#ececec] px-3 py-2 font-mono text-[12px] text-[#777]">Android logcat is available only for Android devices.</div>
        ) : null}
        <div className="min-h-0 flex-1 overflow-auto px-3 py-2 text-[12px] text-[#2e2e2e]">
          {error ? <div className="mb-2 text-[12px] text-[#d85a47]">{error}</div> : null}
          <pre className="whitespace-pre-wrap break-words font-mono text-[12px] leading-5 text-[#2e2e2e]">{text || (hasSearch ? 'No matching logcat lines.' : 'Click Refresh to read Android logcat.')}</pre>
        </div>
      </div>
    </div>
  );
}
