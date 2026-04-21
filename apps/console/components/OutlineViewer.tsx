'use client';

import clsx from 'clsx';
import { useEffect, useRef, useState } from 'react';
import type { Outline, OutlineNode, OutlineRect } from '@/lib/types';

type RenderRect = {
  x: number;
  y: number;
  width: number;
  height: number;
};

type Viewport = {
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
const OUTLINE_LABEL_SIZE = 11;

export function OutlineViewer({ outline, className }: { outline?: Outline | null; className?: string }) {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const [hostSize, setHostSize] = useState({ width: 0, height: 0 });
  const nodes = flattenOutline(outline);
  const viewport = getViewport(outline, nodes);
  const layout = computeMeetLayout(viewport, hostSize);
  const hasOutline = Boolean(outline && nodes.length > 0);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    const updateSize = () => {
      const rect = host.getBoundingClientRect();
      const width = Math.round(rect.width);
      const height = Math.round(rect.height);
      setHostSize((previous) => (previous.width === width && previous.height === height ? previous : { width, height }));
    };
    updateSize();
    const observer = new ResizeObserver(updateSize);
    observer.observe(host);
    return () => observer.disconnect();
  }, [hasOutline]);

  if (!hasOutline) {
    return (
      <div className={clsx('grid h-full min-h-[420px] place-items-center bg-[#111] p-6 text-center font-mono text-[#d8d8d8]', className)}>
        <div>
          <div className="mx-auto mb-4 h-16 w-10 border border-[#2fd0a6]" />
          <div className="text-[13px] font-semibold">No outline available</div>
          <div className="mt-2 max-w-[220px] text-[11px] leading-5 text-[#888]">设备暂未上报 UI 轮廓. 保持 Mira 前台连接后刷新设备状态.</div>
        </div>
      </div>
    );
  }

  return (
    <div ref={hostRef} className={clsx('relative h-full min-h-[420px] overflow-hidden bg-[#111]', className)}>
      <svg
        viewBox={`0 0 ${viewport.width} ${viewport.height}`}
        role="img"
        aria-label="Device outline"
        className="h-full w-full bg-[#111]"
        preserveAspectRatio="xMidYMid meet"
      >
        <rect x="0" y="0" width={viewport.width} height={viewport.height} fill="#111" />
        {nodes.map(({ key, node, rect, depth }) => {
          const important = isImportantNode(node);
          const stroke = important ? '#2fd0a6' : depth < 2 ? '#6b7280' : '#3f3f46';
          const fill = important ? 'rgba(47,208,166,0.08)' : 'rgba(255,255,255,0.018)';
          return (
            <g key={key}>
              <rect
                x={rect.x}
                y={rect.y}
                width={Math.max(rect.width, 1)}
                height={Math.max(rect.height, 1)}
                fill={fill}
                stroke={stroke}
                strokeWidth={important ? 2 : 1}
                vectorEffect="non-scaling-stroke"
                opacity={Math.max(0.22, 0.88 - depth * 0.08)}
              />
            </g>
          );
        })}
      </svg>
      {layout &&
        nodes.map(({ key, node, rect }, index) => {
          if (!isImportantNode(node)) return null;
          const renderedWidth = rect.width * layout.scale;
          const renderedHeight = rect.height * layout.scale;
          if (renderedWidth < 54 || renderedHeight < 14) return null;
          return (
            <div
              key={`${key}-label`}
              className="pointer-events-none absolute overflow-hidden text-ellipsis whitespace-nowrap font-mono text-[#d8fff3]"
              style={{
                left: layout.offsetX + rect.x * layout.scale + 4,
                top: layout.offsetY + rect.y * layout.scale + 3,
                maxWidth: Math.max(24, renderedWidth - 8),
                fontSize: OUTLINE_LABEL_SIZE,
                lineHeight: `${OUTLINE_LABEL_SIZE}px`,
              }}
            >
              {labelForNode(node, index)}
            </div>
          );
        })}
      <div className="pointer-events-none absolute bottom-2 left-2 bg-[#111] px-1 font-mono text-[11px] text-[#8a8a8a]">
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
    if (node.visible === false) return;
    const rect = readRect(node);
    if (rect && rect.width > 0 && rect.height > 0) result.push({ key: `${path}-${result.length}`, node, rect, depth });
    node.children?.forEach((child, index) => walk(child, depth + 1, `${path}.${index}`));
  };
  roots.forEach((node, index) => walk(node, node.depth ?? 0, node.path || String(index)));
  return result;
}

function getViewport(outline: Outline | undefined | null, nodes: NormalizedNode[]): Viewport {
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

function computeMeetLayout(viewport: Viewport, hostSize: Viewport) {
  if (viewport.width <= 0 || viewport.height <= 0 || hostSize.width <= 0 || hostSize.height <= 0) return null;
  const scale = Math.min(hostSize.width / viewport.width, hostSize.height / viewport.height);
  if (!Number.isFinite(scale) || scale <= 0) return null;
  return {
    scale,
    offsetX: (hostSize.width - viewport.width * scale) / 2,
    offsetY: (hostSize.height - viewport.height * scale) / 2,
  };
}

function readRect(node: OutlineNode): RenderRect | null {
  const candidate = node.visibleBounds || visibleBoundsFrom(node.bounds) || node.bounds || node.rect || node.frame;
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

function visibleBoundsFrom(value: OutlineNode['bounds']): OutlineRect | string | null | undefined {
  if (!value || typeof value !== 'object') return null;
  return value.visible && typeof value.visible === 'object' ? value.visible : null;
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
  return Boolean(node.role === 'button' || node.role === 'input' || node.text || node.label || node.resourceId || node.resourceName || node.contentDescription || node.clickable);
}

function labelForNode(node: OutlineNode, index: number): string {
  return String(node.text || node.label || node.resourceName || node.contentDescription || node.simpleClass || node.className || `node-${index}`).slice(0, 28);
}
