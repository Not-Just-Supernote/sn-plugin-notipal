import { PluginCommAPI, PluginFileAPI, PluginNoteAPI } from 'sn-plugin-lib';
import { FileLogger } from './FileLogger';
import FloatingToolbarBridge from './FloatingToolbarBridge';
import { NativeModules } from 'react-native';

const { AIRelayModule } = NativeModules;

export interface SelectionPage {
  filePath: string;
  pageNum: number;
  width: number;
  height: number;
}
export type SelectionRect = { left: number; top: number; right: number; bottom: number };
export type PenLassoResult =
  | { status: 'selected'; page: SelectionPage; rect: SelectionRect }
  | { status: 'cancelled' | 'empty' | 'stale' | 'error' };

export async function readSelectionPage(): Promise<SelectionPage | null> {
  const [fp, pg]: any[] = await Promise.all([
    PluginCommAPI.getCurrentFilePath(), PluginCommAPI.getCurrentPageNum(),
  ]);
  if (!fp?.success || typeof fp.result !== 'string' || !fp.result ||
      !pg?.success || !Number.isInteger(pg.result)) return null;
  const size: any = await PluginFileAPI.getPageSize(fp.result, pg.result);
  const width = size?.result?.width, height = size?.result?.height;
  if (!size?.success || !Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return null;
  return { filePath: fp.result, pageNum: pg.result, width, height };
}

export async function isSameSelectionPage(expected: SelectionPage): Promise<boolean> {
  const current = await readSelectionPage();
  return !!current && current.filePath === expected.filePath && current.pageNum === expected.pageNum &&
    current.width === expected.width && current.height === expected.height;
}

export function validSelectionRect(rect: any): rect is SelectionRect {
  return !!rect && [rect.left, rect.top, rect.right, rect.bottom].every(Number.isFinite) &&
    rect.right > rect.left && rect.bottom > rect.top;
}


export async function hasSelectedContent(): Promise<boolean> {
  const rect: any = await PluginCommAPI.getLassoRect();
  if (!rect?.success || !validSelectionRect(rect.result)) return false;
  const elements: any = await PluginCommAPI.getLassoElements();
  if (elements?.success && Array.isArray(elements.result)) {
    const selected = elements.result.length > 0;
    for (const el of elements.result) { try { el?.recycle?.(); } catch (_) {} }
    if (selected) return true;
  }
  
  try {
    const links: any = await (PluginNoteAPI as any).getLassoLinks?.();
    return links?.success === true && Array.isArray(links.result) && links.result.length > 0;
  } catch (_) { return false; }
}

let sequence = 0;
let current: { id: string; promise: Promise<PenLassoResult>; cancel(): void } | null = null;
const BBOX_PADDING_PX = 100;

export const PenLasso = {
  arm(options: { page?: SelectionPage } = {}): Promise<PenLassoResult> {
    if (current) return current.promise;
    const id = `selection-${Date.now()}-${++sequence}`;
    let settle!: (result: PenLassoResult) => void;
    const promise = new Promise<PenLassoResult>(resolve => { settle = resolve; });
    let settled = false, processing = false;
    let bboxSub: { remove(): void } | null = null;
    let cancelSub: { remove(): void } | null = null;
    const alive = () => !settled && current?.id === id;
    const finish = (result: PenLassoResult) => {
      if (settled) return;
      settled = true;
      bboxSub?.remove(); cancelSub?.remove();
      if (result.status !== 'selected') FloatingToolbarBridge.cancelPenLassoSelection(id);
      if (current?.id === id) current = null;
      FloatingToolbarBridge.restoreToolbar();
      settle(result);
    };
    current = { id, promise, cancel: () => finish({ status: 'cancelled' }) };
    (async () => {
      const page = options.page ?? await readSelectionPage();
      if (!alive()) return;
      if (!page) return finish({ status: 'error' });
      bboxSub = FloatingToolbarBridge.onPenLassoBbox(event => {
        if (!alive() || processing || event.requestId !== id) return;
        processing = true;
        (async () => {
          if (!await isSameSelectionPage(page)) return finish({ status: 'stale' });
          if (!alive()) return;
          if (!validSelectionRect(event)) return finish({ status: 'empty' });
          const rect = {
            left: Math.max(0, Math.floor(event.left) - BBOX_PADDING_PX),
            top: Math.max(0, Math.floor(event.top) - BBOX_PADDING_PX),
            right: Math.min(page.width, Math.ceil(event.right) + BBOX_PADDING_PX),
            bottom: Math.min(page.height, Math.ceil(event.bottom) + BBOX_PADDING_PX),
          };
          if (!validSelectionRect(rect)) return finish({ status: 'empty' });
          FileLogger.logEvent('PenLasso', `request=${id} create rect=${JSON.stringify(rect)}`);
          
          
          try {
            const synced = await AIRelayModule?.prepareLivePageForSelection?.(
              page.filePath, page.pageNum, page.width, page.height);
            FileLogger.logEvent('PenLasso', `request=${id} live-page-sync=${synced === true}`);
          } catch (e) {
            FileLogger.logEvent('PenLasso', `request=${id} live-page-sync-error=${String(e)}`);
          }
          if (!alive() || !await isSameSelectionPage(page)) return finish({ status: 'stale' });
          const selected: any = await PluginCommAPI.lassoElements(rect);
          if (!alive()) return;
          if (!selected?.success || selected.result === false) return finish({ status: 'empty' });
          if (!await isSameSelectionPage(page)) return finish({ status: 'stale' });
          if (!alive()) return;
          const shown: any = await (PluginCommAPI as any).setLassoBoxState(0);
          if (!alive()) return;
          if (shown?.success === false) return finish({ status: 'error' });
          const hasContent = await hasSelectedContent();
          if (!alive()) return;
          if (!hasContent) return finish({ status: 'empty' });
          if (!await isSameSelectionPage(page)) return finish({ status: 'stale' });
          if (!alive()) return;
          
          const completed = await FloatingToolbarBridge.finishPenLassoSelection(id);
          if (!alive()) return;
          if (!completed) return finish({ status: 'cancelled' });
          FileLogger.logEvent('PenLasso', `request=${id} selection ready`);
          finish({ status: 'selected', page, rect });
        })().catch(e => { FileLogger.logEvent('PenLasso', String(e)); finish({ status: 'error' }); });
      });
      cancelSub = FloatingToolbarBridge.onPenLassoCancel(event => {
        if (event?.requestId === id) finish({ status: 'cancelled' });
      });
      FloatingToolbarBridge.beginPenLassoSelection(id);
    })().catch(e => { FileLogger.logEvent('PenLasso', String(e)); finish({ status: 'error' }); });
    return promise;
  },
  cancel(): void { current?.cancel(); },
  disarm(): void { PenLasso.cancel(); },
};
