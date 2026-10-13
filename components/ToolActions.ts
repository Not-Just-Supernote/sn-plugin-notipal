import {
  PluginCommAPI, PluginFileAPI, PluginNoteAPI, PluginManager, NativeUIUtils, FileUtils, PointUtils,
} from 'sn-plugin-lib';
import { NativeModules, EmitterSubscription } from 'react-native';
import { loadClips, saveClips, loadSnapshots, saveSnapshots } from './ToolPresets';
import { t } from './i18n';
import { toggleMode, handleAiSend, getLastMode, stopAiMode } from './BackgroundService';
import { getRNFS } from './rnfs';
import FloatingToolbarBridge from './FloatingToolbarBridge';
import FloatingBubbleBridge from './BubbleBridges';
import { layerIdOf, saveCurrentPage } from './ElementOps';
import { reselectLasso } from './LassoTxn';
import { TOOL_IDS } from './ToolIdentifiers';

const { FloatingToolbar } = NativeModules;

let _filePath: string | null = null;
let _pageNum: number | null = null;


let _clipBusy = false;

const STICKER_DIR = '/sdcard/MyStyle/Sticker';

async function ensureStickerDir(): Promise<void> {
  const RNFS = getRNFS();
  if (!RNFS) return;
  try {
    const parentExists = await RNFS.exists('/sdcard/MyStyle');
    if (!parentExists) {
      await RNFS.mkdir('/sdcard/MyStyle');
      console.log('[CLIP-DBG] created /sdcard/MyStyle');
    }
    const exists = await RNFS.exists(STICKER_DIR);
    if (!exists) {
      await RNFS.mkdir(STICKER_DIR);
      console.log('[CLIP-DBG] created', STICKER_DIR);
    }
  } catch (e) {
    console.warn('[CLIP-DBG] ensureStickerDir FAILED:', e);
  }
}

export const OPEN_SEND_SCREEN = '__open_send_screen__';

async function ctx(): Promise<boolean> {
  try {
    const fp = await PluginCommAPI.getCurrentFilePath();
    if (fp.success && fp.result) _filePath = fp.result;
    const pg = await PluginCommAPI.getCurrentPageNum();
    if (pg.success && pg.result !== undefined) _pageNum = pg.result;
    return !!_filePath && _pageNum !== null;
  } catch { return false; }
}

export async function executeAction(action: string): Promise<string> {
  console.log('[ToolActions]:', action);

  if (action === 'insert_text') {
    const lastMode = getLastMode();
    const mode = await toggleMode(lastMode);
    return mode === 'nospacing' ? 'Text receive: no-gap ON' : mode === 'paragraph' ? 'Text receive: paragraph ON' : 'Text receive OFF';
  }

  if (action === 'text_recv_nospacing') {
    const mode = await toggleMode('nospacing');
    return mode === 'nospacing' ? 'Text receive: no-gap ON' : 'Text receive OFF';
  }
  if (action === 'text_recv_paragraph') {
    const mode = await toggleMode('paragraph');
    return mode === 'paragraph' ? 'Text receive: paragraph ON' : 'Text receive OFF';
  }

  if (action === TOOL_IDS.AI_RELAY) {
    
    const { startAiReceiveMode } = require('./BackgroundService');
    await startAiReceiveMode();
    return 'Opened AI bubble';
  }

  if (action === 'lasso_ai') {
    const { requestAiSelection } = require('./BackgroundService');
    requestAiSelection()
      .catch((e: unknown) => console.error('[ToolActions]: AI selection:', e));
    return 'AI selection workflow opened';
  }

  if (action === TOOL_IDS.SMART_LASSO) {
    
    
    if (isMosaicBoardSurface()) {
      FloatingToolbarBridge.showSmartLassoCapture();
      return 'Smart lasso capture opened';
    }
    if (!await ctx()) return 'Smart lasso: no current page';
    
    
    if (await hasLassoSelection()) {
      const { LassoExtractor } = require('./LassoExtractor');
      const { markLassoDataPrePopulated } = require('../App');
      const extracted = await LassoExtractor.extract();
      if (!extracted.text && extracted.imagePaths.length === 0 && extracted.linkedFiles.length === 0) {
        NativeUIUtils.showErrorTipDialog(t('lasso_send_ocr_failed'));
        return 'Send aborted: nothing to send';
      }
      FloatingToolbarBridge.setLassoData(
        extracted.text,
        JSON.stringify(extracted.imagePaths),
        JSON.stringify(extracted.linkedFiles),
      );
      markLassoDataPrePopulated();
      FloatingToolbarBridge.showSendPanelFromBubble();
      return 'Send panel opened';
    }
    FloatingToolbarBridge.showSmartLassoCapture();
    return 'Smart lasso capture opened';
  }

  if (action === 'lasso_send') {
    return OPEN_SEND_SCREEN;
  }

  if (action === 'screenshot_ai') {
    const { handleScreenshotAi } = require('./BackgroundService');
    await handleScreenshotAi();
    return 'Screenshot AI: delegated to native';
  }

  if (action === 'open_copilot' || action === TOOL_IDS.INK_PALETTE) {
    try {
      await NativeModules.AIRelayModule?.openRelayMain?.('AIRelay');
      return 'Opened AIRelay';
    } catch (e: any) {
      console.warn('[ToolActions]: open AIRelay failed:', e);
      return `Open AIRelay failed: ${e?.message ?? e}`;
    }
  }

  if (action === 'insert_link') {
    try {
      const fpRes: any = await PluginCommAPI.getCurrentFilePath();
      const pgRes: any = await PluginCommAPI.getCurrentPageNum();
      if (fpRes?.success && fpRes.result) _filePath = fpRes.result;
      if (pgRes?.success && pgRes.result !== undefined) _pageNum = pgRes.result;
    } catch (e) {
      console.warn('[ToolActions] insert_link context refresh failed:', e);
    }

    if (_filePath && _pageNum !== null) {
      try {
        const layersRes: any = await PluginFileAPI.getLayers(_filePath, _pageNum);
        const currentLayer = Array.isArray(layersRes?.result)
          ? layersRes.result.find((layer: any) => layer.isCurrentLayer)
          : null;
        const currentLayerId = currentLayer?.layerId ?? currentLayer?.layerNum;
        if (currentLayerId !== undefined && currentLayerId !== 0) {
          console.warn('[ToolActions] insert_link blocked on non-main layer:', currentLayerId);
          NativeUIUtils.showErrorTipDialog(t('doc_link_main_only'));
          return 'Document links require the main layer';
        }
      } catch (e) {
        console.warn('[ToolActions] insert_link layer check failed:', e);
      }
    }

    FloatingToolbarBridge.showDocLinkPanel(_filePath);
    return 'Doc link panel opened';
  }

  if (action === 'toggle_spacing') {
    const { getActiveMode } = require('./BackgroundService');
    const cur = getActiveMode();
    const next = cur === 'nospacing' ? 'paragraph' : 'nospacing';
    const mode = await toggleMode(next);
    return mode ? `Switched to ${mode}` : 'Mode toggled off';
  }

  if (action === 'insert_image') {
    FloatingToolbarBridge.showImagePanel();
    return 'Image panel opened';
  }

  if (action === 'insert_doc_screenshot') {
    FloatingToolbarBridge.handleDocScreenshot();
    return 'Doc screenshot: delegated to native';
  }

  
  if (!action.startsWith('clip_save_') && !isMosaicInkSurface()) {
    if (!await ctx()) return 'No file context';
  }

  try {
    if (action.startsWith('clip_paste_')) {
      const slot = clipSlotOf(action);
      const result = await clipSmartAction(slot);
      console.log('[ToolActions]: clip result slot=', slot, 'result=', result);
      return result;
    }
    if (action.startsWith('clip_save_clear_')) {
      const slot = clipSlotOf(action);
      
      if (isMosaicInkSurface()) return await mosaicClipSave(slot, { deleteAfter: true });
      const saved = await clipSave(slot);
      if (!saved.startsWith('Saved to clip')) return saved;
      const removed: any = await PluginCommAPI.deleteLassoElements();
      console.log('[CLIP-DBG] delete selected after save:', JSON.stringify(removed));
      return removed?.success === false ? `${saved}; clear selection failed` : `${saved}; selected strokes cleared`;
    }
    if (action.startsWith('clip_save_')) {
      const slot = clipSlotOf(action);
      if (isMosaicInkSurface()) return await mosaicClipSave(slot);
      return await clipSave(slot);
    }
    if (action.startsWith('clip_clear_')) {
      const slot = clipSlotOf(action);
      return await clipClear(slot);
    }

    return `Unknown: ${action}`;
  } catch (e) {
    console.error('[ToolActions]:', e);
    return `Error: ${String(e)}`;
  }
}






function clipSlotOf(action: string): string {
  return action.slice(action.lastIndexOf('_') + 1);
}

const CLIP_CONVERTIBLE_TYPES = new Set([0, 700, 800]);
type ClipboardSkipKind = 'title' | 'image' | 'link' | 'textbox' | 'other';

function clipboardSkipLabels(kinds: ClipboardSkipKind[]): string {
  const labels: Record<ClipboardSkipKind, string> = {
    title: t('clip_skip_title'),
    image: t('clip_skip_image'),
    link: t('clip_skip_link'),
    textbox: t('clip_skip_textbox'),
    other: t('clip_skip_other'),
  };
  return Array.from(new Set(kinds)).map(kind => labels[kind]).join(t('clip_skip_separator'));
}

function clipboardSkipError(kind: ClipboardSkipKind): string {
  switch (kind) {
    case 'title': return t('clip_err_title');
    case 'image': return t('clip_err_image');
    case 'link': return t('clip_err_link');
    case 'textbox': return t('clip_err_textbox');
    default: return t('clip_err_unsupported', { types: t('clip_skip_other') });
  }
}

function clipboardSkipKindsFromCounts(counts: any): ClipboardSkipKind[] {
  if (!counts || typeof counts !== 'object') return [];
  const kinds: ClipboardSkipKind[] = [];
  if (Number(counts.titleNum) > 0) kinds.push('title');
  if (Number(counts.bitmapNum) > 0) kinds.push('image');
  if (Number(counts.textLinkNum) + Number(counts.trailLinkNum) + Number(counts.todoLinkNum) > 0) {
    kinds.push('link');
  }
  if (
    Number(counts.normalTextBoxNum) > 0 ||
    Number(counts.digestTextBoxNum) > 0 ||
    Number(counts.digestTextBoxEditableNum) > 0
  ) {
    kinds.push('textbox');
  }
  return kinds;
}

function clipboardSkipKindForElement(element: any): ClipboardSkipKind {
  switch (Number(element?.type)) {
    case 100: return 'title';
    case 200: return 'image';
    case 600: return 'link';
    case 500:
    case 501:
    case 502: return 'textbox';
    default: return 'other';
  }
}

function recycleClipboardElements(elements: any[]): void {
  for (const element of elements) {
    try { element?.recycle?.(); } catch (_) {}
  }
}

