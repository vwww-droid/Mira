import clsx from 'clsx';
import { stateTone } from '@/lib/format';

export function StatusPill({ state, label }: { state?: string; label?: string }) {
  const tone = stateTone(state);
  return (
    <span
      className={clsx(
        'inline-flex items-center gap-2 rounded-full border px-2.5 py-1 text-[11px] font-semibold uppercase tracking-[0.18em]',
        tone === 'good' && 'border-emerald-400/30 bg-emerald-400/10 text-emerald-300',
        tone === 'warn' && 'border-amber-300/30 bg-amber-300/10 text-amber-200',
        tone === 'bad' && 'border-red-400/30 bg-red-400/10 text-red-300',
        tone === 'muted' && 'border-slate-500/30 bg-slate-500/10 text-slate-300',
      )}
    >
      <span className="relative flex h-2 w-2">
        <span
          className={clsx(
            'relative inline-flex h-2 w-2 rounded-full',
            tone === 'good' && 'bg-emerald-300',
            tone === 'warn' && 'bg-amber-300',
            tone === 'bad' && 'bg-red-300',
            tone === 'muted' && 'bg-slate-400',
          )}
        />
      </span>
      {label || state || 'unknown'}
    </span>
  );
}
