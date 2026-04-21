import type { MiraDevice } from '@/lib/types';
import { deviceTitle, shortId } from '@/lib/format';

export function BackgroundGlow() {
  return null;
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
    <header className="flex h-9 shrink-0 items-center justify-between border-b border-[#d0d0d0] bg-[#efefef] px-3 font-mono text-[12px] text-[#222]">
      <div className="flex min-w-0 items-center gap-3">
        <span className="truncate text-[#555]">{relayUrl || 'relay origin'}</span>
        <button type="button" onClick={onCopyRelay} className="border border-[#bdbdbd] bg-[#f8f8f8] px-2 py-0.5 text-[#333] hover:bg-white">
          {copied ? 'copied' : 'copy'}
        </button>
      </div>
      <div className="flex items-center gap-3 text-[#444]">
        <span>{error ? 'relay error' : loading ? 'syncing' : `${onlineCount}/${devices.length} online`}</span>
        <button type="button" onClick={onRefresh} className="border border-[#bdbdbd] bg-[#f8f8f8] px-2 py-0.5 text-[#333] hover:bg-white">
          refresh
        </button>
      </div>
    </header>
  );
}

export function Lobby({ devices, onSelect }: { devices: MiraDevice[]; onSelect: (device: MiraDevice) => void }) {
  return (
    <section className="min-h-0 flex-1 overflow-auto bg-[#f5f5f5] p-3 text-[#111]">
      <div className="w-fit min-w-[540px] max-w-full border border-[#cfcfcf] bg-white">
        <div className="border-b border-[#cfcfcf] px-2 py-1 font-mono text-[10px] font-semibold leading-3">Devices</div>
        {devices.length ? (
          <div className="divide-y divide-[#dedede]">
            {devices.map((device) => (
              <button
                key={device.installId}
                type="button"
                onClick={() => onSelect(device)}
                className="grid w-full grid-cols-[130px_88px_240px_54px] gap-2 px-2 py-0.5 text-left font-mono text-[9px] leading-3 hover:bg-[#f3f7ff]"
              >
                <span className="truncate font-semibold">{deviceTitle(device)}</span>
                <span className="truncate text-[#555]">{shortId(device.installId)}</span>
                <span className="truncate text-[#555]">{device.packageName || device.address || 'unknown'}</span>
                <span className="text-right">{device.state || 'unknown'}</span>
              </button>
            ))}
          </div>
        ) : (
          <div className="px-2 py-6 font-mono text-[9px] leading-3 text-[#666]">No device connected.</div>
        )}
      </div>
    </section>
  );
}