async function hasLassoSelection(): Promise<boolean> {
  try {
    const r = await PluginCommAPI.getLassoRect();
    console.log('[ToolActions] hasLassoSelection:', r.success, r.result);
    if (r.success && r.result != null) return true;
  } catch (_) {}
  try {
    const elements: any = await PluginCommAPI.getLassoElements();
    const selected = elements?.success === true && Array.isArray(elements.result) && elements.result.length > 0;
    console.log('[ToolActions] hasLassoSelection fallback elements=', selected ? elements.result.length : 0);
    return selected;
  } catch (_) {
    return false;
  }
}







const MOSAIC_CLIP_DIR = '/sdcard/EXPORT/mosaic';
const MOSAIC_LASSO_FILE = `${MOSAIC_CLIP_DIR}/clip_lasso.json`;
const MOSAIC_PASTE_FILE = `${MOSAIC_CLIP_DIR}/paste_strokes.json`;




const pendingMosaicSidecars = new Map<string, Promise<void>>();

const SIDE_MARGIN = 100;          


const NOTE_PRESSURE_MAX = 4095;
const NOTE_DEFAULT_THICKNESS = 1000;
const CLIP_PASTE_CONCURRENCY = 8;


const MOSAIC_THICK_FACTOR = 100;
const MOSAIC_THICK_MIN = 100;
const MOSAIC_THICK_MAX = 4000;


function isMosaicBoardSurface(): boolean {
  return FloatingToolbarBridge.isMosaicBoardSurface();
}


function isMosaicInkSurface(): boolean {
  return isMosaicBoardSurface() ||
    (FloatingToolbarBridge.isMosaicBoardVisible() && FloatingToolbarBridge.getMosaicSurface() === 'note');
}



function notifyStickerInserted(): void {
  try { (FloatingToolbar as any)?.notifyStickerInserted?.(); } catch (_) {}
}


const SUPPORTED_PEN_TYPES = new Set([10, 16, 15, 11]);


function sdkPenToMosaic(penType: number): number {
  switch (penType) {
    case 10:
    case 16: return 15;
    case 11: return 11;
    default: return 16;
  }
}

function rawElementThickness(element: any): number | null {
  if (typeof element?.thickness !== 'number' || !Number.isFinite(element.thickness)) return null;
  
  return element.thickness;
}

type ClipboardCoordinateSpace = 'mosaic-px' | 'note-page-px';
type MosaicSidecar = {
  strokes: any[];
  pageSize: { width: number; height: number } | null;
  coordinateSpace: ClipboardCoordinateSpace;
  screenShortPx: number | null;
};


async function readMosaicSidecar(path: string): Promise<MosaicSidecar | null> {
  const RNFS = getRNFS();
  if (!RNFS) return null;
  try {
    if (!(await RNFS.exists(path))) return null;
    const json = JSON.parse(await RNFS.readFile(path, 'utf8'));
    const strokes = Array.isArray(json?.strokes) ? json.strokes : null;
    if (!strokes || strokes.length === 0) return null;
    const width = Number(json?.pageSize?.width);
    const height = Number(json?.pageSize?.height);
    const coordinateSpace: ClipboardCoordinateSpace = json.coordinateSpace === 'note-page-px'
      || json.coordinateSpace !== 'mosaic-px'
        && strokes.some((stroke: any) => stroke?.sdkThickness != null || stroke?.sourcePenType != null)
      ? 'note-page-px' : 'mosaic-px';
    const screenShortPx = Number(json.screenShortPx);
    return {
      strokes, pageSize: width > 0 && height > 0 ? { width, height } : null,
      coordinateSpace, screenShortPx: Number.isFinite(screenShortPx) && screenShortPx > 0 ? screenShortPx : null,
    };
  } catch (e) {
    console.warn('[MOSAIC-CLIP] read strokes failed', path, e);
    return null;
  }
}


async function restoreClipboardAngles(el: any, stroke: any): Promise<boolean> {
  const values = stroke.angles ?? stroke.tilt;
  if (values == null) return true;
  if (!Array.isArray(values) || values.length % 2 !== 0 || !values.every(Number.isInteger)) {
    throw new Error('Invalid clipboard angle sequence');
  }
  if (values.length === 0) return true;
  const angles = [];
  for (let index = 0; index < values.length; index += 2) {
    angles.push({ x: values[index], y: values[index + 1] });
  }
  return Boolean(await el.angles.setRange(0, angles.length - 1, angles));
}

async function readMosaicStrokesFile(path: string): Promise<any[] | null> {
  return (await readMosaicSidecar(path))?.strokes ?? null;
}


async function currentNotePageSize(): Promise<{ width: number; height: number } | null> {
  try {
    
    
    if (_filePath && _pageNum !== null) {
      const cachedPageSize: any = await PluginFileAPI.getPageSize(_filePath, _pageNum);
      const cachedWidth = cachedPageSize?.result?.width;
      const cachedHeight = cachedPageSize?.result?.height;
      if (Number.isInteger(cachedWidth) && Number.isInteger(cachedHeight)
        && cachedWidth > 0 && cachedHeight > 0) {
        console.log('[MOSAIC-CLIP] current note pageSize=', `${cachedWidth}x${cachedHeight}`,
          'file=', _filePath, 'page=', _pageNum, 'source=context-cache');
        return { width: cachedWidth, height: cachedHeight };
      }
    }
    let filePath = _filePath;
    let pageNum = _pageNum;
    const [fileRes, pageRes]: any[] = await Promise.all([
      PluginCommAPI.getCurrentFilePath(),
      PluginCommAPI.getCurrentPageNum(),
    ]);
    if (fileRes?.success && typeof fileRes.result === 'string' && fileRes.result) filePath = fileRes.result;
    if (pageRes?.success && Number.isInteger(pageRes.result)) pageNum = pageRes.result;
    if (filePath && pageNum !== null) {
      const psRes: any = await PluginFileAPI.getPageSize(filePath, pageNum);
      const width = psRes?.result?.width;
      const height = psRes?.result?.height;
      if (Number.isInteger(width) && Number.isInteger(height) && width > 0 && height > 0) {
        console.log('[MOSAIC-CLIP] current note pageSize=', `${width}x${height}`,
          'file=', filePath, 'page=', pageNum);
        return { width, height };
      }
    }
  } catch (e) {
    console.warn('[MOSAIC-CLIP] get current page size failed', e);
  }
  console.warn('[MOSAIC-CLIP] actual page size unavailable; coordinate conversion skipped');
  return null;
}

async function stickerTargetPageSize(
  sourcePageSize: { width: number; height: number },
): Promise<{ width: number; height: number }> {
  const cachedPageSize = await currentNotePageSize();
  if (cachedPageSize) return cachedPageSize;

  
  
  
  console.log('[CLIP-DBG] sticker target pageSize fallback=source',
    `${sourcePageSize.width}x${sourcePageSize.height}`);
  return sourcePageSize;
}

function resolveElementPageSize(
  reported: { width: number; height: number },
): { width: number; height: number } {
  
  
  
  return reported;
}


async function writeMosaicSidecar(
  stickerPath: string, strokes: any[], pageSize?: { width: number; height: number },
  coordinateSpace: ClipboardCoordinateSpace = 'note-page-px', screenShortPx?: number | null,
): Promise<void> {
  const RNFS = getRNFS();
  if (!RNFS) return;
  try {
    await RNFS.writeFile(`${stickerPath}.mosaic.json`, JSON.stringify({
      v: 3, pageSize, coordinateSpace, ...(screenShortPx ? { screenShortPx } : {}), strokes,
    }), 'utf8');
  } catch (e) {
    console.warn('[MOSAIC-CLIP] write sidecar failed', e);
  }
}

function sidecarStrokeRect(
  strokes: any[], pageSize: { width: number; height: number },
): StickerPlacementMetadata['sourceRect'] | null {
  let left = Infinity; let top = Infinity; let right = -Infinity; let bottom = -Infinity;
  for (const stroke of strokes) {
    const points = Array.isArray(stroke?.pts) ? stroke.pts : [];
    for (let i = 0; i + 1 < points.length; i += 3) {
      const x = Number(points[i]); const y = Number(points[i + 1]);
      if (!Number.isFinite(x) || !Number.isFinite(y)) continue;
      left = Math.min(left, x); top = Math.min(top, y);
      right = Math.max(right, x); bottom = Math.max(bottom, y);
    }
  }
  if (!Number.isFinite(left) || right <= left || bottom <= top) return null;
  const pad = 32;
  return {
    left: Math.max(0, Math.floor(left) - pad),
    top: Math.max(0, Math.floor(top) - pad),
    right: Math.min(pageSize.width, Math.ceil(right) + pad),
    bottom: Math.min(pageSize.height, Math.ceil(bottom) + pad),
  };
}


