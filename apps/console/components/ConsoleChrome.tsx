import { Check, Clipboard, Globe2, Layers3, RefreshCw } from 'lucide-react';
import { DeviceCard } from '@/components/DeviceCard';
import { StatusPill } from '@/components/StatusPill';
import type { MiraDevice } from '@/lib/types';

export function BackgroundGlow() {
  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden">
      <div className="absolute -left-32 top-[-18rem] h-[32rem] w-[32rem] rounded-full bg-emerald-500/12 blur-3xl" />
      <div className="absolute right-[-10rem] top-24 h-[30rem] w-[30rem] rounded-full bg-cyan-500/8 blur-3xl" />
      <div className="absolute bottom-[-16rem] left-1/2 h-[30rem] w-[42rem] -translate-x-1/2 rounded-full bg-indigo-500/10 blur-3xl" />
      <div className="absolute inset-0 bg-[linear-gradient(rgba(148,163,184,0.03)_1px,transparent_1px),linear-gradient(90deg,rgba(148,163,184,0.03)_1px,transparent_1px)] bg-[size:48px_48px]" />
    </div>
  );
}

export function TopBar({
  relayUrl,
  devices,
  onlineCount,
  loading,
  error,
  copied,
  onCopyRelay,
  onRefresh,
}: {
  relayUrl: string;
  devices: MiraDevice[];
  onlineCount: number;
  loading: boolean;
  error: string | null;
  copied: boolean;
  onCopyRelay: () => void;
  onRefresh: () => void;
}) {
  return (
    <header className="mb-4 flex min-h-0 items-center justify-between gap-4 rounded-[1.75rem] border border-white/10 bg-slate-950/68 px-4 py-3 shadow-[0_18px_80px_rgba(0,0,0,0.25)] backdrop-blur-2xl lg:px-5">
      <div className="flex min-w-0 items-center gap-4">
        <div className="flex items-center gap-3">
          <div className="grid h-10 w-10 place-items-center rounded-2xl border border-emerald-300/20 bg-emerald-300/10 text-emerald-300 shadow-[0_0_36px_rgba(52,211,153,0.16)]">
            <Layers3 size={19} />
          </div>
          <div>
            <div className="flex items-baseline gap-2">
              <span className="text-lg font-black tracking-[-0.05em]">Mira</span>
              <span className="text-[11px] font-semibold uppercase tracking-[0.24em] text-slate-500">console</span>
            </div>
            <div className="text-[11px] text-slate-500">Android device workbench</div>
          </div>
        </div>
        <div className="hidden min-w-0 items-center gap-3 rounded-2xl border border-white/10 bg-white/[0.035] px-3 py-2 font-mono text-[11px] text-slate-400 xl:flex">
          <Globe2 size={14} className="shrink-0 text-emerald-300" />
          <span className="max-w-[360px] truncate">{relayUrl || 'relay origin'}</span>
          <button type="button" onClick={onCopyRelay} className="rounded-xl px-2 py-1 text-slate-200 transition hover:bg-white/10">
            {copied ? <Check size={14} /> : <Clipboard size={14} />}
          </button>
        </div>
      </div>
      <div className="flex items-center gap-2 lg:gap-3">
        {error ? (
          <StatusPill state="offline" label="relay error" />
        ) : (
          <StatusPill state={onlineCount > 0 ? 'active' : 'opening'} label={loading ? 'syncing' : `${onlineCount}/${devices.length} online`} />
        )}
        <button
          type="button"
          onClick={onRefresh}
          className="grid h-10 w-10 place-items-center rounded-2xl border border-white/10 bg-white/[0.035] text-slate-300 transition hover:bg-white/[0.07]"
          title="Refresh device list"
        >
          <RefreshCw size={16} />
        </button>
      </div>
    </header>
  );
}

export function Lobby({ devices, onSelect }: { devices: MiraDevice[]; onSelect: (device: MiraDevice) => void }) {
  return (
    <section className="min-h-0 flex-1 overflow-hidden rounded-[2rem] border border-white/10 bg-slate-950/45 p-5 shadow-[0_24px_120px_rgba(0,0,0,0.32)] backdrop-blur-2xl lg:p-6">
      <div className="mira-scrollbar grid h-full min-h-0 grid-cols-1 content-start gap-3 overflow-auto pr-1 xl:grid-cols-2 2xl:grid-cols-3">
        {devices.map((device) => (
          <DeviceCard key={device.installId} device={device} onSelect={onSelect} />
        ))}
      </div>
    </section>
  );
}
