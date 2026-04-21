export type DeviceState = 'idle' | 'opening' | 'active' | 'offline' | 'unknown' | string;

export type OutlineRect = {
  x?: number;
  y?: number;
  left?: number;
  top?: number;
  right?: number;
  bottom?: number;
  width?: number;
  height?: number;
  visible?: OutlineRect | null;
};

export type OutlineNode = {
  id?: string;
  path?: string;
  role?: string;
  simpleClass?: string;
  label?: string;
  text?: string;
  className?: string;
  resourceId?: string;
  resourceName?: string;
  contentDescription?: string;
  clickable?: boolean;
  focused?: boolean;
  selected?: boolean;
  alpha?: number;
  enabled?: boolean;
  visible?: boolean;
  depth?: number;
  bounds?: OutlineRect | string | null;
  visibleBounds?: OutlineRect | string | null;
  rect?: OutlineRect | string;
  frame?: OutlineRect | string;
  x?: number;
  y?: number;
  width?: number;
  height?: number;
  children?: OutlineNode[];
};

export type Outline = {
  available?: boolean;
  reason?: string;
  schema?: string;
  stale?: boolean;
  staleReason?: string;
  nodeCount?: number;
  packageName?: string;
  activityName?: string;
  screen?: OutlineRect & { density?: number };
  rootBounds?: OutlineRect;
  width?: number;
  height?: number;
  root?: OutlineNode;
  nodes?: OutlineNode[];
  capturedAt?: string | number;
};

export type MiraDevice = {
  type?: string;
  protocol?: number;
  installId: string;
  deviceName?: string;
  packageName?: string;
  androidIdHash?: string;
  model?: string;
  sdk?: number;
  arch?: string;
  state?: DeviceState;
  transport?: string;
  address?: string;
  wakeUrl?: string;
  outline?: Outline | null;
  outlineLastSeen?: number;
};

export type DevicesResponse = {
  devices: MiraDevice[];
};

export type OpenSessionResponse = {
  sessionId: string;
};

export type SessionStatus = 'idle' | 'opening' | 'active' | 'waiting for device' | 'device disconnected' | 'closed' | string;