async function pasteNoteFromMosaicSidecar(
  stickerPath: string,
): Promise<'handled' | 'unavailable' | 'failed'> {
  const RNFS = getRNFS();
  if (!RNFS) return 'unavailable';
  const pending = pendingMosaicSidecars.get(stickerPath);
  if (pending) {
    try { await pending; } catch (_) {}
  }

  let payload: any;
  let metadata: StickerPlacementMetadata;
  try {
    const sidecarPath = `${stickerPath}.mosaic.json`;
    if (!(await RNFS.exists(sidecarPath))) return 'unavailable';
    payload = JSON.parse(await RNFS.readFile(sidecarPath, 'utf8'));
    const rawMeta = JSON.parse(await RNFS.readFile(`${stickerPath}.meta.json`, 'utf8'));
    const sourceWidth = Number(rawMeta?.sourcePageSize?.width ?? payload?.pageSize?.width);
    const sourceHeight = Number(rawMeta?.sourcePageSize?.height ?? payload?.pageSize?.height);
    if (!(sourceWidth > 0) || !(sourceHeight > 0) ||
        !validStickerPlacementRect(rawMeta?.sourceRect)) return 'unavailable';
    metadata = {
      v: 1,
      sourcePageSize: { width: sourceWidth, height: sourceHeight },
      sourceRect: {
        left: Number(rawMeta.sourceRect.left), top: Number(rawMeta.sourceRect.top),
        right: Number(rawMeta.sourceRect.right), bottom: Number(rawMeta.sourceRect.bottom),
      },
    };
  } catch (error) {
    console.warn('[CLIP-DBG] direct note sidecar read failed', error);
    return 'unavailable';
  }

  const strokes: any[] = Array.isArray(payload?.strokes) ? payload.strokes : [];
  if (strokes.length === 0) return 'unavailable';
  const sidecarPenTypes = new Map<number, number>();
  const sidecarRawPenTypes = new Map<number, number>();
  const sidecarMarkerColors = new Map<number, number>();
  const sidecarThickness = new Map<number, number>();
  const sidecarFactors = new Map<string, number>();
  for (const stroke of strokes) {
    const rawType = Number(stroke?.sourcePenType ?? stroke?.penType);
    if (Number.isInteger(rawType)) sidecarRawPenTypes.set(rawType, (sidecarRawPenTypes.get(rawType) ?? 0) + 1);
    const sdkType = Number(stroke?.sdkPenType);
    sidecarPenTypes.set(sdkType, (sidecarPenTypes.get(sdkType) ?? 0) + 1);
    const thickness = Number(stroke?.sdkThickness);
    if (Number.isFinite(thickness)) sidecarThickness.set(thickness, (sidecarThickness.get(thickness) ?? 0) + 1);
    const factor = Number(stroke?.sdkFactorResize);
    if (Number.isFinite(factor)) {
      const key = factor.toFixed(6);
      sidecarFactors.set(key, (sidecarFactors.get(key) ?? 0) + 1);
    }
    if (sdkType === 11) {
      const color = Number(stroke?.penColor);
      sidecarMarkerColors.set(color, (sidecarMarkerColors.get(color) ?? 0) + 1);
    }
  }
  console.log('[CLIP-DBG] direct note sidecar styles=',
    'raw=', JSON.stringify(Object.fromEntries(sidecarRawPenTypes)),
    'normalized=', JSON.stringify(Object.fromEntries(sidecarPenTypes)),
    'thickness=', JSON.stringify(Object.fromEntries(sidecarThickness)),
    'factorResize=', JSON.stringify(Object.fromEntries(sidecarFactors)),
    'markerColors=', JSON.stringify(Object.fromEntries(sidecarMarkerColors)));
  const derivedRect = sidecarStrokeRect(strokes, metadata.sourcePageSize);
  if (derivedRect) {
    metadata.sourceRect = derivedRect;
    console.log('[CLIP-DBG] direct note sidecar placement from element points=',
      JSON.stringify(derivedRect));
  }

  let filePath = _filePath;
  let pageNum = _pageNum;
  try {
    const [fp, pg]: any[] = await Promise.all([
      PluginCommAPI.getCurrentFilePath(), PluginCommAPI.getCurrentPageNum(),
    ]);
    if (fp?.success && typeof fp.result === 'string' && fp.result) filePath = fp.result;
    if (pg?.success && Number.isInteger(pg.result)) pageNum = pg.result;
  } catch (error) {
    console.warn('[CLIP-DBG] direct note sidecar current page lookup failed', error);
  }
  if (!filePath || pageNum === null) return 'failed';
  let targetMachineType: number | null = null;
  try {
    const machine: any = await (PluginFileAPI as any).getFileMachineType?.(filePath);
    if (machine?.success && Number.isInteger(machine.result)) targetMachineType = machine.result;
  } catch (_) {}
  const targetSize = await currentNotePageSize();
  if (!targetSize) return 'failed';
  console.log('[CLIP-DBG] direct note paste target context file=', filePath,
    'page=', pageNum, 'machineType=', targetMachineType,
    'pageSize=', `${targetSize.width}x${targetSize.height}`);
  
  
  
  
  
  
  
  
  let targetLayerId = 0;
  try {
    const layersRes: any = await PluginFileAPI.getLayers(filePath, pageNum);
    const currentLayer = Array.isArray(layersRes?.result)
      ? layersRes.result.find((layer: any) => layer.isCurrentLayer)
      : null;
    const id = currentLayer ? layerIdOf(currentLayer) : undefined;
    if (Number.isInteger(id) && id >= 0 && id <= 3) targetLayerId = id;
  } catch (error) {
    console.warn('[CLIP-DBG] direct note paste current layer lookup failed', error);
  }
  console.log('[CLIP-DBG] direct note paste target layer=', targetLayerId);
  const sx = targetSize.width / metadata.sourcePageSize.width;
  const sy = targetSize.height / metadata.sourcePageSize.height;
  const elements: any[] = [];
  try {
    
    
    
    const prepared = new Array<any>(strokes.length);
    let next = 0;
    let skipped = 0;
    const worker = async () => {
      while (true) {
        const index = next++;
        if (index >= strokes.length) return;
        try {
          const stroke = strokes[index];
          const points = Array.isArray(stroke?.pts) ? stroke.pts : [];
          if (points.length < 6) { skipped++; continue; }
          const created: any = await (PluginCommAPI as any).createElement(0);
          if (!created?.success || !created.result?.stroke) { skipped++; continue; }
          const el: any = created.result;
          const pagePoints: { x: number; y: number }[] = [];
          const pressures: number[] = [];
          for (let i = 0; i + 2 < points.length; i += 3) {
            pagePoints.push({
              x: Math.round(Number(points[i]) * sx),
              y: Math.round(Number(points[i + 1]) * sy),
            });
            pressures.push(Math.max(1, Math.min(NOTE_PRESSURE_MAX,
              Math.round(Number(points[i + 2]) * NOTE_PRESSURE_MAX))));
          }
          if (pagePoints.length < 2) {
            try { el.recycle?.(); } catch (_) {}
            skipped++;
            continue;
          }
          const sourceThickness = Number.isInteger(stroke.sdkThickness) && stroke.sdkThickness > 0
            ? Number(stroke.sdkThickness)
            : Math.max(MOSAIC_THICK_MIN,
              Math.min(MOSAIC_THICK_MAX, Math.round(Number(stroke.width ?? 2) * MOSAIC_THICK_FACTOR)));
          const sourceFactor = Number(stroke.sdkFactorResize);
          const factor = Number.isFinite(sourceFactor) && sourceFactor > 0 ? sourceFactor : 1;
          
          
          
          el.factorResize = factor;
          el.thickness = Math.max(MOSAIC_THICK_MIN,
            Math.min(MOSAIC_THICK_MAX, Math.round(sourceThickness / factor)));
          el.layerNum = targetLayerId;
          el.stroke.penType = Number(stroke.sdkPenType);
          el.stroke.penColor = Number(stroke.penColor);
          const okPts = await el.stroke.points.setRange(0, pagePoints.length - 1, pagePoints);
          const okPrs = await el.stroke.pressures.setRange(0, pressures.length - 1, pressures);
          const okAngles = await restoreClipboardAngles(el, stroke);
          if (!okPts || !okPrs || !okAngles) {
            try { el.recycle?.(); } catch (_) {}
            skipped++;
            continue;
          }
          prepared[index] = el;
        } catch (_) {
          skipped++;
        }
      }
    };
    await Promise.all(Array.from(
      { length: Math.min(CLIP_PASTE_CONCURRENCY, strokes.length) }, () => worker(),
    ));
    elements.push(...prepared.filter(Boolean));
    const insertedPenTypes = new Map<number, number>();
    const insertedThickness = new Map<number, number>();
    const insertedFactors = new Map<string, number>();
    for (const element of elements) {
      const penType = Number(element?.stroke?.penType);
      if (Number.isInteger(penType)) insertedPenTypes.set(penType, (insertedPenTypes.get(penType) ?? 0) + 1);
      const thickness = Number(element?.thickness);
      if (Number.isFinite(thickness)) insertedThickness.set(thickness, (insertedThickness.get(thickness) ?? 0) + 1);
      const factor = Number(element?.factorResize);
      if (Number.isFinite(factor)) insertedFactors.set(factor.toFixed(6), (insertedFactors.get(factor.toFixed(6)) ?? 0) + 1);
    }
    console.log('[CLIP-DBG] direct note paste prepared fields=',
      'elements=', elements.length,
      'penTypes=', JSON.stringify(Object.fromEntries(insertedPenTypes)),
      'thickness=', JSON.stringify(Object.fromEntries(insertedThickness)),
      'factorResize=', JSON.stringify(Object.fromEntries(insertedFactors)));
    if (skipped > 0) {
      console.warn('[CLIP-DBG] direct note sidecar conversion skipped=', skipped,
        'of=', strokes.length);
      return 'failed';
    }
    if (elements.length === 0) return 'failed';
    const inserted: any = await PluginFileAPI.insertElements(filePath, pageNum, elements);
    console.log('[CLIP-DBG] direct note sidecar insertElements=', JSON.stringify(inserted),
      'elements=', elements.length, 'pageSize=', `${targetSize.width}x${targetSize.height}`);
    if (!inserted?.success) return 'failed';
    try { await (PluginCommAPI as any).reloadFile?.(); } catch (error) {
      console.warn('[CLIP-DBG] direct note sidecar reload failed', error);
    }
    const targetRect = {
      left: Math.max(0, Math.min(targetSize.width, Math.round(metadata.sourceRect.left * sx))),
      top: Math.max(0, Math.min(targetSize.height, Math.round(metadata.sourceRect.top * sy))),
      right: Math.max(0, Math.min(targetSize.width, Math.round(metadata.sourceRect.right * sx))),
      bottom: Math.max(0, Math.min(targetSize.height, Math.round(metadata.sourceRect.bottom * sy))),
    };
    const selected = await reselectLasso(targetRect, 'clip/paste', 8, 250);
    console.log('[CLIP-DBG] direct note sidecar lasso=', JSON.stringify(targetRect),
      'selected=', selected);
    return 'handled';
  } catch (error) {
    console.warn('[CLIP-DBG] direct note sidecar paste failed', error);
    return 'failed';
  } finally {
    for (const el of elements) { try { el?.recycle?.(); } catch (_) {} }
  }
}

type StickerPlacementMetadata = {
  v: 1;
  sourcePageSize: { width: number; height: number };
  sourceRect: { left: number; top: number; right: number; bottom: number };
};

function validStickerPlacementRect(rect: any): rect is StickerPlacementMetadata['sourceRect'] {
  return !!rect && [rect.left, rect.top, rect.right, rect.bottom].every(Number.isFinite)
    && rect.right > rect.left && rect.bottom > rect.top;
}

async function writeStickerPlacementMetadata(
  stickerPath: string,
  sourcePageSize: { width: number; height: number },
  sourceRect: StickerPlacementMetadata['sourceRect'] | null,
): Promise<void> {
  if (!sourceRect) return;
  const RNFS = getRNFS();
  if (!RNFS) return;
  const metadata: StickerPlacementMetadata = { v: 1, sourcePageSize, sourceRect };
  try {
    await RNFS.writeFile(`${stickerPath}.meta.json`, JSON.stringify(metadata), 'utf8');
    console.log('[CLIP-DBG] sticker placement metadata written',
      'sourcePageSize=', `${sourcePageSize.width}x${sourcePageSize.height}`,
      'sourceRect=', JSON.stringify(sourceRect));
  } catch (error) {
    console.warn('[CLIP-DBG] sticker placement metadata write failed', error);
  }
}

