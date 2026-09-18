import { PluginCommAPI, PluginFileAPI, PluginNoteAPI, PluginManager, NativeUIUtils, FileUtils, PointUtils } from 'sn-plugin-lib';
import { NativeModules, EmitterSubscription, Dimensions } from 'react-native';
import { loadClips, saveClips, loadSnapshots, saveSnapshots } from './ToolPresets';
import { t } from './i18n';
import { toggleMode, handleAiSend, getLastMode, stopAiMode } from './BackgroundService';
import { getRNFS } from './rnfs';
import FloatingToolbarBridge from './FloatingToolbarBridge';
import FloatingBubbleBridge from './BubbleBridges';
import { layerIdOf, moveLiveElements, saveCurrentPage } from './ElementOps';
import { normalizeToolAction, TOOL_IDS } from './ToolIdentifiers';

const { FloatingToolbar } = NativeModules;

let _filePath: string | null = null;
let _pageNum: number | null = null;

let _imageQueue: string[] = [];
let _docLinkQueue: string[] = [];
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

let _noteType: number | null = null;

async function ctx(): Promise<boolean> {
  try {
    const fp = await PluginCommAPI.getCurrentFilePath();
    if (fp.success && fp.result) _filePath = fp.result;
    const pg = await PluginCommAPI.getCurrentPageNum();
    if (pg.success && pg.result !== undefined) _pageNum = pg.result;
    if (_filePath) {
      const nt = await PluginFileAPI.getNoteType(_filePath);
      _noteType = nt.success ? (nt.result as number) : null;
    }
    return !!_filePath && _pageNum !== null;
  } catch { return false; }
}

function isRecognitionNote(): boolean {
  return _noteType === 1;
}

async function _insertDocLinkDirect(docPath: string): Promise<void> {
  const rawName = docPath.split('/').pop() ?? 'link';
  const dotIdx = rawName.lastIndexOf('.');
  const linkName = dotIdx > 0 ? rawName.substring(0, dotIdx) : rawName;

  const screen = Dimensions.get('window');
  let pageW = screen.width > screen.height ? 1872 : 1404;
  let pageH = screen.width > screen.height ? 1404 : 1872;
  let notePath = '';
  let pageNum = 0;
  try {
    const fpRes: any = await PluginCommAPI.getCurrentFilePath();
    const pgRes: any = await PluginCommAPI.getCurrentPageNum();
    if (fpRes?.success && fpRes.result) notePath = fpRes.result;
    if (pgRes?.success && pgRes.result !== undefined) pageNum = pgRes.result;
    if (notePath && pageNum !== undefined) {
      const layersRes: any = await PluginFileAPI.getLayers(notePath, pageNum);
      const currentLayer = Array.isArray(layersRes?.result)
        ? layersRes.result.find((layer: any) => layer.isCurrentLayer)
        : null;
      const currentLayerId = currentLayer?.layerId ?? currentLayer?.layerNum;
      if (currentLayerId !== undefined && currentLayerId !== 0) {
        console.warn('[ToolActions] insertDocLink blocked on non-main layer:', currentLayerId);
        NativeUIUtils.showErrorTipDialog(t('doc_link_main_only'));
        return;
      }

      const psRes: any = await PluginFileAPI.getPageSize(notePath, pageNum);
      if (psRes?.success && psRes.result) {
        pageW = psRes.result.width;
        pageH = psRes.result.height;
      }
    }
  } catch (_) {}

  try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_) {}

  const linkW = Math.min(linkName.length * 30 + 40, pageW * 0.6);
  const left = Math.round((pageW - linkW) / 2);
  let top = Math.round(pageH * 0.15);
  const fontSize = 49;
  const lineH = fontSize + 10;

  try {
    if (notePath) {
      const elRes: any = await PluginFileAPI.getElements(pageNum, notePath);
      if (elRes?.success && Array.isArray(elRes.result)) {
        const occupied: { top: number; bottom: number }[] = [];
        for (const el of elRes.result) {
          try {
            const elType = el.type;

            if (typeof elType === 'number' && elType >= 500 && elType <= 502) {
              const rect = el.textBox?.textRect;
              if (rect && typeof rect.top === 'number' && typeof rect.bottom === 'number') {
                occupied.push({ top: rect.top, bottom: rect.bottom });
              }
            }

            if (typeof elType === 'number' && elType === 600) {
              const lk = el.link;
              if (lk && typeof lk.Y === 'number' && typeof lk.height === 'number') {
                occupied.push({ top: lk.Y, bottom: lk.Y + lk.height });
              }
            }
          } finally {
            try { el.recycle?.(); } catch (_) {}
          }
        }
        occupied.sort((a, b) => a.top - b.top);

        const GAP = 10;
        for (let iter = 0; iter < 50; iter++) {
          let collision = false;
          for (const range of occupied) {
            if (top < range.bottom && (top + lineH) > range.top) {
              console.log('[ToolActions] docLink collision at top=', top,
                'with existing [', range.top, ',', range.bottom, '] → skip to', range.bottom + GAP);
              top = range.bottom + GAP;
              collision = true;
              break;
            }
          }
          if (!collision) break;
        }

        if (top + lineH > pageH - 50) {
          console.warn('[ToolActions] docLink: no room on page, using original position');
          top = Math.round(pageH * 0.15);
        }
      }
    }
  } catch (e) {
    console.warn('[ToolActions] docLink collision scan failed (non-fatal):', e);
  }

  const ext = docPath.split('.').pop()?.toLowerCase() || '';
  const isNoteFile = ext === 'note';
  const isDocFile = ['epub', 'pdf', 'cbz', 'doc', 'docx', 'djvu', 'mobi', 'fb2'].includes(ext);
  const destPath = docPath.replace('/sdcard/', '/storage/emulated/0/');

  const textLink = {
    destPath,
    destPage: -1,
    style: 0,
    linkType: isNoteFile ? 1 : isDocFile ? 2 : 0,
    rect: { left, top, right: left + Math.round(linkW), bottom: top + lineH },
    fontSize,
    fullText: linkName,
    showText: linkName,
    isItalic: 0,
  };
  const insertRes: any = await (PluginNoteAPI as any).insertTextLink(textLink);
  console.log('[ToolActions] insertTextLink result:', insertRes);
  if (!insertRes?.success || insertRes.result !== 0) {
    console.warn('[ToolActions] insertTextLink failed:', insertRes);
    if (insertRes?.error?.code === 810) {
      NativeUIUtils.showErrorTipDialog(t('doc_link_main_only'));
    }
    return;
  }

  try {
    await new Promise(r => setTimeout(r, 500));

    const pad = 5;
    const lassoRect = {
      left: textLink.rect.left - pad,
      top: textLink.rect.top - pad,
      right: textLink.rect.right + pad,
      bottom: textLink.rect.bottom + pad,
    };
    console.log('[ToolActions] lassoElements rect:', JSON.stringify(lassoRect),
      'textLink.rect:', JSON.stringify(textLink.rect), 'pageW:', pageW, 'pageH:', pageH);
    const lr: any = await PluginCommAPI.lassoElements(lassoRect);
    console.log('[ToolActions] lassoElements result:', JSON.stringify(lr));
    if (lr?.success && lr.result !== false) {
      const shown: any = await (PluginCommAPI as any).setLassoBoxState?.(0);
      console.log('[ToolActions] setLassoBoxState(0) result:', JSON.stringify(shown));
    } else {
      console.warn('[ToolActions] lassoElements did not select: success=', lr?.success, 'result=', lr?.result);
    }
  } catch (e) {
    console.warn('[ToolActions] lassoElements after insertTextLink failed:', e);
  }
}

