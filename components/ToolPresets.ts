

import { NativeModules } from 'react-native';
import { t } from './i18n';

const { FloatingToolbar } = NativeModules;

const CLIP_STORE_KEY = 99;

const SNAPSHOT_STORE_KEY = 95;

export interface ClipData {
  [slot: string]: string | null;
}

export interface SnapshotRecord {
  id: string;
  sticker: string;
  thumb: string;
  note: string;
  page: number;
  ts: number;
}

export async function loadSnapshots(): Promise<SnapshotRecord[]> {
  try {
    const json = await FloatingToolbar?.loadPreset(SNAPSHOT_STORE_KEY);
    if (!json) return [];
    const data = JSON.parse(json);
    if (!Array.isArray(data)) return [];
    const records = data.filter((r: any) => r && typeof r.sticker === 'string');
    return records.slice(0, 3);
  } catch (e) {
    console.warn('[ToolPresets]: loadSnapshots:', e);
    return [];
  }
}

export async function saveSnapshots(list: SnapshotRecord[]): Promise<void> {
  try {
    await FloatingToolbar?.savePreset(SNAPSHOT_STORE_KEY, JSON.stringify(list));
  } catch (e) {
    console.warn('[ToolPresets]: saveSnapshots:', e);
  }
}

export type AiSelectionMode = 'text' | 'formula';

export interface BubbleAction {
  id: string;
  icon: string;
  label: string;
  action: string;
  primary?: boolean;
  selectedMode?: AiSelectionMode;
  modeOptions?: { id: AiSelectionMode; label: string }[];
}

const AI_BUBBLE_ACTION_DEFS = [
  { id: 'lasso_ai', icon: 'icons/ic_ai_selection.xml', action: 'lasso_ai', nameKey: 'tool_lasso_ai' },
  { id: 'screenshot_ai', icon: 'icons/ic_ai_image.xml', action: 'screenshot_ai', nameKey: 'tool_screenshot_ai' },
  { id: 'copilot', icon: 'icons/ic_ai_conversations.xml', action: 'open_copilot', nameKey: 'tool_ai_conversations' },
  { id: 'cancel_ai', icon: 'icons/ic_ai_close.xml', action: 'cancel_ai', nameKey: 'tool_cancel_ai' },
];
const DEFAULT_AI_BUBBLE_ACTIONS = ['lasso_ai', 'screenshot_ai', 'copilot'];

const AI_BUBBLE_ACTION_STORE_KEY = 93;
interface AiBubblePrefs { version: 2; enabledIds: string[]; selectionMode: AiSelectionMode }
let prefsPromise: Promise<AiBubblePrefs> | null = null;
let prefsWrite: Promise<void> = Promise.resolve();

export function normalizeAiBubbleActionIds(ids: unknown): string[] {
  if (!Array.isArray(ids)) return [...DEFAULT_AI_BUBBLE_ACTIONS];
  const known = new Set(AI_BUBBLE_ACTION_DEFS.map(d => d.id));
  return Array.from(new Set(ids.filter((id): id is string => typeof id === 'string' && known.has(id))));
}

function loadAiPrefs(): Promise<AiBubblePrefs> {
  if (!prefsPromise) prefsPromise = (async () => {
    let saved: any = null;
    try {
      const raw = await FloatingToolbar?.loadPreset(AI_BUBBLE_ACTION_STORE_KEY);
      saved = raw ? JSON.parse(raw) : null;
    } catch (_) {}
    return {
      version: 2 as const,
      enabledIds: normalizeAiBubbleActionIds(saved?.enabledIds),
      selectionMode: saved?.selectionMode === 'formula' ? 'formula' as const : 'text' as const,
    };
  })();
  return prefsPromise;
}

function updateAiPrefs(update: (prefs: AiBubblePrefs) => void): Promise<void> {
  prefsWrite = prefsWrite.then(async () => {
    const prefs = await loadAiPrefs();
    update(prefs);
    await FloatingToolbar?.savePreset(AI_BUBBLE_ACTION_STORE_KEY, JSON.stringify(prefs));
  }).catch(e => console.warn('[ToolPresets]: save AI preferences:', e));
  return prefsWrite;
}

export async function loadAiBubbleActions(): Promise<string[]> {
  return [...(await loadAiPrefs()).enabledIds];
}
export async function loadAiSelectionMode(): Promise<AiSelectionMode> {
  return (await loadAiPrefs()).selectionMode;
}
export function saveAiSelectionMode(mode: AiSelectionMode): Promise<void> {
  return updateAiPrefs(prefs => { prefs.selectionMode = mode; });
}
export function saveAiBubbleActions(enabledIds: string[]): Promise<void> {
  return updateAiPrefs(prefs => { prefs.enabledIds = normalizeAiBubbleActionIds(enabledIds); });
}

export function resolveAiBubbleActions(enabledIds: string[], mode: AiSelectionMode = 'text'): BubbleAction[] {
  return normalizeAiBubbleActionIds(enabledIds).map(id => {
    const def = AI_BUBBLE_ACTION_DEFS.find(d => d.id === id)!;
    const button: BubbleAction = { id: def.id, icon: def.icon, label: t(def.nameKey as any), action: def.action };
    if (id === 'lasso_ai') {
      button.selectedMode = mode;
      button.modeOptions = [
        { id: 'text', label: t('ai_mode_text') },
        { id: 'formula', label: t('ai_mode_formula') },
      ];
    }
    return button;
  });
}


export const CLIP_SLOT_COUNT = 8;

export async function loadClips(): Promise<ClipData> {
  const result: ClipData = {};
  try {
    const json = await FloatingToolbar?.loadPreset(CLIP_STORE_KEY);
    if (json) Object.assign(result, JSON.parse(json) as ClipData);
  } catch (e) {
    console.warn('[ToolPresets]: loadClips:', e);
  }
  for (let n = 1; n <= CLIP_SLOT_COUNT; n++) {
    if (!(String(n) in result)) result[String(n)] = null;
  }
  return result;
}


export function clipFilledFlags(clips: ClipData): boolean[] {
  return Array.from({ length: CLIP_SLOT_COUNT }, (_, i) => !!clips[String(i + 1)]);
}

export async function saveClips(clips: ClipData): Promise<void> {
  try {
    await FloatingToolbar?.savePreset(CLIP_STORE_KEY, JSON.stringify(clips));
  } catch (e) {
    console.warn('[ToolPresets]: saveClips:', e);
  }
}

