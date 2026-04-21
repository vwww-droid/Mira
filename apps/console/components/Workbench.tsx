import clsx from 'clsx';
import { Braces, ChevronLeft, Cpu, Database, Gauge, Radio, Search } from 'lucide-react';
import { DeviceCard } from '@/components/DeviceCard';
import { DeviceFrame } from '@/components/DeviceFrame';
import { ConsoleEvent, TerminalStage } from '@/components/TerminalStage';
import { deviceTitle, stateTone } from '@/lib/format';
import type { MiraDevice } from '@/lib/types';

export function Workbench({
  devices,
  selectedDevice,
  events,
  onSelect,
  onBack,
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
    <section className="min-h-0 flex-1 overflow-hidden rounded-[2rem] border border-white/10 bg-slate-950/42 p-3 shadow-[0_24px_120px_rgba(0,0,0,0.32)] backdrop-blur-2xl lg:p-4">
      <div className="grid h-full min-h-0 grid-cols-1 gap-3 xl:grid-cols-[360px_minmax(0,1fr)_340px]">
        <DeviceFrame device={selectedDevice} />
        <div className="min-h-[420px] overflow-hidden rounded-[1.75rem] border border-white/10 bg-[#030712] shadow-[0_30px_120px_rgba(0,0,0,0.42)] xl:min-h-0">
          <TerminalStage device={selectedDevice} onEvent={onEvent} onRefreshDevices={onRefreshDevices} />
        </div>
        <InspectorRail device={selectedDevice} devices={devices} events={events} onSelect={onSelect} onBack={onBack} />
      </div>
    </section>
  );
}

function InspectorRail({
  device,
  devices,
  events,
  onSelect,
  onBack,
}: {
  device: MiraDevice;
  devices: MiraDevice[];
  events: ConsoleEvent[];
  onSelect: (device: MiraDevice) => void;
  onBack: () => void;
}) {
  return (
    <aside className="mira-scrollbar flex h-full min-h-0 flex-col gap-3 overflow-auto rounded-[1.75rem] border border-white/10 bg-slate-950/38 p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="text-sm font-semibold uppercase tracking-[0.18em] text-slate-500">Inspector</div>
          <div className="mt-1 text-xl font-bold tracking-[-0.04em]">Device context</div>
        </div>
        <button
          type="button"
          onClick={onBack}
          className="inline-flex items-center gap-2 rounded-2xl border border-white/10 bg-white/[0.035] px-3 py-2 text-sm text-slate-300 transition hover:bg-white/[0.07]"
        >
          <ChevronLeft size={16} /> Lobby
        </button>
      </div>
      <InfoCard
        title="Identity"
        icon={Database}
        rows={[
          ['Install', device.installId],
          ['Package', device.packageName || 'unknown'],
          ['Android ID', device.androidIdHash || 'unknown'],
        ]}
      />
      <InfoCard
        title="Platform"
        icon={Cpu}
        rows={[
          ['Model', device.model || device.deviceName || 'unknown'],
          ['SDK', String(device.sdk || 'unknown')],
          ['ABI', device.arch || 'unknown'],
        ]}
      />
      <InfoCard
        title="Transport"
        icon={Radio}
        rows={[
          ['State', device.state || 'unknown'],
          ['Mode', device.transport || 'legacy'],
          ['Address', device.address || 'unknown'],
        ]}
      />
      <DeviceSwitcher devices={devices} selectedDevice={device} onSelect={onSelect} />
      <div className="rounded-[1.35rem] border border-white/10 bg-white/[0.035] p-4">
        <div className="mb-3 flex items-center justify-between">
          <div className="inline-flex items-center gap-2 text-sm font-semibold text-slate-200">
            <Gauge size={15} /> Protocol trace
          </div>
          <span className="font-mono text-[10px] text-slate-600">{events.length}</span>
        </div>
        <div className="space-y-2">
          {events.slice(0, 12).map((event, index) => (
            <div key={`${event.at}-${event.type}-${index}`} className="rounded-2xl bg-black/20 px-3 py-2">
              <div className="flex items-center justify-between gap-2">
                <span className="truncate font-mono text-[11px] text-emerald-300">{event.type}</span>
                <span className="font-mono text-[10px] text-slate-600">{event.at}</span>
              </div>
              <div className="mt-1 truncate text-xs text-slate-400">{event.detail}</div>
            </div>
          ))}
          {!events.length && <div className="rounded-2xl border border-dashed border-white/10 p-4 text-center text-xs text-slate-500">No protocol events yet.</div>}
        </div>
      </div>
    </aside>
  );
}

function DeviceSwitcher({ devices, selectedDevice, onSelect }: { devices: MiraDevice[]; selectedDevice: MiraDevice; onSelect: (device: MiraDevice) => void }) {
  return (
    <section className="rounded-[1.35rem] border border-white/10 bg-white/[0.035] p-4">
      <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-slate-200">
        <Search size={15} /> Devices
      </div>
      <div className="mira-scrollbar max-h-[360px] space-y-2 overflow-auto pr-1">
        {devices.map((candidate) => (
          <DeviceCard key={candidate.installId} device={candidate} selected={candidate.installId === selectedDevice.installId} onSelect={onSelect} />
        ))}
      </div>
    </section>
  );
}

function InfoCard({ title, icon: Icon, rows }: { title: string; icon: typeof Braces; rows: Array<[string, string]> }) {
  return (
    <section className="rounded-[1.35rem] border border-white/10 bg-white/[0.035] p-4">
      <div className="mb-3 inline-flex items-center gap-2 text-sm font-semibold text-slate-200">
        <Icon size={15} /> {title}
      </div>
      <div className="space-y-2">
        {rows.map(([key, value]) => (
          <div key={key} className="grid grid-cols-[72px_1fr] gap-3 text-xs">
            <div className="text-slate-600">{key}</div>
            <div className={clsx('truncate font-mono text-slate-300', stateTone(value) === 'good' && key === 'State' && 'text-emerald-300')}>
              {value}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
