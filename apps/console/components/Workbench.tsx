import { DeviceFrame } from '@/components/DeviceFrame';
import { ConsoleEvent, TerminalStage } from '@/components/TerminalStage';
import { deviceTitle, shortId } from '@/lib/format';
import type { MiraDevice } from '@/lib/types';

export function Workbench({
  selectedDevice,
  onEvent,
  onRefreshDevices,
}: {
  devices: MiraDevice[];
  selectedDevice: MiraDevice;
  events: ConsoleEvent[];
  onSelect: (device: MiraDevice) => void;
  onBack: () => void;
  onEvent: (event: ConsoleEvent) => void;
  onRefreshDevices: () => void;
}) {
  return (
    <section className="grid min-h-0 flex-1 grid-cols-[286px_minmax(0,1fr)] overflow-hidden bg-[#f5f5f5] text-[#111]">
      <DeviceFrame device={selectedDevice} />
      <div className="grid min-h-0 grid-rows-[minmax(0,1fr)_168px]">
        <TerminalStage device={selectedDevice} onEvent={onEvent} onRefreshDevices={onRefreshDevices} />
        <InfoPanel device={selectedDevice} />
      </div>
    </section>
  );
}

function InfoPanel({ device }: { device: MiraDevice }) {
  const screen = device.outline?.screen;
  const screenText = screen?.width && screen?.height ? `${screen.width} x ${screen.height}` : 'unknown';
  const rows: Array<[string, string]> = [
    ['Architecture', device.arch || 'unknown'],
    ['Model', device.model || device.deviceName || 'unknown'],
    ['Device ID', shortId(device.installId, 36)],
    ['Screen', screenText],
    ['Components', String(device.outline?.nodeCount ?? device.outline?.nodes?.length ?? 'unknown')],
    ['Outline', device.outline?.stale ? `stale: ${device.outline.staleReason || 'cached'}` : device.outline?.available === false ? device.outline.reason || 'unavailable' : 'live'],
    ['Proxy', 'none'],
    ['Cur Application', device.packageName || 'unknown'],
    ['Cur Activity', device.outline?.activityName || 'unknown'],
    ['Status', device.state || 'unknown'],
  ];

  return (
    <section className="border-t border-[#cfcfcf] bg-[#f5f5f5] text-[#111]">
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
    </section>
  );
}