export async function executeAction(action: string): Promise<string> {
  const rawAction = action;
  action = normalizeToolAction(action);
  console.log('[ToolActions]:', rawAction, '=>', action);

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
    handleAiSend().catch(e => console.error('[ToolActions]: lasso_ai error:', e));
    return 'Sending to AI...';
  }

  if (action === TOOL_IDS.SMART_LASSO) {
    
    
    if (isMosaicBoardVisible()) {
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

  if (action === TOOL_IDS.INK_PALETTE) {
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

    if (_docLinkQueue.length === 0) {
      _docLinkQueue = FloatingToolbarBridge.drainDocLinkQueue();
    }
    if (_docLinkQueue.length > 0) {
      const docPath = _docLinkQueue.shift()!;
      console.log('[QUEUE-DBG/TS] docLink queue pop:', docPath, 'remaining:', _docLinkQueue.length);
      try { await _insertDocLinkDirect(docPath); } catch (_) {}
      return 'Doc link inserted from queue';
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
    if (_imageQueue.length === 0) {
      _imageQueue = FloatingToolbarBridge.drainImageQueue();
    }
    if (_imageQueue.length > 0) {
      const imgPath = _imageQueue.shift()!;
      console.log('[QUEUE-DBG/TS] image queue pop:', imgPath, 'remaining:', _imageQueue.length);
      
      if (isMosaicBoardVisible()) {
        const ok = await FloatingToolbarBridge.enqueueMosaicImage(imgPath, 'inkling-insert-image');
        return ok ? 'Image queued to Mosaic' : 'Image queue to Mosaic failed';
      }
      try {

        try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_) {}
        await (PluginNoteAPI as any).insertImage(imgPath);
      } catch (_) {}
      return 'Image inserted from queue';
    }
    FloatingToolbarBridge.showImagePanel();
    return 'Image panel opened';
  }

  if (action === 'insert_doc_screenshot') {
    FloatingToolbarBridge.handleDocScreenshot();
    return 'Doc screenshot: delegated to native';
  }

  
  if (!action.startsWith('clip_save_') && !isMosaicBoardVisible()) {
    if (!await ctx()) return 'No file context';
  }

  try {
    if (action === 'layer_prev') {
      if (isRecognitionNote()) { NativeUIUtils.showErrorTipDialog(t('layer_err_recognition')); return ''; }
      if (await hasLassoSelection()) return moveLassoElementsLayer('up');
      return await layerPrev();
    }
    if (action === 'layer_next') {
      if (isRecognitionNote()) { NativeUIUtils.showErrorTipDialog(t('layer_err_recognition')); return ''; }
      if (await hasLassoSelection()) return moveLassoElementsLayer('down');
      return await layerNext();
    }

    if (action.startsWith('clip_paste_')) {
      const slot = action.charAt(action.length - 1);
      return await clipSmartAction(slot);
    }
    if (action.startsWith('clip_save_clear_')) {
      const slot = action.charAt(action.length - 1);
      
      if (isMosaicBoardVisible()) return await mosaicClipSave(slot, { deleteAfter: true });
      const saved = await clipSave(slot);
      if (!saved.startsWith('Saved to clip')) return saved;
      const removed: any = await PluginCommAPI.deleteLassoElements();
      console.log('[CLIP-DBG] delete selected after save:', JSON.stringify(removed));
      return removed?.success === false ? `${saved}; clear selection failed` : `${saved}; selected strokes cleared`;
    }
    if (action.startsWith('clip_save_')) {
      const slot = action.charAt(action.length - 1);
      if (isMosaicBoardVisible()) return await mosaicClipSave(slot);
      return await clipSave(slot);
    }
    if (action.startsWith('clip_clear_')) {
      const slot = action.charAt(action.length - 1);
      return await clipClear(slot);
    }

    return `Unknown: ${action}`;
  } catch (e) {
    console.error('[ToolActions]:', e);
    return `Error: ${String(e)}`;
  }
}

const LAYER_MOVABLE_TYPES = new Set([0, 200, 500, 700]);

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

async function moveLassoElementsLayer(direction: 'up' | 'down'): Promise<string> {
  if (!_filePath || _pageNum === null) return 'No context';

  try {
    const cntRes = await (PluginCommAPI as any).getLassoElementTypeCounts?.();
    const c = cntRes?.result;
    const hasTitle = (c?.titleNum ?? 0) > 0;
    const hasLink = ((c?.textLinkNum ?? 0) + (c?.trailLinkNum ?? 0) + (c?.todoLinkNum ?? 0)) > 0;
    const hasTextBox = ((c?.normalTextBoxNum ?? 0) + (c?.digestTextBoxNum ?? 0) + (c?.digestTextBoxEditableNum ?? 0)) > 0;
    const hasImage = (c?.bitmapNum ?? 0) > 0;
    if (hasTitle || hasLink || hasTextBox || hasImage) {
      NativeUIUtils.showErrorTipDialog(t('layer_err_unmovable'));
      return 'Unmovable element in selection';
    }
  } catch (_) {}

  
  
  const elemRes = await PluginCommAPI.getLassoElements() as any;
  if (!elemRes?.success || !elemRes.result?.length) return 'No lasso elements';
  const lassoElements = elemRes.result as any[];
  const movable = lassoElements.filter((e: any) => LAYER_MOVABLE_TYPES.has(e.type));
  if (movable.length === 0) {
    NativeUIUtils.showErrorTipDialog(t('layer_err_unmovable'));
    return 'No movable elements in selection';
  }

  if (!await saveCurrentPage('move-layer')) return 'Save current note failed';

  const lr = await PluginFileAPI.getLayers(_filePath, _pageNum) as any;
  if (!lr?.success || !lr.result) return 'Get layers failed';
  const userLayerIds = (lr.result as any[])
    .filter((l: any) => l?.isDeleted !== true && l?.isBackgroundLayer !== true)
    .map((l: any) => (l.layerId !== undefined ? l.layerId : l.layerNum) as number)
    .filter((id: number) => Number.isInteger(id) && id >= 0)
    .sort((a: number, b: number) => a - b);
  if (userLayerIds.length === 0) return 'No layers';
  console.log('[Layer] active layers=', JSON.stringify(userLayerIds),
    'raw=', JSON.stringify((lr.result as any[]).map((l: any) => ({
      id: layerIdOf(l), deleted: l?.isDeleted === true,
    }))));

  if (direction === 'up') {
    const maxLayer = Math.max(...movable.map((e: any) => e.layerNum ?? 0));
    const maxIdx = userLayerIds.indexOf(maxLayer);
    if (maxIdx === userLayerIds.length - 1) {
      NativeUIUtils.showErrorTipDialog(t('layer_err_need_new'));
      return 'Need more layers to move up';
    }
  }

  const targetLayerMap = new Map<number, number>();
  for (const el of movable) {
    const curLayer = el.layerNum ?? 0;
    const curIdx = userLayerIds.indexOf(curLayer);
    if (curIdx < 0) continue;
    const targetIdx = direction === 'up' ? curIdx + 1 : curIdx - 1;
    if (targetIdx < 0 || targetIdx >= userLayerIds.length) continue;
    targetLayerMap.set(el.numInPage, userLayerIds[targetIdx]);
  }
  if (targetLayerMap.size === 0) {
    const msg = direction === 'up' ? t('layer_err_at_top') : t('layer_err_at_bottom');
    NativeUIUtils.showErrorTipDialog(msg);
    return direction === 'up' ? 'Already at top layer' : 'Already at bottom layer';
  }

  const fullRes = await PluginFileAPI.getElements(_pageNum, _filePath) as any;
  if (!fullRes?.success || !fullRes.result) return 'Get page elements failed';
  const allEls = fullRes.result as any[];

  try {
    const toMove = allEls.filter((e: any) => targetLayerMap.has(e.numInPage));
    if (toMove.length === 0) return 'No matching page elements';

    const insertByTarget = new Map<number, any[]>();
    const deleteBySource = new Map<number, number[]>();
    for (const el of toMove) {
      const src = el.layerNum ?? 0;
      const dst = targetLayerMap.get(el.numInPage)!;
      el.layerNum = dst;
      const ins = insertByTarget.get(dst);
      if (ins) ins.push(el); else insertByTarget.set(dst, [el]);
      const del = deleteBySource.get(src);
      if (del) del.push(el.numInPage); else deleteBySource.set(src, [el.numInPage]);
    }

    console.log('[ToolActions] moveLayer live insert/delete', toMove.length, 'elements');
    const moved = await moveLiveElements(_pageNum, insertByTarget, deleteBySource);
    if (!moved.ok) {
      NativeUIUtils.showErrorTipDialog(moved.error ?? 'Move layer failed');
      return `Move failed: ${moved.error ?? 'unknown'}`;
    }
    return `Moved ${toMove.length} element(s) ${direction}`;
  } finally {
    for (const el of allEls) { try { el?.recycle?.(); } catch (_) {} }
    for (const el of lassoElements) { try { el?.recycle?.(); } catch (_) {} }
  }
}

function normalizeLayerName(layer: any, id: number): void {
  if (typeof layer.name !== 'string' || !layer.name.trim()) {
    layer.name = id === 0 ? 'Main Layer' : `Layer ${id}`;
  }
  if (typeof layer.isVisible !== 'boolean') layer.isVisible = true;
  layer.layerId = id;
}

async function switchCurrentLayer(targetId: number, layers: any[]): Promise<boolean> {
  
  const toSend = layers.filter((l: any) => {
    const id = layerIdOf(l);
    return id >= 0 && id <= 3;
  });
  for (const l of toSend) {
    const id = layerIdOf(l);
    normalizeLayerName(l, id);
    l.isCurrentLayer = id === targetId;
  }
  console.log('[Layer] modifyLayers →', toSend.map((l: any) => ({
    id: l.layerId, name: l.name, cur: l.isCurrentLayer, vis: l.isVisible,
  })));
  const r: any = await PluginFileAPI.modifyLayers(_filePath!, _pageNum!, toSend);
  console.log('[Layer] modifyLayers ok=', r?.success, 'err=', r?.error, 'result=', r?.result);
  if (!r?.success) {
    if (r?.error?.message) NativeUIUtils.showErrorTipDialog(r.error.message);
    return false;
  }
  
  
  return true;
}


async function layerPrev(): Promise<string> {
  return mergeWithNeighbor('up');
}


async function layerNext(): Promise<string> {
  return mergeWithNeighbor('down');
}

async function mergeWithNeighbor(dir: 'up' | 'down'): Promise<string> {
  if (!_filePath || _pageNum === null) return 'No context';
  const lr = await PluginFileAPI.getLayers(_filePath, _pageNum);
  if (!lr.success || !lr.result) return 'Get layers failed';

  const allLayers = (lr.result as any[])
    .filter((l: any) => l?.isDeleted !== true && l?.isBackgroundLayer !== true)
    .slice()
    .sort((a: any, b: any) => layerIdOf(a) - layerIdOf(b));
  const current = allLayers.find((l: any) => l.isCurrentLayer) || allLayers[allLayers.length - 1];
  const currentId = layerIdOf(current);

  const neighbor = dir === 'up'
    ? allLayers.find((l: any) => layerIdOf(l) > currentId)
    : allLayers.filter((l: any) => layerIdOf(l) >= 0 && layerIdOf(l) < currentId).pop();
  if (!neighbor) {
    NativeUIUtils.showErrorTipDialog(t('layer_no_neighbor'));
    return dir === 'up' ? 'No layer above' : 'No layer below';
  }
  const neighborId = layerIdOf(neighbor);

  const msg = t('layer_merge_confirm').replace('%s', neighbor.name || `Layer ${neighborId}`);
  const confirmed = await NativeUIUtils.showRattaDialog(
    msg, t('btn_cancel'), t('btn_confirm'), false
  ).catch(() => false);
  if (!confirmed) return 'Merge cancelled';

  
  return dir === 'up'
    ? mergeLayer(neighborId, currentId, _filePath, _pageNum)
    : mergeLayer(currentId, neighborId, _filePath, _pageNum);
}


async function mergeLayer(
  sourceLayerId: number,
  targetLayerId: number,
  filePath: string,
  pageNum: number,
): Promise<string> {
  try {
    
    
    
    
    if (!await saveCurrentPage('merge-layer')) {
      NativeUIUtils.showErrorTipDialog(t('layer_merge_failed'));
      return 'Save current note failed';
    }

    const elRes = await PluginFileAPI.getElements(pageNum, filePath) as any;
    if (!elRes?.success || !elRes.result) {
      NativeUIUtils.showErrorTipDialog(t('layer_merge_failed'));
      return 'Get elements failed';
    }
    const elements = elRes.result as any[];

    const sourceElements = elements
      .filter((e: any) => e.layerNum === sourceLayerId)
      .map((e: any) => ({ ...e, layerNum: targetLayerId }));

    if (sourceElements.length > 0) {
      
      
      
      const moved = await moveLiveElements(
        pageNum,
        new Map([[targetLayerId, sourceElements]]),
        new Map([[sourceLayerId, sourceElements.map((e: any) => e.numInPage)]]),
      );
      console.log('[Layer] move elements n=', sourceElements.length, 'ok=', moved.ok, 'error=', moved.error ?? '');
      if (!moved.ok) {
        NativeUIUtils.showErrorTipDialog(t('layer_merge_failed'));
        return `Move elements failed: ${moved.error ?? 'unknown'}`;
      }
    }

    const delRes = await PluginFileAPI.deleteLayers(filePath, pageNum, [sourceLayerId]) as any;
    console.log('[Layer] deleteLayers id=', sourceLayerId, 'ok=', delRes?.success);
    if (!delRes?.success) {
      NativeUIUtils.showErrorTipDialog(t('layer_merge_failed'));
      return 'Delete layer failed';
    }

    return `Merged layer ${sourceLayerId} into ${targetLayerId}`;
  } catch (e: any) {
    console.error('[Layer] mergeLayer error:', e);
    NativeUIUtils.showErrorTipDialog(t('layer_merge_failed'));
    return 'Merge failed';
  }
}







const MOSAIC_CLIP_DIR = '/sdcard/EXPORT/mosaic';
const MOSAIC_LASSO_FILE = `${MOSAIC_CLIP_DIR}/clip_lasso.json`;
const MOSAIC_PASTE_FILE = `${MOSAIC_CLIP_DIR}/paste_strokes.json`;

const SIDE_MARGIN = 100;          
const MOSAIC_PRESSURE = 1000;     
const MOSAIC_THICK_FACTOR = 20;   
const MOSAIC_THICK_MIN = 100;
const MOSAIC_THICK_MAX = 900;

function isMosaicBoardVisible(): boolean {
  try { return (FloatingToolbar as any)?.isMosaicBoardVisible?.() === true; } catch { return false; }
}



function notifyStickerInserted(): void {
  try { (FloatingToolbar as any)?.notifyStickerInserted?.(); } catch (_) {}
}


function mosaicPenToSdk(objType: number): number {
  switch (objType) {
    case 18: return 10; 
    case 0:  return 1;  
    case 14: return 15; 
    case 15: return 15; 
    case 17: return 11; 
    default: return 1;
  }
}

function sdkPenToMosaic(penType: number): number {
  switch (penType) {
    case 10: return 18;
    case 1:  return 0;
    case 6:  return 14;
    case 14:
    case 15: return 14; 
    case 11: return 17;
    default: return 0;
  }
}

function mosaicPageSize(deviceType: number): { width: number; height: number } {
  
  return deviceType === 5 ? { width: 1920, height: 2560 } : { width: 1404, height: 1872 };
}

async function readMosaicStrokesFile(path: string): Promise<any[] | null> {
  const RNFS = getRNFS();
  if (!RNFS) return null;
  try {
    if (!(await RNFS.exists(path))) return null;
    const json = JSON.parse(await RNFS.readFile(path, 'utf8'));
    const strokes = Array.isArray(json?.strokes) ? json.strokes : null;
    return strokes && strokes.length > 0 ? strokes : null;
  } catch (e) {
    console.warn('[MOSAIC-CLIP] read strokes failed', path, e);
    return null;
  }
}


async function writeMosaicSidecar(stickerPath: string, strokes: any[]): Promise<void> {
  const RNFS = getRNFS();
  if (!RNFS) return;
  try {
    await RNFS.writeFile(`${stickerPath}.mosaic.json`, JSON.stringify({ v: 1, strokes }), 'utf8');
  } catch (e) {
    console.warn('[MOSAIC-CLIP] write sidecar failed', e);
  }
}


async function elementsToMosaicStrokes(
  elements: any[], pageSize: { width: number; height: number },
): Promise<any[]> {
  const out: any[] = [];
  for (const el of elements) {
    const stroke = el?.stroke;
    if (!stroke?.points) continue;
    try {
      const n = await stroke.points.size();
      if (!n || n < 2) continue;
      const emrPts: any[] = await stroke.points.getRange(0, n);
      let prs: any[] = [];
      try { prs = await stroke.pressures.getRange(0, n); } catch (_) {}
      const pts: number[] = [];
      for (let i = 0; i < emrPts.length; i++) {
        const a = PointUtils.emrPoint2Android(emrPts[i], pageSize);
        const pr = typeof prs[i] === 'number' ? Math.max(0, Math.min(1, prs[i] / MOSAIC_PRESSURE)) : 1;
        pts.push(a.x, a.y, pr);
      }
      if (pts.length < 6) continue;
      out.push({
        penStyle: sdkPenToMosaic(stroke.penType ?? 1),
        width: Math.max(1, Math.round((el.thickness ?? MOSAIC_PRESSURE) / MOSAIC_THICK_FACTOR)),
        pts,
      });
    } catch (e) {
      console.warn('[MOSAIC-CLIP] element read failed', e);
    }
  }
  return out;
}



function requestMosaicClearSelection(deleteAfter: boolean): void {
  try { (FloatingToolbar as any)?.requestMosaicClearSelection?.(deleteAfter); }
  catch (e) { console.warn('[MOSAIC-CLIP] clear-selection broadcast failed', e); }
}


async function mosaicStrokesToElements(
  strokes: any[], pageSize: { width: number; height: number },
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
  const fit = Math.min(1, (pageSize.width - 2 * SIDE_MARGIN) / bw, (pageSize.height - 2 * SIDE_MARGIN) / bh);

  const elements: any[] = [];
  for (const s of strokes) {
    const createRes: any = await (PluginCommAPI as any).createElement(0);
    if (!createRes?.success || !createRes.result) { console.warn('[MOSAIC-CLIP] createElement failed'); return null; }
    const el: any = createRes.result;
    const emrPoints: any[] = []; const pressures: number[] = [];
    const p = s.pts ?? [];
    for (let i = 0; i + 2 < p.length; i += 3) {
      const pageX = SIDE_MARGIN + (p[i] - minX) * fit;
      const pageY = SIDE_MARGIN + (p[i + 1] - minY) * fit;
      emrPoints.push(PointUtils.androidPoint2Emr({ x: Math.round(pageX), y: Math.round(pageY) }, pageSize));
      pressures.push(Math.max(1, Math.round((p[i + 2] ?? 1) * MOSAIC_PRESSURE)));
    }
    if (emrPoints.length < 2 || !el.stroke) continue;
    el.thickness = Math.max(MOSAIC_THICK_MIN, Math.min(MOSAIC_THICK_MAX, Math.round((s.width ?? 2) * fit * MOSAIC_THICK_FACTOR)));
    el.stroke.penColor = 0;
    el.stroke.penType = mosaicPenToSdk(s.penStyle ?? 0);
    const okPts = await el.stroke.points.setRange(0, emrPoints.length - 1, emrPoints);
    const okPrs = await el.stroke.pressures.setRange(0, pressures.length - 1, pressures);
    if (!okPts || !okPrs) { console.warn('[MOSAIC-CLIP] setRange failed'); return null; }
    elements.push(el);
  }
  return elements;
}


export async function extractMosaicLassoText(
  onProgress?: (stage: 'recognizing' | 'done') => void,
): Promise<string | null> {
  const strokes = await readMosaicStrokesFile(MOSAIC_LASSO_FILE);
  if (!strokes) return null;
  const canWrite = await FloatingToolbarBridge.requestFileWritePermission();
  if (!canWrite) {
    try { NativeUIUtils.showErrorTipDialog(t('perm_required')); } catch (_) {}
    return null;
  }
  const deviceType = await PluginManager.getDeviceType();
  const pageSize = mosaicPageSize(deviceType);
  const elements = await mosaicStrokesToElements(strokes, pageSize);
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
  const strokes = await readMosaicStrokesFile(MOSAIC_LASSO_FILE);
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

  const deviceType = await PluginManager.getDeviceType();
  const pageSize = mosaicPageSize(deviceType);

  const elements = await mosaicStrokesToElements(strokes, pageSize);
  if (elements === null) return `Save clip ${slot} failed`;
  if (elements.length === 0) return `Save clip ${slot} failed (no strokes)`;

  const convertRes: any = await NativeModules.NativePluginAPI.convertElement2Sticker({
    machineType: deviceType, elements, stickerPath: path,
  });
  if (!convertRes?.success || convertRes.result === false) {
    NativeUIUtils.showErrorTipDialog(t('clip_save_failed'));
    return `Save clip ${slot} failed`;
  }

  
  
  await writeMosaicSidecar(path, strokes);
  if (oldPath) {
    try { await FileUtils.deleteFile(oldPath); } catch (_) {}
    try { await FileUtils.deleteFile(oldPath + '.mosaic.json'); } catch (_) {}
    try { await FileUtils.deleteFile(oldPath + '.meta.json'); } catch (_) {}
  }
  const clips = await loadClips(); clips[slot] = path; await saveClips(clips);
  requestMosaicClearSelection(opts.deleteAfter === true);
  return `Saved to clip ${slot}`;
}


async function mosaicClipPaste(slot: string): Promise<string> {
  const clips = await loadClips();
  const stored = clips[slot];
  if (!stored) { NativeUIUtils.showErrorTipDialog(t('clip_empty_hint')); return 'Clip empty'; }
  const stickerPath = stored.startsWith('/') ? stored : `${STICKER_DIR}/${stored}`;
  const RNFS = getRNFS();
  if (!RNFS) return `Paste clip ${slot} failed (no fs)`;

  const strokes = await readMosaicStrokesFile(`${stickerPath}.mosaic.json`);
  if (!strokes) {
    NativeUIUtils.showErrorTipDialog(t('clip_err_read_failed'));
    return `Paste clip ${slot} failed (no mosaic sidecar)`;
  }
  try {
    await RNFS.mkdir(MOSAIC_CLIP_DIR).catch(() => {});
    await RNFS.writeFile(MOSAIC_PASTE_FILE, JSON.stringify({ v: 1, strokes }), 'utf8');
  } catch (e) {
    console.warn('[MOSAIC-CLIP] write paste file failed', e);
    return `Paste clip ${slot} failed`;
  }
  try { (FloatingToolbar as any)?.requestMosaicPasteStrokes?.(); } catch (e) { console.warn('[MOSAIC-CLIP] broadcast failed', e); }
  return `Pasted clip ${slot} to Mosaic`;
}

async function clipSmartAction(slot: string): Promise<string> {
  if (_clipBusy) return 'Clip busy';
  _clipBusy = true;
  try {
    
    if (isMosaicBoardVisible()) {
      const hasSel = (await readMosaicStrokesFile(MOSAIC_LASSO_FILE)) != null;
      return hasSel ? await mosaicClipSave(slot) : await mosaicClipPaste(slot);
    }

    let hasLasso = false;
    try {
      const lassoRes = await PluginCommAPI.getLassoRect();
      hasLasso = lassoRes.success && lassoRes.result != null;
    } catch {}

    if (hasLasso) {
      return await clipSave(slot);
    } else {

      try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_) {}
      return await clipPasteSticker(slot);
    }
  } finally {
    _clipBusy = false;
  }
}