async function restoreInsertedStickerPlacement(stickerPath: string): Promise<boolean> {
  const RNFS = getRNFS();
  if (!RNFS) {
    console.warn('[CLIP-DBG] restore sticker placement skipped: RNFS unavailable');
    return false;
  }
  let metadata: StickerPlacementMetadata;
  try {
    const raw = await RNFS.readFile(`${stickerPath}.meta.json`, 'utf8');
    const parsed = JSON.parse(raw);
    if (!parsed?.sourcePageSize || !validStickerPlacementRect(parsed.sourceRect)) {
      console.warn('[CLIP-DBG] restore sticker placement skipped: metadata invalid', stickerPath);
      return false;
    }
    const sourceWidth = Number(parsed.sourcePageSize.width);
    const sourceHeight = Number(parsed.sourcePageSize.height);
    if (!(sourceWidth > 0) || !(sourceHeight > 0)) {
      console.warn('[CLIP-DBG] restore sticker placement skipped: source pageSize invalid',
        JSON.stringify(parsed.sourcePageSize));
      return false;
    }
    metadata = {
      v: 1,
      sourcePageSize: { width: sourceWidth, height: sourceHeight },
      sourceRect: {
        left: Number(parsed.sourceRect.left), top: Number(parsed.sourceRect.top),
        right: Number(parsed.sourceRect.right), bottom: Number(parsed.sourceRect.bottom),
      },
    };
  } catch (error) {
    console.warn('[CLIP-DBG] restore sticker placement metadata read failed', stickerPath, error);
    return false;
  }

  const targetPageSize = await stickerTargetPageSize(metadata.sourcePageSize);
  const sx = targetPageSize.width / metadata.sourcePageSize.width;
  const sy = targetPageSize.height / metadata.sourcePageSize.height;
  const targetRect = {
    left: Math.max(0, Math.min(targetPageSize.width,
      Math.round(metadata.sourceRect.left * sx))),
    top: Math.max(0, Math.min(targetPageSize.height,
      Math.round(metadata.sourceRect.top * sy))),
    right: Math.max(0, Math.min(targetPageSize.width,
      Math.round(metadata.sourceRect.right * sx))),
    bottom: Math.max(0, Math.min(targetPageSize.height,
      Math.round(metadata.sourceRect.bottom * sy))),
  };
  if (targetRect.right <= targetRect.left || targetRect.bottom <= targetRect.top) {
    console.warn('[CLIP-DBG] restore sticker placement skipped: target rect invalid',
      JSON.stringify(targetRect));
    return false;
  }

  try {
    const nativeResult = await (FloatingToolbar as any).resizeInsertedSticker(targetRect);
    console.log('[CLIP-DBG] native sticker placement result=', nativeResult,
      'targetRect=', JSON.stringify(targetRect));
    return nativeResult === true;
  } catch (error) {
    console.warn('[CLIP-DBG] native sticker placement failed', error);
    return false;
  }
}


async function getLassoElementsForSidecar(): Promise<any> {
  
  const result: any = await PluginCommAPI.getLassoElements();
  console.log('[MOSAIC-CLIP] lasso source=SDK Android elements=',
    result?.result?.length ?? 0);
  return result;
}


function normalizeStickerElementCoordinateMetadata(
  elements: any[],
  sourcePageSize: { width: number; height: number },
): { maxX: number; maxY: number } | null {
  try {
    const maxX = PointUtils.getRealMaxX(sourcePageSize);
    const maxY = PointUtils.getRealMaxY(sourcePageSize);
    for (const element of elements) {
      if (!element || typeof element !== 'object') continue;
      element.maxX = maxX;
      element.maxY = maxY;
    }
    console.log('[CLIP-DBG] normalized sticker source coordinate metadata',
      'pageSize=', `${sourcePageSize.width}x${sourcePageSize.height}`,
      'sourceMax=', `${maxX}x${maxY}`);
    return { maxX, maxY };
  } catch (error) {
    console.warn('[CLIP-DBG] source page coordinate metadata normalization failed', error);
    return null;
  }
}


async function elementsToMosaicStrokes(
  elements: any[], pageSize: { width: number; height: number },
  sourceRect?: StickerPlacementMetadata['sourceRect'] | null,
  targetRect?: StickerPlacementMetadata['sourceRect'] | null,
): Promise<any[]> {
  const out: any[] = new Array(elements.length);
  let shortPointReads = 0;
  let shortPressureReads = 0;
  let missingPointAccessors = 0;
  let shortStrokes = 0;
  const shortStrokeNums: number[] = [];
  const missingAccessorNums: number[] = [];
  let readErrors = 0;
  const readErrorNums: number[] = [];
  let firstReadError = '';
  const unsupportedPenTypes = new Map<number, number>();
  const unsupportedPenNums: number[] = [];
  let next = 0;
  
  
  const worker = async () => {
    while (true) {
      const index = next++;
      if (index >= elements.length) return;
      const el = elements[index];
      const stroke = el?.stroke;
      if (!stroke?.points) {
        missingPointAccessors++;
        if (Number.isInteger(el?.numInPage)) missingAccessorNums.push(Number(el.numInPage));
        continue;
      }
      try {
        const n = await stroke.points.size();
        if (!n || n < 2) {
          shortStrokes++;
          if (Number.isInteger(el?.numInPage)) shortStrokeNums.push(Number(el.numInPage));
          continue;
        }
        const angleCount = el.angles ? await el.angles.size() : 0;
        const [androidPts, prs, angles] = await Promise.all([
          stroke.points.getRange(0, n),
          stroke.pressures
            ? stroke.pressures.getRange(0, n).catch(() => [])
            : Promise.resolve([]),
          angleCount > 0 ? el.angles.getRange(0, angleCount) : Promise.resolve([]),
        ]);
        if (angles.length !== Math.max(0, angleCount)
          || angles.some((angle: any) => !Number.isInteger(angle?.x) || !Number.isInteger(angle?.y))) {
          throw new Error(`Incomplete clipboard angle data: expected ${angleCount}, got ${angles.length}`);
        }
        const downTilt = angles[0];
        if (androidPts.length < n) shortPointReads += 1;
        if (stroke.pressures && prs.length < n) shortPressureReads += 1;
        const pts: number[] = [];
        for (let i = 0; i < androidPts.length; i++) {
          const a = androidPts[i];
          if (!a || !Number.isFinite(a.x) || !Number.isFinite(a.y)) continue;
          const x = sourceRect && targetRect && sourceRect.right > sourceRect.left && sourceRect.bottom > sourceRect.top
            ? targetRect.left + (a.x - sourceRect.left) * (targetRect.right - targetRect.left)
              / (sourceRect.right - sourceRect.left)
            : a.x;
          const y = sourceRect && targetRect && sourceRect.right > sourceRect.left && sourceRect.bottom > sourceRect.top
            ? targetRect.top + (a.y - sourceRect.top) * (targetRect.bottom - targetRect.top)
              / (sourceRect.bottom - sourceRect.top)
            : a.y;
          const pr = typeof prs[i] === 'number' ? Math.max(0, Math.min(1, prs[i] / NOTE_PRESSURE_MAX)) : 1;
          pts.push(x, y, pr);
        }
        const sdkPenType = Number(stroke.penType);
        if (!SUPPORTED_PEN_TYPES.has(sdkPenType)) {
          unsupportedPenTypes.set(sdkPenType, (unsupportedPenTypes.get(sdkPenType) ?? 0) + 1);
          if (Number.isInteger(el?.numInPage)) unsupportedPenNums.push(Number(el.numInPage));
        }
        if (pts.length >= 6 && SUPPORTED_PEN_TYPES.has(sdkPenType)) {
          out[index] = {
            penStyle: sdkPenToMosaic(sdkPenType),
            sourcePenType: sdkPenType,
            sdkPenType,
            ...(Number.isInteger(el.layerNum) ? { layerNum: el.layerNum } : {}),
            penColor: Number(stroke.penColor),
            
            ...(angles.length ? { angles: angles.flatMap((angle: any) => [angle.x, angle.y]) } : {}),
            ...(downTilt && Number.isFinite(downTilt.x) && Number.isFinite(downTilt.y)
              ? { tilt: [downTilt.x, downTilt.y] } : {}),
            
            
            
            sdkThickness: typeof el.thickness === 'number' && Number.isFinite(el.thickness)
              ? Math.max(1, Math.round(el.thickness))
              : NOTE_DEFAULT_THICKNESS,
            ...(Number(el.factorResize) > 0 ? { sdkFactorResize: Number(el.factorResize) } : {}),
            width: Math.max(1, (typeof el.thickness === 'number'
              ? el.thickness : NOTE_DEFAULT_THICKNESS) / 100),
            pts,
          };
        }
      } catch (e) {
        readErrors++;
        if (Number.isInteger(el?.numInPage)) readErrorNums.push(Number(el.numInPage));
        if (!firstReadError) firstReadError = e instanceof Error ? e.message : String(e);
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(16, elements.length) }, () => worker()));
  const strokes = out.filter(Boolean);
  let minX = Infinity; let minY = Infinity; let maxX = -Infinity; let maxY = -Infinity;
  const thicknesses: number[] = [];
  const widths: number[] = [];
  const rawPenTypes = new Map<number, number>();
  const normalizedPenTypes = new Map<number, number>();
  const factors = new Map<string, number>();
  for (const stroke of strokes) {
    const rawType = Number(stroke?.sourcePenType);
    if (Number.isInteger(rawType)) rawPenTypes.set(rawType, (rawPenTypes.get(rawType) ?? 0) + 1);
    const normalizedType = Number(stroke?.sdkPenType);
    if (Number.isInteger(normalizedType)) normalizedPenTypes.set(normalizedType, (normalizedPenTypes.get(normalizedType) ?? 0) + 1);
    const factor = Number(stroke?.sdkFactorResize);
    if (Number.isFinite(factor)) factors.set(factor.toFixed(6), (factors.get(factor.toFixed(6)) ?? 0) + 1);
    if (Number.isFinite(stroke?.sdkThickness)) thicknesses.push(Number(stroke.sdkThickness));
    if (Number.isFinite(stroke?.width)) widths.push(Number(stroke.width));
    const points = stroke.pts as number[];
    for (let i = 0; i + 1 < points.length; i += 3) {
      minX = Math.min(minX, points[i]); maxX = Math.max(maxX, points[i]);
      minY = Math.min(minY, points[i + 1]); maxY = Math.max(maxY, points[i + 1]);
    }
  }
  console.log('[MOSAIC-CLIP] converted note strokes=', strokes.length,
    'pageSize=', `${pageSize.width}x${pageSize.height}`,
    'bbox=', Number.isFinite(minX) ? `${Math.round(minX)},${Math.round(minY)}-${Math.round(maxX)},${Math.round(maxY)}` : 'empty',
    'thickness=', thicknesses.length ? `${Math.min(...thicknesses)}..${Math.max(...thicknesses)}` : 'none',
    'width=', widths.length ? `${Math.min(...widths).toFixed(2)}..${Math.max(...widths).toFixed(2)}` : 'none',
    'rawPenTypes=', JSON.stringify(Object.fromEntries(rawPenTypes)),
    'normalizedPenTypes=', JSON.stringify(Object.fromEntries(normalizedPenTypes)),
    'factorResize=', JSON.stringify(Object.fromEntries(factors)),
    'shortPoints=', shortPointReads, 'shortPressures=', shortPressureReads,
    'missingAccessors=', missingPointAccessors, 'shortStrokes=', shortStrokes,
    'readErrors=', readErrors, 'firstReadError=', firstReadError,
    'unsupportedPenTypes=', JSON.stringify(Object.fromEntries(unsupportedPenTypes)),
    'unsupportedPenNums=', JSON.stringify(unsupportedPenNums.slice(0, 100)),
    'missingAccessorNums=', JSON.stringify(missingAccessorNums.slice(0, 100)),
    'shortStrokeNums=', JSON.stringify(shortStrokeNums.slice(0, 100)),
    'readErrorNums=', JSON.stringify(readErrorNums.slice(0, 100)));
  return strokes;
}



function requestMosaicClearSelection(deleteAfter: boolean): void {
  try { (FloatingToolbar as any)?.requestMosaicClearSelection?.(deleteAfter); }
  catch (e) { console.warn('[MOSAIC-CLIP] clear-selection broadcast failed', e); }
}


