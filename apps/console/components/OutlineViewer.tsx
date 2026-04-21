import clsx from 'clsx';
import type { Outline, OutlineNode, OutlineRect } from '@/lib/types';

type RenderRect = {
  x: number;
  y: number;
  width: number;
  height: number;
};

type NormalizedNode = {
  key: string;
  node: OutlineNode;
  rect: RenderRect;
  depth: number;
};

const MIN_VIEWPORT = { width: 360, height: 720 };

export function OutlineViewer({ outline, className }: { outline?: Outline | null; className?: string }) {
  const nodes = flattenOutline(outline);
  const viewport = getViewport(outline, nodes);

  if (!outline || nodes.length === 0) {
    return (
      <div className={clsx('grid h-full min-h-[420px] place-items-center rounded-[2rem] border border-dashed border-white/10 bg-slate-950/45 p-8 text-center', className)}>
        <div>
          <div className="mx-auto mb-4 h-16 w-10 rounded-[1.2rem] border border-emerald-300/30 bg-emerald-300/10 shadow-[0_0_40px_rgba(52,211,153,0.12)]" />
          <div className="text-sm font-semibold text-slate-200">No outline available</div>
          <div className="mt-2 max-w-[220px] text-xs leading-5 text-slate-500">设备暂未上报 UI 轮廓. 保持 Mira 前台连接后刷新设备状态.</div>
        </div>
      </div>
    );
  }

  return (
    <div className={clsx('relative h-full min-h-[420px] overflow-hidden rounded-[2rem] border border-white/10 bg-[#050816]', className)}>
      <svg
        viewBox={`0 0 ${viewport.width} ${viewport.height}`}
        role="img"
        aria-label="Device outline"
        className="h-full w-full bg-[radial-gradient(circle_at_50%_0%,rgba(52,211,153,0.12),transparent_38%),linear-gradient(180deg,rgba(15,23,42,0.88),rgba(2,6,23,0.92))]"
        preserveAspectRatio="xMidYMid meet"
      >
        <rect x="0" y="0" width={viewport.width} height={viewport.height} rx="28" fill="rgba(15,23,42,0.35)" />
        {nodes.map(({ key, node, rect, depth }, index) => {
          const important = isImportantNode(node);
          const stroke = important ? 'rgba(52,211,153,0.9)' : depth < 2 ? 'rgba(125,211,252,0.58)' : 'rgba(148,163,184,0.32)';
          const fill = important ? 'rgba(52,211,153,0.08)' : depth < 2 ? 'rgba(14,165,233,0.045)' : 'rgba(148,163,184,0.025)';
          return (
            <g key={key}>
              <rect
                x={rect.x}
                y={rect.y}
                width={Math.max(rect.width, 1)}
                height={Math.max(rect.height, 1)}
                rx={Math.min(12, Math.max(2, Math.min(rect.width, rect.height) / 8))}
                fill={fill}
                stroke={stroke}
                strokeWidth={important ? 2.2 : 1.2}
                vectorEffect="non-scaling-stroke"
                opacity={Math.max(0.24, 0.9 - depth * 0.08)}
              />
              {important && rect.width > 72 && rect.height > 24 && (
                <text x={rect.x + 8} y={rect.y + 17} fill="rgba(209,250,229,0.95)" fontSize="12" fontFamily="ui-monospace, SFMono-Regular, Menlo, monospace">
                  {labelForNode(node, index)}
                </text>
              )}
            </g>
          );
        })}
      </svg>
      <div className="pointer-events-none absolute bottom-3 left-3 rounded-2xl border border-white/10 bg-slate-950/75 px-3 py-2 font-mono text-[10px] text-slate-400 backdrop-blur">
        {nodes.length} nodes · {Math.round(viewport.width)} x {Math.round(viewport.height)}
      </div>
    </div>
  );
}

function flattenOutline(outline?: Outline | null): NormalizedNode[] {
  if (!outline) return [];
  const roots = outline.nodes?.length ? outline.nodes : outline.root ? [outline.root] : [];
  const result: NormalizedNode[] = [];
  const walk = (node: OutlineNode, depth: number, path: string) => {
    const rect = readRect(node);
    if (rect && rect.width > 0 && rect.height > 0) result.push({ key: `${path}-${result.length}`, node, rect, depth });
    node.children?.forEach((child, index) => walk(child, depth + 1, `${path}.${index}`));
  };
  roots.forEach((node, index) => walk(node, 0, String(index)));
  return result;
}

function getViewport(outline: Outline | undefined | null, nodes: NormalizedNode[]) {
  const maxRight = Math.max(
    ...nodes.map(({ rect }) => rect.x + rect.width),
    outline?.screen?.width || outline?.width || outline?.rootBounds?.right || 0,
    MIN_VIEWPORT.width,
  );
  const maxBottom = Math.max(
    ...nodes.map(({ rect }) => rect.y + rect.height),
    outline?.screen?.height || outline?.height || outline?.rootBounds?.bottom || 0,
    MIN_VIEWPORT.height,
  );
  return { width: maxRight, height: maxBottom };
}

function readRect(node: OutlineNode): RenderRect | null {
  const candidate = node.bounds || node.rect || node.frame;
  if (typeof candidate === 'string') return parseAndroidBounds(candidate);
  if (candidate && typeof candidate === 'object') {
    const left = numberValue(candidate.x ?? candidate.left);
    const top = numberValue(candidate.y ?? candidate.top);
    const right = numberValue(candidate.right);
    const bottom = numberValue(candidate.bottom);
    const width = numberValue(candidate.width);
    const height = numberValue(candidate.height);
    if (width !== null && height !== null) return { x: left ?? 0, y: top ?? 0, width, height };
    if (left !== null && top !== null && right !== null && bottom !== null) return { x: left, y: top, width: right - left, height: bottom - top };
  }
  const x = numberValue(node.x);
  const y = numberValue(node.y);
  const width = numberValue(node.width);
  const height = numberValue(node.height);
  if (x !== null && y !== null && width !== null && height !== null) return { x, y, width, height };
  return null;
}

function parseAndroidBounds(value: string): RenderRect | null {
  const match = value.match(/\[(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)\]\[(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)\]/);
  if (!match) return null;
  const [, left, top, right, bottom] = match.map(Number);
  return { x: left, y: top, width: right - left, height: bottom - top };
}

function numberValue(value: unknown): number | null {
  const number = typeof value === 'number' ? value : typeof value === 'string' ? Number(value) : Number.NaN;
  return Number.isFinite(number) ? number : null;
}

function isImportantNode(node: OutlineNode): boolean {
  return Boolean(node.text || node.label || node.resourceId || node.resourceName || node.contentDescription || node.clickable);
}

function labelForNode(node: OutlineNode, index: number): string {
  return String(node.text || node.label || node.resourceId || node.resourceName || node.contentDescription || node.className || `node-${index}`).slice(0, 28);
}