async function clipSave(slot: string): Promise<string> {
  await ensureStickerDir();

  try {
    const cntRes = await (PluginCommAPI as any).getLassoElementTypeCounts?.();
    const c = cntRes?.result;
    const hasTitle = (c?.titleNum ?? 0) > 0;
    const hasLink = ((c?.textLinkNum ?? 0) + (c?.trailLinkNum ?? 0) + (c?.todoLinkNum ?? 0)) > 0;
    const hasTextBox = ((c?.normalTextBoxNum ?? 0) + (c?.digestTextBoxNum ?? 0) + (c?.digestTextBoxEditableNum ?? 0)) > 0;
    const hasImage = (c?.bitmapNum ?? 0) > 0;
    if (hasTitle) { NativeUIUtils.showErrorTipDialog(t('clip_err_title')); return 'Unsupported: title'; }
    if (hasLink)  { NativeUIUtils.showErrorTipDialog(t('clip_err_link'));  return 'Unsupported: link'; }
    if (hasTextBox) { NativeUIUtils.showErrorTipDialog(t('clip_err_textbox')); return 'Unsupported: textbox'; }
    if (hasImage) { NativeUIUtils.showErrorTipDialog(t('clip_err_image')); return 'Unsupported: image'; }
  } catch (_) {}

  const existingClips = await loadClips();
  const oldPath = existingClips[slot];
  const name = `quickbar_clip_${slot}_${Date.now()}.sticker`;
  const path = `${STICKER_DIR}/${name}`;

  console.log('[ToolActions]: clipSave slot=', slot, 'path=', path);

  if (oldPath && await getRNFS()?.exists(oldPath).catch(() => false)) {
    const confirmed = await NativeUIUtils.showRattaDialog(
      t('clip_overwrite'), t('btn_cancel'), t('btn_confirm'), false
    ).catch(() => true);
    if (!confirmed) return `Clip ${slot} overwrite cancelled`;
  }

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

  
  
  
  console.log('[CLIP-DBG] ===== clipSave v2 (no saveStickerByLasso) =====');
  const elemRes = await PluginCommAPI.getLassoElements() as any;
  if (!elemRes?.success || !elemRes.result || !Array.isArray(elemRes.result) || elemRes.result.length === 0) {
    console.warn('[CLIP-DBG] getLassoElements failed or empty:', elemRes?.error);
    
    
    if (await lassoContainsPicture()) {
      NativeUIUtils.showErrorTipDialog(t('clip_err_image'));
      return 'Unsupported: image';
    }
    NativeUIUtils.showErrorTipDialog(t('clip_err_read_failed'));
    return `Save clip ${slot} failed (no elements)`;
  }

  const deviceType = await PluginManager.getDeviceType();
  let fileType: number | null = null;
  try {
    const fpRes: any = await PluginCommAPI.getCurrentFilePath();
    if (fpRes?.success && fpRes.result) {
      const ftRes: any = await (PluginFileAPI as any).getFileMachineType(fpRes.result);
      if (ftRes?.success && typeof ftRes.result === 'number') fileType = ftRes.result;
    }
  } catch (_) {}

  const machineType = pickStickerMachineType(elemRes.result, fileType, deviceType);
  console.log('[CLIP-DBG] deviceType=', deviceType, 'fileType=', fileType, 'machineType=', machineType);
  console.log('[CLIP-DBG] convertElement2Sticker elements=', elemRes.result.length, 'machineType=', machineType);
  for (const [i, el] of (elemRes.result as any[]).entries()) {
    let pointCount: number | null = null;
    try {
      const points = el?.stroke?.points;
      if (points && typeof points.size === 'function') pointCount = await points.size();
    } catch (_) {}
    console.log('[CLIP-DBG] element before convert', JSON.stringify({
      index: i,
      type: el?.type,
      numInPage: el?.numInPage,
      layerNum: el?.layerNum,
      pageNum: el?.pageNum,
      maxX: el?.maxX,
      maxY: el?.maxY,
      pointCount,
      stroke: el?.stroke ? {
        penType: el.stroke.penType,
        penWidth: el.stroke.penWidth,
        penColor: el.stroke.penColor,
      } : null,
    }));
  }
  const convertRes: any = await NativeModules.NativePluginAPI.convertElement2Sticker({
    machineType,
    elements: elemRes.result,
    stickerPath: path,
  });
  console.log('[CLIP-DBG] convertElement2Sticker result:', JSON.stringify(convertRes));

  if (!convertRes?.success || convertRes.result === false) {
    console.warn('[CLIP-DBG] convertElement2Sticker FAILED');
    NativeUIUtils.showErrorTipDialog(t('clip_save_failed'));
    return `Save clip ${slot} failed`;
  }

  
  try {
    const fpRes: any = await PluginCommAPI.getCurrentFilePath();
    const pgRes: any = await PluginCommAPI.getCurrentPageNum();
    if (fpRes?.success && fpRes.result && pgRes?.success && pgRes.result != null) {
      const psRes: any = await PluginFileAPI.getPageSize(fpRes.result, pgRes.result);
      const pageSize = psRes?.result;
      if (pageSize?.width && pageSize?.height) {
        const mstrokes = await elementsToMosaicStrokes(elemRes.result, pageSize);
        if (mstrokes.length > 0) await writeMosaicSidecar(path, mstrokes);
      }
    }
  } catch (e) { console.warn('[MOSAIC-CLIP] note-origin sidecar failed', e); }

  return await commitSave();
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


function pickStickerMachineType(elements: any[], fileType: number | null, deviceType: number): number {
  const A5X2 = 5;
  let big = 0, small = 0;
  for (const el of elements) {
    const mx = el?.maxX;
    if (typeof mx !== 'number' || mx <= 0) continue;
    
    
    if (mx >= 16000 && mx !== 21098) big++; else small++;
  }
  console.log('[CLIP-DBG] pickStickerMachineType big=', big, 'small=', small,
    'maxXs=', JSON.stringify(elements.map((el: any) => el?.maxX)));
  if (big === 0 && small === 0) {
    
    return fileType != null ? fileType : deviceType;
  }
  if (big > 0 && small > 0) {
    console.warn('[CLIP-DBG] mixed maxX families in one lasso; sizes of minority family will drift');
  }
  if (big >= small) return A5X2;
  
  if (fileType != null && fileType !== A5X2) return fileType;
  if (deviceType !== A5X2) return deviceType;
  return 4; 
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
        notifyStickerInserted();
        return `Pasted clip ${slot}`;
      }
    } catch (e) { console.warn('[CLIP-DBG] insertSticker error:', e); }

    try {
      const r2 = await PluginCommAPI.insertSticker(path + '.sticker');
      console.log('[CLIP-DBG] insertSticker(.sticker) result:', JSON.stringify(r2));
      if (r2.success) {
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
    const rect = el.picture?.rect;
    if (!rect) continue;
    const outPath = `${STICKER_DIR}/tmp_pic_${ts}_${i}.png`;
    try {
      await NativeModules.InklingImageUtil.cropRegion(
        previewPath, pageW, pageH,
        rect.left, rect.top, rect.right, rect.bottom, outPath,
      );
      el.picture.picturePath = outPath;
      tmpFiles.push(outPath);
      console.log('[SNAP] picture repaired:', JSON.stringify(rect), '→', outPath);
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
    let fileType: number | null = null;
    try {
      const ftRes: any = await (PluginFileAPI as any).getFileMachineType(notePath);
      if (ftRes?.success && typeof ftRes.result === 'number') fileType = ftRes.result;
    } catch (_) {}
    const machineType = pickStickerMachineType(elements, fileType, deviceType);

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
      '(raw=', elements.length, ') machineType=', machineType, 'path=', stickerPath);
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
    for (const el of strokes) if (typeof el?.thickness === 'number') thicknessVals.push(el.thickness);
    for (const el of geoms)   if (typeof el?.geometry?.penWidth === 'number') thicknessVals.push(el.geometry.penWidth);
    const avgThickness = thicknessVals.length
      ? Math.round(thicknessVals.reduce((a, b) => a + b, 0) / thicknessVals.length)
      : 100;

    let resolvedDefaultPenType: number | null = null;
    if (strokes.some((el: any) => el?.stroke?.penType === 1)) {
      try {
        const pi = await PluginCommAPI.getPenInfo();
        if (pi?.success && pi.result != null) resolvedDefaultPenType = pi.result.type ?? null;
        console.log(`[PLT/stats] resolvedDefaultPenType=${resolvedDefaultPenType}`);
      } catch (e) {
        console.warn('[PLT/stats] getPenInfo failed:', e);
      }
    }

    const penTypeCount = new Map<number, number>();
    const penColorCount = new Map<number, number>();
    for (const el of strokes) {
      let pt = el?.stroke?.penType;
      if (pt === 1 && resolvedDefaultPenType != null) pt = resolvedDefaultPenType;
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
    switch (normalizeToolAction(toolAction)) {
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