export function clearMosaicLassoSelection(): void {
  requestMosaicClearSelection(false);
}


async function mosaicStrokesToElements(
  strokes: any[], pageSize: { width: number; height: number },
  screenShortPx?: number | null,
): Promise<any[] | null> {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const s of strokes) {
    const p = s.pts ?? [];
    for (let i = 0; i + 1 < p.length; i += 3) {
      if (p[i] < minX) minX = p[i]; if (p[i] > maxX) maxX = p[i];
      if (p[i + 1] < minY) minY = p[i + 1]; if (p[i + 1] > maxY) maxY = p[i + 1];
    }
  }
  if (!(maxX >= minX)) return [];
  const bw = Math.max(1, maxX - minX), bh = Math.max(1, maxY - minY);
  const pageScale = screenShortPx && screenShortPx > 0
    ? Math.min(pageSize.width, pageSize.height) / screenShortPx : 1;
  const fit = Math.min(1, (pageSize.width - 2 * SIDE_MARGIN) / (bw * pageScale),
    (pageSize.height - 2 * SIDE_MARGIN) / (bh * pageScale));
  const resizeFactor = pageScale > 0 ? 1 / pageScale : 1;
  console.log('[MOSAIC-CLIP] coordinate conversion mosaic-px -> note-page-px',
    'sourceSize=', `${bw}x${bh}`, 'pageScale=', pageScale, 'fit=', fit,
    'resizeFactor=', resizeFactor);

  const elements: any[] = [];
  for (const s of strokes) {
    const createRes: any = await (PluginCommAPI as any).createElement(0);
    if (!createRes?.success || !createRes.result) { console.warn('[MOSAIC-CLIP] createElement failed'); return null; }
    const el: any = createRes.result;
    const androidPoints: any[] = []; const pressures: number[] = [];
    const p = s.pts ?? [];
    for (let i = 0; i + 2 < p.length; i += 3) {
      const pageX = SIDE_MARGIN + (p[i] - minX) * pageScale * fit;
      const pageY = SIDE_MARGIN + (p[i + 1] - minY) * pageScale * fit;
      androidPoints.push({ x: Math.round(pageX), y: Math.round(pageY) });
      pressures.push(Math.max(1, Math.min(NOTE_PRESSURE_MAX, Math.round((p[i + 2] ?? 1) * NOTE_PRESSURE_MAX))));
    }
    if (androidPoints.length < 2 || !el.stroke) continue;
    
    
    
    const tierThickness = Math.max(MOSAIC_THICK_MIN,
      Math.min(MOSAIC_THICK_MAX, Math.round((s.width ?? 2) * MOSAIC_THICK_FACTOR)));
    el.factorResize = resizeFactor;
    el.thickness = Math.max(MOSAIC_THICK_MIN, Math.round(tierThickness / resizeFactor));
    el.stroke.penType = Number(s.sdkPenType);
    
    el.stroke.penColor = el.stroke.penType === 11
      ? Number(s.penColor)
      : (Number(s.penColor) === 0xFE ? 0xFE : 0);
    const okPts = await el.stroke.points.setRange(0, androidPoints.length - 1, androidPoints);
    const okPrs = await el.stroke.pressures.setRange(0, pressures.length - 1, pressures);
    const okAngles = await restoreClipboardAngles(el, s);
    if (!okPts || !okPrs || !okAngles) { console.warn('[MOSAIC-CLIP] setRange failed'); return null; }
    elements.push(el);
  }
  return elements;
}


export async function extractMosaicLassoText(
  onProgress?: (stage: 'recognizing' | 'done') => void,
): Promise<string | null> {
  if (!isMosaicBoardSurface()) return null;
  const selection = await readMosaicSidecar(MOSAIC_LASSO_FILE);
  const strokes = selection?.strokes;
  if (!strokes) return null;
  const canWrite = await FloatingToolbarBridge.requestFileWritePermission();
  if (!canWrite) {
    try { NativeUIUtils.showErrorTipDialog(t('perm_required')); } catch (_) {}
    return null;
  }
  const pageSize = await currentNotePageSize();
  if (!pageSize) {
    console.warn('[MOSAIC-OCR] actual page size unavailable');
    return null;
  }
  const elements = await mosaicStrokesToElements(strokes, pageSize, selection?.screenShortPx);
  if (!elements || elements.length === 0) return null;
  try { onProgress?.('recognizing'); } catch (_) {}
  try {
    const res: any = await PluginCommAPI.recognizeElements(elements, pageSize);
    if (res?.success && typeof res.result === 'string' && res.result.trim()) return res.result.trim();
    console.warn('[MOSAIC-OCR] recognizeElements empty/failed:', res?.error?.message);
    return null;
  } catch (e) {
    console.warn('[MOSAIC-OCR] recognizeElements threw:', e);
    return null;
  } finally {
    try { onProgress?.('done'); } catch (_) {}
  }
}


async function mosaicClipSave(slot: string, opts: { deleteAfter?: boolean } = {}): Promise<string> {
  if (!isMosaicInkSurface()) return 'Mosaic clip deferred: Mosaic surface is not active';
  const selection = await readMosaicSidecar(MOSAIC_LASSO_FILE);
  const strokes = selection?.strokes;
  if (!strokes) { NativeUIUtils.showErrorTipDialog(t('clip_err_read_failed')); return 'Mosaic clip empty'; }
  await ensureStickerDir();

  const existingClips = await loadClips();
  const oldPath = existingClips[slot];
  const path = `${STICKER_DIR}/quickbar_clip_${slot}_${Date.now()}.sticker`;

  if (oldPath && await getRNFS()?.exists(oldPath).catch(() => false)) {
    const confirmed = await NativeUIUtils.showRattaDialog(
      t('clip_overwrite'), t('btn_cancel'), t('btn_confirm'), false,
    ).catch(() => true);
    if (!confirmed) return `Clip ${slot} overwrite cancelled`;
  }

  const pageSize = await currentNotePageSize();
  if (!pageSize) {
    NativeUIUtils.showErrorTipDialog(t('clip_save_failed'));
    return `Save clip ${slot} failed (page size unavailable)`;
  }
  const runtimeDeviceType = await PluginManager.getDeviceType();
  const deviceType = stickerMachineTypeForPageSize(pageSize, runtimeDeviceType);

  const elements = await mosaicStrokesToElements(strokes, pageSize, selection?.screenShortPx);
  if (elements === null) return `Save clip ${slot} failed`;
  if (elements.length === 0) return `Save clip ${slot} failed (no strokes)`;
  console.log('[MOSAIC-CLIP] sticker target machineType=', deviceType,
    'runtimeDeviceType=', runtimeDeviceType,
    'source pageSize=', `${pageSize.width}x${pageSize.height}`);

  
  
  const convertRes: any = await NativeModules.NativePluginAPI.convertElement2Sticker({
    machineType: deviceType, elements, stickerPath: path,
  });
  if (!convertRes?.success || convertRes.result === false) {
    NativeUIUtils.showErrorTipDialog(t('clip_save_failed'));
    return `Save clip ${slot} failed`;
  }

  
  await writeMosaicSidecar(path, strokes, pageSize, 'mosaic-px', selection?.screenShortPx);
  if (oldPath) {
    try { await FileUtils.deleteFile(oldPath); } catch (_) {}
    try { await FileUtils.deleteFile(oldPath + '.mosaic.json'); } catch (_) {}
    try { await FileUtils.deleteFile(oldPath + '.meta.json'); } catch (_) {}
  }
  const clips = await loadClips(); clips[slot] = path; await saveClips(clips);
  if (isMosaicInkSurface()) requestMosaicClearSelection(opts.deleteAfter === true);
  return `Saved to clip ${slot}`;
}


async function mosaicClipPaste(slot: string): Promise<string> {
  if (!isMosaicInkSurface()) return 'Mosaic paste deferred: Mosaic surface is not active';
  const clips = await loadClips();
  const stored = clips[slot];
  if (!stored) {
    console.warn('[MOSAIC-CLIP] paste slot=', slot, 'empty clips=', JSON.stringify(clips));
    NativeUIUtils.showErrorTipDialog(t('clip_empty_hint'));
    return 'Clip empty';
  }
  const stickerPath = stored.startsWith('/') ? stored : `${STICKER_DIR}/${stored}`;
  const RNFS = getRNFS();
  if (!RNFS) return `Paste clip ${slot} failed (no fs)`;

  const pending = pendingMosaicSidecars.get(stickerPath);
  if (pending) await pending.catch(() => {});

  const sidecarPath = `${stickerPath}.mosaic.json`;
  const sidecar = await readMosaicSidecar(sidecarPath);
  const strokes = sidecar?.strokes;
  console.log('[MOSAIC-CLIP] paste slot=', slot, 'sticker=', stickerPath,
    'stickerExists=', await RNFS.exists(stickerPath).catch(() => false),
    'sidecarExists=', await RNFS.exists(sidecarPath).catch(() => false),
    'waitedPending=', !!pending, 'strokes=', strokes?.length ?? 0,
    'coordinateSpace=', sidecar?.coordinateSpace ?? null,
    'sdkPenTypes=', JSON.stringify(Object.fromEntries((strokes ?? []).reduce((m: Map<number, number>, s: any) =>
      m.set(Number(s?.sdkPenType), (m.get(Number(s?.sdkPenType)) ?? 0) + 1), new Map<number, number>()))));
  if (!sidecar || !strokes) {
    NativeUIUtils.showErrorTipDialog(t('clip_err_read_failed'));
    return `Paste clip ${slot} failed (no mosaic sidecar)`;
  }
  try {
    await RNFS.mkdir(MOSAIC_CLIP_DIR).catch(() => {});
    
    const pageSize = sidecar?.pageSize;
    await RNFS.writeFile(MOSAIC_PASTE_FILE, JSON.stringify({
      v: 2, ...(pageSize ? { pageSize } : {}), coordinateSpace: sidecar.coordinateSpace,
      ...(sidecar.screenShortPx ? { screenShortPx: sidecar.screenShortPx } : {}), strokes,
    }), 'utf8');
  } catch (e) {
    console.warn('[MOSAIC-CLIP] write paste file failed', e);
    return `Paste clip ${slot} failed`;
  }
  if (!isMosaicInkSurface()) return `Paste clip ${slot} deferred: Mosaic surface is not active`;
  try { (FloatingToolbar as any)?.requestMosaicPasteStrokes?.(); } catch (e) { console.warn('[MOSAIC-CLIP] broadcast failed', e); }
  return `Pasted clip ${slot} to Mosaic`;
}

async function clipSmartAction(slot: string): Promise<string> {
  if (_clipBusy) return 'Clip busy';
  _clipBusy = true;
  try {
    
    if (isMosaicInkSurface()) {
      const hasSel = (await readMosaicStrokesFile(MOSAIC_LASSO_FILE)) != null;
      console.log('[MOSAIC-CLIP] smart action slot=', slot, 'surface=', FloatingToolbarBridge.getMosaicSurface(),
        'lassoFile=', hasSel ? 'present' : 'absent', '=>', hasSel ? 'save' : 'paste');
      return hasSel ? await mosaicClipSave(slot) : await mosaicClipPaste(slot);
    }

    
    
    const hasHostLasso = await (FloatingToolbar as any).hasHostLasso();
    if (!hasHostLasso) return await clipPasteSticker(slot);

    
    const saveResult = await clipSave(slot);
    if (saveResult === '__NO_LASSO__') return await clipPasteSticker(slot);
    return saveResult;
  } finally {
    _clipBusy = false;
  }
}

