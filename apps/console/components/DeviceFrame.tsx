import { Smartphone } from 'lucide-react';
import type { MiraDevice } from '@/lib/types';
import { deviceTitle, shortId } from '@/lib/format';
import { StatusPill } from './StatusPill';
import { OutlineViewer } from './OutlineViewer';

export function DeviceFrame({ device }: { device: MiraDevice }) {
  return (
    <section className="flex h-full min-h-0 flex-col overflow-hidden rounded-[1.75rem] border border-white/10 bg-slate-950/48 p-4">
      <header className="mb-4 flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <div className="grid h-11 w-11 shrink-0 place-items-center rounded-2xl border border-emerald-300/20 bg-emerald-300/10 text-emerald-300">
            <Smartphone size={20} />
          </div>
          <div className="min-w-0">
            <div className="truncate text-base font-bold tracking-[-0.04em] text-slate-100">{deviceTitle(device)}</div>
            <div className="mt-1 font-mono text-[11px] text-slate-500">{shortId(device.installId, 12)}</div>
          </div>
        </div>
        <StatusPill state={device.state || 'unknown'} />
      </header>
      <div className="min-h-0 flex-1 rounded-[2.4rem] border border-white/10 bg-black/30 p-3 shadow-[inset_0_0_0_1px_rgba(255,255,255,0.04),0_24px_80px_rgba(0,0,0,0.35)]">
        <OutlineViewer outline={device.outline} />
      </div>
    </section>
  );
}