async function clipSave(slot: string): Promise<string> {
  await ensureStickerDir();

  const existingClips = await loadClips();
  const oldPath = existingClips[slot];
  const name = `quickbar_clip_${slot}_${Date.now()}.sticker`;
  const path = `${STICKER_DIR}/${name}`;

  console.log('[ToolActions]: clipSave slot=', slot, 'path=', path);

  const commitSave = async (): Promise<string> => {
    if (oldPath) {
      try { await FileUtils.deleteFile(oldPath); } catch (_) {}
      try { await FileUtils.deleteFile(oldPath + '.meta.json'); } catch (_) {}
      try { await FileUtils.deleteFile(oldPath + '.mosaic.json'); } catch (_) {}
    }
    await PluginCommAPI.setLassoBoxState(2);
    const clips = await loadClips();
    clips[slot] = path;
    await saveClips(clips);
    return `Saved to clip ${slot}`;
  };

  const startedAt = Date.now();
  console.log('[CLIP-DBG] ===== clipSave element conversion =====');
  let sourceLassoRect: StickerPlacementMetadata['sourceRect'] | null = null;
  try {
    const rectRes: any = await PluginCommAPI.getLassoRect();
    if (rectRes?.success && validStickerPlacementRect(rectRes.result)) {
      sourceLassoRect = {
        left: Number(rectRes.result.left), top: Number(rectRes.result.top),
        right: Number(rectRes.result.right), bottom: Number(rectRes.result.bottom),
      };
      console.log('[CLIP-DBG] source lasso rect=', JSON.stringify(sourceLassoRect));
    } else {
      console.warn('[CLIP-DBG] source lasso rect unavailable', JSON.stringify(rectRes));
    }
  } catch (error) {
    console.warn('[CLIP-DBG] source lasso rect read failed', error);
  }
  let snapshot: any = await getLassoElementsForSidecar().catch(error => {
    console.warn('[CLIP-DBG] lasso element read failed', error);
    return null;
  });
  if (!snapshot?.success || !Array.isArray(snapshot.result) || snapshot.result.length === 0) {
    return '__NO_LASSO__';
  }

  const selectedElements: any[] = snapshot.result;
  let typeCounts: any = null;
  try {
    const countResponse: any = await (PluginCommAPI as any).getLassoElementTypeCounts?.();
    if (countResponse?.success !== false) typeCounts = countResponse?.result ?? null;
  } catch (error) {
    console.warn('[CLIP-DBG] lasso type counts unavailable', error);
  }
  const skippedKinds = clipboardSkipKindsFromCounts(typeCounts);
  const convertibleElements = selectedElements.filter(element =>
    CLIP_CONVERTIBLE_TYPES.has(Number(element?.type)),
  );
  const elementKinds = selectedElements
    .filter(element => !CLIP_CONVERTIBLE_TYPES.has(Number(element?.type)))
    .map(clipboardSkipKindForElement);
  const allSkippedKinds = Array.from(new Set([...skippedKinds, ...elementKinds]));
  console.log('[CLIP-DBG] lasso type counts=', JSON.stringify(typeCounts),
    'selected=', selectedElements.length,
    'convertible=', convertibleElements.length,
    'skipped=', clipboardSkipLabels(allSkippedKinds));

  if (convertibleElements.length === 0) {
    recycleClipboardElements(selectedElements);
    const firstKind = allSkippedKinds[0] ?? 'other';
    NativeUIUtils.showErrorTipDialog(
      allSkippedKinds.length === 1
        ? clipboardSkipError(firstKind)
        : t('clip_err_unsupported', { types: clipboardSkipLabels(allSkippedKinds) }),
    );
    return `Save clip ${slot} failed: unsupported elements`;
  }

  if (convertibleElements.length !== selectedElements.length) {
    recycleClipboardElements(selectedElements.filter(element =>
      !CLIP_CONVERTIBLE_TYPES.has(Number(element?.type)),
    ));
    snapshot.result = convertibleElements;
    console.log('[CLIP-DBG] filtered unsupported lasso elements before sticker conversion',
      'remaining=', convertibleElements.length,
      'skipped=', selectedElements.length - convertibleElements.length);
  }

  const sourcePageSize = await currentNotePageSize();
  if (!sourcePageSize) {
    NativeUIUtils.showErrorTipDialog(t('clip_save_failed'));
    return `Save clip ${slot} failed (page size unavailable)`;
  }

  const sourceRange = normalizeStickerElementCoordinateMetadata(snapshot.result, sourcePageSize);
  if (!sourceRange) {
    NativeUIUtils.showErrorTipDialog(t('clip_save_failed'));
    return `Save clip ${slot} failed (coordinate metadata unavailable)`;
  }

  const runtimeDeviceType = await PluginManager.getDeviceType();
  const deviceType = stickerMachineTypeForPageSize(sourcePageSize, runtimeDeviceType);
  console.log('[CLIP-DBG] sticker target machineType=', deviceType,
    'runtimeDeviceType=', runtimeDeviceType,
    'coordinateMode=android-page',
    'elements=', snapshot.result.length,
    'sourcePageSize=', `${sourcePageSize.width}x${sourcePageSize.height}`,
    'sourceMax=', `${sourceRange.maxX}x${sourceRange.maxY}`);
  
  
  
  const sourceLayers: number[] = snapshot.result.map((element: any) => element.layerNum);
  const nonMainLayers = Array.from(new Set(sourceLayers.filter(n => Number.isInteger(n) && n !== 0)));
  if (nonMainLayers.length) {
    console.log('[CLIP-DBG] sticker conversion remaps source layers=', JSON.stringify(nonMainLayers), 'to MAINLAYER');
  }
  let nativeSaved = false;
  try {
    for (const element of snapshot.result) element.layerNum = 0;
    const converted: any = await NativeModules.NativePluginAPI.convertElement2Sticker({
      machineType: deviceType,
      elements: snapshot.result,
      stickerPath: path,
    });
    console.log('[CLIP-DBG] convertElement2Sticker machineType=', deviceType,
      'result=', JSON.stringify(converted),
      'elapsedMs=', Date.now() - startedAt);
    nativeSaved = !!converted?.success && converted.result !== false;
  } catch (error) {
    console.warn('[CLIP-DBG] convertElement2Sticker threw', error);
  } finally {
    snapshot.result.forEach((element: any, index: number) => { element.layerNum = sourceLayers[index]; });
  }
  if (!nativeSaved) {
    NativeUIUtils.showErrorTipDialog(t('clip_save_failed'));
    return `Save clip ${slot} failed`;
  }

  await writeStickerPlacementMetadata(path, sourcePageSize, sourceLassoRect);

  
  
  
  let placementRect = sourceLassoRect;
  try {
    const observed = await (FloatingToolbar as any)?.getObservedLassoPageRect?.(
      sourcePageSize.width, sourcePageSize.height,
    );
    if (validStickerPlacementRect(observed)) {
      placementRect = {
        left: observed.left, top: observed.top,
        right: observed.right, bottom: observed.bottom,
      };
      console.log('[CLIP-DBG] live resized lasso pageRect=', JSON.stringify(placementRect));
    }
  } catch (error) {
    console.warn('[CLIP-DBG] live resized lasso rect unavailable', error);
  }

  
  
  if (oldPath && await getRNFS()?.exists(oldPath).catch(() => false)) {
    const confirmed = await NativeUIUtils.showRattaDialog(
      t('clip_overwrite'), t('btn_cancel'), t('btn_confirm'), false
    ).catch(() => true);
    if (!confirmed) {
      try { await FileUtils.deleteFile(path); } catch (_) {}
      try { await FileUtils.deleteFile(path + '.meta.json'); } catch (_) {}
      return `Clip ${slot} overwrite cancelled`;
    }
  }

  
  
  const sidecarPromise = (async () => {
    if (snapshot?.success && snapshot.result?.length) {
      const reported = await currentNotePageSize();
      if (!reported) return;
      const pageSize = resolveElementPageSize(reported);
      let sourceMachineType: number | null = null;
      try {
        const machine: any = _filePath ? await PluginFileAPI.getFileMachineType(_filePath) : null;
        if (machine?.success && Number.isInteger(machine.result)) sourceMachineType = machine.result;
      } catch (_) {}
      console.log('[CLIP-DBG] sidecar source context file=', _filePath,
        'page=', _pageNum, 'machineType=', sourceMachineType,
        'pageSize=', `${pageSize.width}x${pageSize.height}`);
      const uuids = snapshot.result
        .map((element: any) => element?.uuid)
        .filter((uuid: any): uuid is string => typeof uuid === 'string' && uuid.length > 0);
      const expectedSidecarStrokes = snapshot.result.filter(
        (element: any) => Number(element?.type) === 0,
      ).length;
      const discardSidecar = async () => {
        console.warn('[MOSAIC-CLIP] sidecar discarded path=', path, 'expectedStrokes=', expectedSidecarStrokes,
          '— this clip cannot be pasted into Mosaic');
        await FileUtils.deleteFile(`${path}.mosaic.json`);
        
        
        await writeStickerPlacementMetadata(path, pageSize, placementRect);
      };
      if (uuids.length === snapshot.result.length) {
        try {
          const nativeResult = await (FloatingToolbar as any).exportClipboardSidecar(
            path, uuids, pageSize.width, pageSize.height, sourceLassoRect, placementRect,
          );
          console.log('[CLIP-DBG] native sidecar export=', JSON.stringify(nativeResult));
          
          
          if (nativeResult?.success !== false &&
              Number(nativeResult?.count) === expectedSidecarStrokes) {
            return;
          }
          console.warn('[CLIP-DBG] native sidecar export incomplete; using JS fallback',
            'expected=', expectedSidecarStrokes, 'actual=', Number(nativeResult?.count ?? 0));
          await discardSidecar();
        } catch (error) {
          console.warn('[CLIP-DBG] native sidecar export failed; using JS fallback', error);
          await discardSidecar();
        }
      }
      const strokes = await elementsToMosaicStrokes(snapshot.result, pageSize, sourceLassoRect, placementRect);
      if (strokes.length !== expectedSidecarStrokes) {
        console.warn('[CLIP-DBG] JS sidecar export incomplete; discarding sidecar',
          'expected=', expectedSidecarStrokes, 'actual=', strokes.length);
        await discardSidecar();
        return;
      }
      if (strokes.length) {
        await writeMosaicSidecar(path, strokes, pageSize);
        
        
        
        const elementRect = sidecarStrokeRect(strokes, pageSize);
        if (elementRect) {
          await writeStickerPlacementMetadata(path, pageSize, elementRect);
          console.log('[CLIP-DBG] sticker placement metadata from elements=',
            JSON.stringify(elementRect));
        }
      }
    }
  })().catch(error => {
    console.warn('[MOSAIC-CLIP] note-origin sidecar failed', error);
  }).finally(() => {
    pendingMosaicSidecars.delete(path);
    console.log('[MOSAIC-CLIP] sidecar finished elapsedMs=', Date.now() - startedAt);
  });
  pendingMosaicSidecars.set(path, sidecarPromise);
  const result = await commitSave();
  console.log('[CLIP-DBG] native sticker committed elapsedMs=', Date.now() - startedAt);
  if (allSkippedKinds.length > 0) {
    NativeUIUtils.showErrorTipDialog(t('clip_saved_with_skipped', {
      skipped: clipboardSkipLabels(allSkippedKinds),
    }));
  }
  return result;
}


async function lassoContainsPicture(): Promise<boolean> {
  let elements: any[] = [];
  try {
    const rectRes: any = await PluginCommAPI.getLassoRect();
    const rect = rectRes?.success ? rectRes.result : null;
    if (!rect || rect.right <= rect.left || rect.bottom <= rect.top) return false;

    const fpRes: any = await PluginCommAPI.getCurrentFilePath();
    const pgRes: any = await PluginCommAPI.getCurrentPageNum();
    if (!fpRes?.success || !fpRes.result || !pgRes?.success || pgRes.result == null) return false;

    const psRes: any = await PluginFileAPI.getPageSize(fpRes.result, pgRes.result);
    const pageW = psRes?.result?.width ?? 0;
    const pageH = psRes?.result?.height ?? 0;
    if (!pageW || !pageH) return false;

    const elemRes: any = await PluginFileAPI.getElements(pgRes.result, fpRes.result);
    elements = elemRes?.success && Array.isArray(elemRes.result) ? elemRes.result : [];
    for (const el of elements) {
      if (el?.type !== 200 || !el.picture?.rect) continue; 
      if (!(el.maxX > 0) || !(el.maxY > 0)) continue;
      const sx = pageW / el.maxX;
      const sy = pageH / el.maxY;
      const p = el.picture.rect;
      
      
      const hit = p.left * sx < rect.right && p.right * sx > rect.left &&
                  p.top * sy < rect.bottom && p.bottom * sy > rect.top;
      if (hit) {
        console.log('[CLIP-DBG] lassoContainsPicture: hit picRect=', JSON.stringify(p),
          'scale=', sx.toFixed(3), sy.toFixed(3), 'lasso=', JSON.stringify(rect));
        return true;
      }
    }
  } catch (e) {
    console.warn('[CLIP-DBG] lassoContainsPicture error:', e);
  } finally {
    for (const el of elements) { try { el?.recycle?.(); } catch (_) {} }
  }
  return false;
}


function stickerMachineTypeForPageSize(
  pageSize: { width: number; height: number }, fallback: number,
): number {
  const shortSide = Math.min(pageSize.width, pageSize.height);
  const longSide = Math.max(pageSize.width, pageSize.height);
  
  
  
  if (shortSide === 1404 && longSide === 1872) return 3;
  if (shortSide === 1920 && longSide === 2560) return 5;
  console.log('[CLIP-DBG] sticker machineType fallback=runtime', fallback,
    'pageSize=', `${pageSize.width}x${pageSize.height}`);
  return fallback;
}

function pickStickerMachineType(
  deviceType: number, pageSize?: { width: number; height: number },
): number {
  const selected = pageSize ? stickerMachineTypeForPageSize(pageSize, deviceType) : deviceType;
  console.log('[CLIP-DBG] pickStickerMachineType runtime=', deviceType,
    'selected=', selected, 'pageSize=', pageSize ? `${pageSize.width}x${pageSize.height}` : 'unknown');
  return selected;
}

async function clipPasteSticker(slot: string): Promise<string> {
  const clips = await loadClips();
  const stored = clips[slot];
  if (!stored) {
    NativeUIUtils.showErrorTipDialog(t('clip_empty_hint'));
    return 'Clip empty';
  }

  let paths: string[];
  if (stored.startsWith('/')) {

    paths = [stored];
  } else {

    paths = [`${STICKER_DIR}/${stored}`];
  }

  for (const path of paths) {

    if (path.endsWith('.textclip.json')) {
      try {
        const RNFS = getRNFS();
        if (!RNFS) return `Paste clip ${slot} failed (no fs)`;
        const content = await RNFS.readFile(path, 'utf8');
        const payload = JSON.parse(content);
        const items: any[] = Array.isArray(payload?.items) ? payload.items : [];
        if (items.length === 0) {
          return `Paste clip ${slot} failed (empty textclip)`;
        }
        let okAll = true;
        let unionRect = { left: Infinity, top: Infinity, right: -Infinity, bottom: -Infinity };
        for (const tb of items) {
          console.log('[ToolActions] insertText input textRect:', JSON.stringify(tb.textRect));
          const r: any = await (PluginNoteAPI as any).insertText?.(tb);
          console.log('[ToolActions] insertText result:', JSON.stringify(r));
          if (!r?.success) {
            okAll = false;
            console.warn('[ToolActions]: insertText failed:', r);
          } else if (tb.textRect) {
            unionRect.left = Math.min(unionRect.left, tb.textRect.left);
            unionRect.top = Math.min(unionRect.top, tb.textRect.top);
            unionRect.right = Math.max(unionRect.right, tb.textRect.right);
            unionRect.bottom = Math.max(unionRect.bottom, tb.textRect.bottom);
          }
        }

        if (unionRect.left < Infinity) {
          try {
            await new Promise(r => setTimeout(r, 500));

            await PluginCommAPI.lassoElements({ left: 0, top: 0, right: 1, bottom: 1 });
            await new Promise(r => setTimeout(r, 200));
            const pad = 5;
            const lassoTarget = {
              left: unionRect.left - pad, top: unionRect.top - pad,
              right: unionRect.right + pad, bottom: unionRect.bottom + pad,
            };
            console.log('[ToolActions] lassoElements target rect:', JSON.stringify(lassoTarget));
            const lr: any = await PluginCommAPI.lassoElements(lassoTarget);
            console.log('[ToolActions] lassoElements result:', JSON.stringify(lr));
            await (PluginCommAPI as any).setLassoBoxState?.(0);
          } catch (e) { console.warn('[ToolActions] lassoElements after textclip paste:', e); }
        }

        return okAll ? `Pasted clip ${slot}` : `Paste clip ${slot} partial`;
      } catch (e) {
        console.warn('[ToolActions]: textclip paste error:', e);
        return `Paste clip ${slot} failed`;
      }
    }

    
    
    
    
    const direct = await pasteNoteFromMosaicSidecar(path);
    if (direct === 'handled') {
      return `Pasted clip ${slot}`;
    }
    if (direct === 'failed') {
      console.warn('[CLIP-DBG] direct note sidecar paste failed; trying sticker path');
    }

    console.log('[CLIP-DBG] clipPaste path=', path);
    try {
      const pasteSzRes: any = await PluginCommAPI.getStickerSize(path);
      console.log('[CLIP-DBG] paste getStickerSize before insert=', JSON.stringify(pasteSzRes));
    } catch (e) {
      console.warn('[CLIP-DBG] paste getStickerSize before insert failed:', e);
    }
    try {
      const r = await PluginCommAPI.insertSticker(path);
      console.log('[CLIP-DBG] insertSticker result:', JSON.stringify(r));
      if (r.success) {
        const restored = await restoreInsertedStickerPlacement(path);
        console.log('[CLIP-DBG] insertSticker placement restored=', restored);
        notifyStickerInserted();
        return `Pasted clip ${slot}`;
      }
    } catch (e) { console.warn('[CLIP-DBG] insertSticker error:', e); }

    try {
      const r2 = await PluginCommAPI.insertSticker(path + '.sticker');
      console.log('[CLIP-DBG] insertSticker(.sticker) result:', JSON.stringify(r2));
      if (r2.success) {
        const restored = await restoreInsertedStickerPlacement(path);
        console.log('[CLIP-DBG] insertSticker(.sticker) placement restored=', restored);
        notifyStickerInserted();
        return `Pasted clip ${slot}`;
      }
    } catch (e) { console.warn('[CLIP-DBG] insertSticker(.sticker) error:', e); }
  }

  return `Paste clip ${slot} failed`;
}



const ELEMENT_TYPE_PICTURE = 200;


async function repairPictureElementPaths(
  elements: any[], notePath: string, page: number,
): Promise<string[]> {
  const RNFS = getRNFS();
  const broken: any[] = [];
  for (const el of elements) {
    if (el?.type !== ELEMENT_TYPE_PICTURE) continue;
    const p = el.picture?.picturePath;
    let exists = false;
    if (p && RNFS) { try { exists = await RNFS.exists(p); } catch (_) {} }
    if (!exists) broken.push(el);
  }
  if (broken.length === 0) return [];

  const tmpFiles: string[] = [];
  const ts = Date.now();
  const previewPath = `${STICKER_DIR}/tmp_layer0_${ts}.png`;

  
  let ok = false;
  try {
    const r0: any = await PluginNoteAPI.generateLayerPreviewImage(notePath, page, 0, previewPath);
    ok = r0?.success === true && r0.result !== false &&
      (RNFS ? await RNFS.exists(previewPath) : true);
  } catch (e) { console.warn('[SNAP] layer preview(0) error:', e); }
  if (!ok) {
    try {
      const r1: any = await PluginNoteAPI.generateLayerPreviewImage(notePath, page, -1, previewPath);
      ok = r1?.success === true && r1.result !== false &&
        (RNFS ? await RNFS.exists(previewPath) : true);
    } catch (e) { console.warn('[SNAP] layer preview(-1) error:', e); }
  }
  if (!ok) {
    console.warn('[SNAP] layer preview unavailable, pictures cannot be repaired');
    return tmpFiles;
  }
  tmpFiles.push(previewPath);

  
  let pageW = 0;
  let pageH = 0;
  try {
    const psRes: any = await PluginFileAPI.getPageSize(notePath, page);
    if (psRes?.success && psRes.result) {
      pageW = psRes.result.width;
      pageH = psRes.result.height;
    }
  } catch (_) {}

  for (let i = 0; i < broken.length; i++) {
    const el = broken[i];
    const storageRect = el.picture?.rect;
    if (!storageRect) continue;
    
    
    
    const maxX = typeof el.maxX === 'number' && el.maxX > 0 ? el.maxX : 0;
    const maxY = typeof el.maxY === 'number' && el.maxY > 0 ? el.maxY : 0;
    const sx = pageW > 0 && maxX > 0 ? pageW / maxX : 1;
    const sy = pageH > 0 && maxY > 0 ? pageH / maxY : 1;
    const rect = {
      left: storageRect.left * sx,
      top: storageRect.top * sy,
      right: storageRect.right * sx,
      bottom: storageRect.bottom * sy,
    };
    const outPath = `${STICKER_DIR}/tmp_pic_${ts}_${i}.png`;
    try {
      await NativeModules.InklingImageUtil.cropRegion(
        previewPath, pageW, pageH,
        rect.left, rect.top, rect.right, rect.bottom, outPath,
      );
      el.picture.picturePath = outPath;
      tmpFiles.push(outPath);
      console.log('[SNAP] picture repaired storage=', JSON.stringify(storageRect),
        'page=', JSON.stringify(rect), '→', outPath);
    } catch (e) {
      console.warn('[SNAP] picture crop failed:', e);
    }
  }
  return tmpFiles;
}

export async function snapshotCreate(silent = false, flushCurrent = true): Promise<string> {
  await ensureStickerDir();
  if (flushCurrent) {
    
    
    await saveCurrentPage('snapshot');
  }

  const fpRes: any = await PluginCommAPI.getCurrentFilePath();
  const pgRes: any = await PluginCommAPI.getCurrentPageNum();
  if (!fpRes?.success || !fpRes.result || !pgRes?.success || pgRes.result == null) {
    if (!silent) NativeUIUtils.showErrorTipDialog(t('snapshot_create_failed'));
    return 'Snapshot: no note context';
  }
  const notePath: string = fpRes.result;
  const page: number = pgRes.result;

  const elemRes: any = await PluginFileAPI.getElements(page, notePath);
  const elements: any[] = elemRes?.success && Array.isArray(elemRes.result) ? elemRes.result : [];
  if (elements.length === 0) {
    if (!silent) NativeUIUtils.showErrorTipDialog(t('snapshot_empty_page'));
    return 'Snapshot: page empty';
  }

  let tmpFiles: string[] = [];
  try {
    const deviceType = await PluginManager.getDeviceType();
    const pageSizeRes: any = await PluginFileAPI.getPageSize(notePath, page);
    const sourcePageSize = pageSizeRes?.success && pageSizeRes.result?.width > 0 && pageSizeRes.result?.height > 0
      ? pageSizeRes.result
      : null;
    if (!sourcePageSize) {
      console.warn('[SNAP] source page size unavailable for sticker conversion');
      if (!silent) NativeUIUtils.showErrorTipDialog(t('snapshot_create_failed'));
      return 'Snapshot: page size unavailable';
    }
    const machineType = pickStickerMachineType(deviceType, sourcePageSize);

    const ts = Date.now();
    const stickerPath = `${STICKER_DIR}/quickbar_snapshot_${ts}.sticker`;

    
    
    tmpFiles = await repairPictureElementPaths(elements, notePath, page);
    let convertElements = elements;
    if (elements.some(el => el?.type === ELEMENT_TYPE_PICTURE)) {
      const RNFS = getRNFS();
      const usable: any[] = [];
      for (const el of elements) {
        if (el?.type === ELEMENT_TYPE_PICTURE) {
          const p = el.picture?.picturePath;
          let exists = false;
          if (p && RNFS) { try { exists = await RNFS.exists(p); } catch (_) {} }
          if (!exists) {
            console.warn('[SNAP] dropping picture with unreadable path:', p);
            continue;
          }
        }
        usable.push(el);
      }
      convertElements = usable;
    }
    if (convertElements.length === 0) {
      if (!silent) NativeUIUtils.showErrorTipDialog(t('snapshot_create_failed'));
      return 'Snapshot: no convertible elements';
    }

    console.log('[SNAP] create page=', page, 'elements=', convertElements.length,
      '(raw=', elements.length, ') machineType=', machineType,
      'sourcePageSize=', `${sourcePageSize.width}x${sourcePageSize.height}`, 'path=', stickerPath);
    const convertRes: any = await NativeModules.NativePluginAPI.convertElement2Sticker({
      machineType,
      elements: convertElements,
      stickerPath,
    });
    if (!convertRes?.success || convertRes.result === false) {
      console.warn('[SNAP] convertElement2Sticker FAILED:', JSON.stringify(convertRes));
      if (!silent) NativeUIUtils.showErrorTipDialog(t('snapshot_create_failed'));
      return 'Snapshot: convert failed';
    }

    
    let thumbPath = `${STICKER_DIR}/quickbar_snapshot_${ts}.png`;
    try {
      const pngRes: any = await PluginFileAPI.generateNotePng({
        notePath, page, times: 1, pngPath: thumbPath, type: 1,
      });
      if (!pngRes?.success || pngRes.result === false) {
        console.warn('[SNAP] generateNotePng failed:', JSON.stringify(pngRes));
        thumbPath = '';
      }
    } catch (e) {
      console.warn('[SNAP] generateNotePng error:', e);
      thumbPath = '';
    }

    const records = await loadSnapshots();
    records.unshift({
      id: String(ts),
      sticker: stickerPath,
      thumb: thumbPath,
      note: notePath,
      page,
      ts,
    });
    const keep = records.slice(0, 3);
    for (const stale of records.slice(3)) {
      try { await FileUtils.deleteFile(stale.sticker); } catch (_) {}
      if (stale.thumb) { try { await FileUtils.deleteFile(stale.thumb); } catch (_) {} }
    }
    await saveSnapshots(keep);
    return 'Snapshot created';
  } finally {
    for (const f of tmpFiles) { try { await FileUtils.deleteFile(f); } catch (_) {} }
    for (const el of elements) { try { el?.recycle?.(); } catch (_) {} }
  }
}

export async function snapshotRestore(stickerPath: string): Promise<string> {
  const RNFS = getRNFS();
  if (RNFS && !(await RNFS.exists(stickerPath).catch(() => false))) {
    NativeUIUtils.showErrorTipDialog(t('snapshot_missing'));
    return 'Snapshot file missing';
  }
  try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_) {}
  try {
    const r: any = await PluginCommAPI.insertSticker(stickerPath);
    console.log('[SNAP] insertSticker result:', JSON.stringify(r));
    if (r?.success) { notifyStickerInserted(); return 'Snapshot restored'; }
  } catch (e) { console.warn('[SNAP] insertSticker error:', e); }
  NativeUIUtils.showErrorTipDialog(t('snapshot_restore_failed'));
  return 'Snapshot restore failed';
}

export async function snapshotDelete(id: string): Promise<string> {
  const records = await loadSnapshots();
  const target = records.find(r => r.id === id);
  if (!target) return 'Snapshot not found';
  try { await FileUtils.deleteFile(target.sticker); } catch (_) {}
  if (target.thumb) { try { await FileUtils.deleteFile(target.thumb); } catch (_) {} }
  await saveSnapshots(records.filter(r => r.id !== id));
  return 'Snapshot deleted';
}

async function clipClear(slot: string): Promise<string> {
  const clips = await loadClips();
  const oldPath = clips[slot];
  if (oldPath) {
    try { await FileUtils.deleteFile(oldPath); } catch (_) {}
    try { await FileUtils.deleteFile(oldPath + '.meta.json'); } catch (_) {}
    try { await FileUtils.deleteFile(oldPath + '.mosaic.json'); } catch (_) {}
  }
  clips[slot] = null;
  await saveClips(clips);
  return `Clip ${slot} cleared`;
}

export interface PaletteLassoInfo {
  elementNums: number[];
  strokeCount: number;
  geometryCount: number;
  avgThickness: number;
  hasMarkerStroke: boolean;
  dominantPenType: number | null;
  dominantPenColor: number | null;
  elements?: any[];
}

const PEN_TYPE_MARKER = 11;

export async function getPaletteLassoInfo(retainElements?: boolean): Promise<PaletteLassoInfo | null> {
  const t0 = Date.now();
  console.log('[PLT/stats] START');
  const elemRes = await PluginCommAPI.getLassoElements() as any;
  if (!elemRes?.success || !Array.isArray(elemRes.result) || elemRes.result.length === 0) {
    console.log(`[PLT/stats] getLassoElements empty ok=${elemRes?.success} n=${elemRes?.result?.length ?? 0} +${Date.now() - t0}ms`);
    return null;
  }
  const els: any[] = elemRes.result;
  console.log(`[PLT/stats] ${els.length} raw elements +${Date.now() - t0}ms`);
  let shouldRecycle = true;
  try {
    const strokes = els.filter((el: any) => el?.type === 0);
    const geoms   = els.filter((el: any) => el?.type === 700);
    const skipped = els.length - strokes.length - geoms.length;
    const elementNums = [...strokes, ...geoms]
      .map((el: any) => el?.numInPage)
      .filter((n: any): n is number => typeof n === 'number');
    console.log(`[PLT/stats] strokes=${strokes.length} geoms=${geoms.length} skip=${skipped} validNums=${elementNums.length}`);
    if (elementNums.length === 0) return null;

    const thicknessVals: number[] = [];
    for (const el of strokes) {
      const thickness = rawElementThickness(el);
      if (thickness != null) thicknessVals.push(thickness);
    }
    for (const el of geoms)   if (typeof el?.geometry?.penWidth === 'number') thicknessVals.push(el.geometry.penWidth);
    const avgThickness = thicknessVals.length
      ? Math.round(thicknessVals.reduce((a, b) => a + b, 0) / thicknessVals.length)
      : 100;

    const penTypeCount = new Map<number, number>();
    const penColorCount = new Map<number, number>();
    for (const el of strokes) {
      const pt = el?.stroke?.penType;
      if (typeof pt === 'number') penTypeCount.set(pt, (penTypeCount.get(pt) ?? 0) + 1);
      const pc = el?.stroke?.penColor;
      if (typeof pc === 'number') penColorCount.set(pc, (penColorCount.get(pc) ?? 0) + 1);
    }
    for (const el of geoms) {
      const pc = el?.geometry?.penColor;
      if (typeof pc === 'number') penColorCount.set(pc, (penColorCount.get(pc) ?? 0) + 1);
    }
    const dominant = (m: Map<number, number>): number | null => {
      let best: number | null = null, bestN = 0;
      for (const [k, v] of m) if (v > bestN) { best = k; bestN = v; }
      return best;
    };

    const info: PaletteLassoInfo = {
      elementNums,
      strokeCount: strokes.length,
      geometryCount: geoms.length,
      avgThickness,
      hasMarkerStroke: penTypeCount.has(PEN_TYPE_MARKER),
      dominantPenType: dominant(penTypeCount),
      dominantPenColor: dominant(penColorCount),
    };
    if (retainElements) {
      info.elements = els;
      shouldRecycle = false;
    }
    console.log(`[PLT/stats] result: s=${info.strokeCount} g=${info.geometryCount} avgT=${info.avgThickness} domPT=${info.dominantPenType} domPC=${info.dominantPenColor} marker=${info.hasMarkerStroke} retain=${!!retainElements} +${Date.now() - t0}ms`);
    return info;
  } finally {
    if (shouldRecycle) {
      for (const el of els) { try { el?.recycle?.(); } catch (_) {} }
      console.log(`[PLT/stats] recycled ${els.length} +${Date.now() - t0}ms`);
    }
  }
}

let modeExitSub: EmitterSubscription | null = null;

export function attachModeListeners(): void {
  modeExitSub?.remove();
  modeExitSub = FloatingToolbarBridge.onToolModeExit(({ toolAction }) => {
    switch (toolAction) {
      case 'insert_text':
      case 'text_recv_nospacing':
      case 'text_recv_paragraph':
        FloatingBubbleBridge.hide();
        break;
      case TOOL_IDS.AI_RELAY:
        stopAiMode();
        break;
    }
  });
}

export function detachModeListeners(): void {
  modeExitSub?.remove();
  modeExitSub = null;
}
