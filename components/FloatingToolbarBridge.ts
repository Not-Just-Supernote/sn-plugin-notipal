

import { NativeModules, NativeEventEmitter } from 'react-native';

function getFT(): any {
  return NativeModules.FloatingToolbar;
}

export const ENABLE_DEBUG: boolean =
  getFT()?.getConstants?.()?.ENABLE_DEBUG ??
  getFT()?.ENABLE_DEBUG ??
  
  
  Boolean(typeof globalThis !== 'undefined' && (globalThis as any).__DEV__);


let pendingButtonId: number | null = null;
let appMounted = false;
let lastHostButtonEventAt = 0;
let lastHostButtonId: number | null = null;
let lastHostButtonHandledAt = 0;

export function setPendingButton(id: number | null): void {
  pendingButtonId = id;
}

export function checkPendingButton(): number | null {
  const value = pendingButtonId;
  pendingButtonId = null;
  return value;
}

export function peekPendingButton(): number | null {
  return pendingButtonId;
}

export function setAppMounted(value: boolean): void {
  appMounted = value;
}

export function isAppMounted(): boolean {
  return appMounted;
}

export function markHostButtonEvent(id?: unknown): void {
  lastHostButtonEventAt = Date.now();
  const numericId = typeof id === 'number' ? id : Number(id);
  lastHostButtonId = Number.isFinite(numericId) ? numericId : null;
}

export function getLastHostButtonEventAt(): number {
  return lastHostButtonEventAt;
}

export function getLastHostButtonId(): number | null {
  return lastHostButtonId;
}


export function markHostButtonHandled(): void {
  lastHostButtonHandledAt = Date.now();
}

export function getLastHostButtonHandledAt(): number {
  return lastHostButtonHandledAt;
}

export interface ToolTapEvent {
  toolId: string;
  toolAction: string;
  toolName: string;
}


export interface MosaicBoardState {
  visible: boolean;
  surface?: string;
  noteRef?: string | null;
}

let _emitter: NativeEventEmitter | null = null;

function getEmitter(): NativeEventEmitter | null {
  const mod = getFT();
  if (!mod) return null;
  if (!_emitter) {
    try {
      _emitter = new NativeEventEmitter(mod);
    } catch (e) {
      if (ENABLE_DEBUG) console.warn('[FloatingToolbarBridge]: NativeEventEmitter unavailable:', e);
      return null;
    }
  }
  return _emitter;
}

const FloatingToolbarBridge = {

  get isAvailable(): boolean {
    const mod = getFT();
    return !!mod && typeof mod.toggleFromPluginButton === 'function';
  },

  showCurrent(): void {
    try {
      getFT()?.showCurrent();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showCurrent failed:', e);
    }
  },

  isSessionActiveSync(): boolean {
    return getFT()?.isSessionActiveSync?.() ?? false;
  },

  toggleFromPluginButton(mode?: 'note_notipal' | 'doc_notipal'): boolean {
    try {
      const mod = getFT();
      if (!mod || typeof mod.toggleFromPluginButton !== 'function') {
        if (ENABLE_DEBUG) console.warn('[FloatingToolbarBridge]: native module unavailable for toggleFromPluginButton');
        return false;
      }
      mod.toggleFromPluginButton(mode ?? null);
      return true;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: toggleFromPluginButton failed:', e);
      return false;
    }
  },

  
  requestClosePluginView(): boolean {
    try {
      const mod = getFT();
      if (!mod || typeof mod.requestClosePluginView !== 'function') return false;
      mod.requestClosePluginView();
      return true;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestClosePluginView failed:', e);
      return false;
    }
  },

  async inspectAndFlushHostEntry(): Promise<'none' | 'config' | 'flushed'> {
    try {
      const result = await getFT()?.inspectAndFlushHostEntry();
      return result === 'config' || result === 'flushed' ? result : 'none';
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: inspectAndFlushHostEntry failed:', e);
      return 'none';
    }
  },

  reportHostButtonChannel(working: boolean): void {
    try {
      getFT()?.reportHostButtonChannel(working);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: reportHostButtonChannel failed:', e);
    }
  },

  reportHostButtonRaw(payload: string): void {
    try { getFT()?.reportHostButtonRaw?.(payload); } catch (_) {}
  },

  hide(): void {
    try {
      getFT()?.hide();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: hide failed:', e);
    }
  },

  restoreToolbar(): void {
    try {
      getFT()?.restoreToolbar();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: restoreToolbar failed:', e);
    }
  },

  beginPaletteApply(): void {
    try {
      getFT()?.beginPaletteApply?.();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: beginPaletteApply failed:', e);
    }
  },

  async hasPendingDocSticker(): Promise<boolean> {
    return (await getFT()?.hasPendingDocSticker?.()) === true;
  },

  clearPendingDocSticker(): void {
    try { getFT()?.clearPendingDocSticker?.(); } catch (_) {}
  },

  finishPaletteApply(): void {
    try {
      getFT()?.finishPaletteApply?.();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: finishPaletteApply failed:', e);
    }
  },

  
  paletteApplyFinished(): void {
    try {
      getFT()?.paletteApplyFinished();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: paletteApplyFinished failed:', e);
    }
  },

  parkToolbarForLasso(holdForSelection: boolean): void {
    try {
      getFT()?.parkToolbarForLasso(holdForSelection);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: parkToolbarForLasso failed:', e);
    }
  },

  paletteSnapshotsChanged(): void {
    try {
      getFT()?.paletteSnapshotsChanged();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: paletteSnapshotsChanged failed:', e);
    }
  },

  isShowingSync(): boolean {
    try {
      return getFT()?.isShowingSync() ?? false;
    } catch { return false; }
  },

  checkPendingOpenMainSync(): boolean {
    try {
      return getFT()?.checkPendingOpenMainSync() ?? false;
    } catch { return false; }
  },

  ackOpenMain(): void {
    try { getFT()?.ackOpenMain(); } catch (_) {}
  },

  getPendingScreenSync(): string {
    try { return getFT()?.getPendingScreenSync() ?? ''; } catch { return ''; }
  },

  ackPendingScreen(): void {
    try { getFT()?.ackPendingScreen(); } catch (_) {}
  },

  async deleteQueueFile(path: string): Promise<boolean> {
    try { return await getFT()?.deleteQueueFile(path) ?? false; } catch { return false; }
  },

  async prepareImageForInsertion(path: string): Promise<string> {
    const prepared = await getFT()?.prepareImageForInsertion(path);
    if (!prepared) throw new Error('Image preparation returned no path');
    return prepared;
  },

  imageInsertFinished(requestId: string, path: string, success: boolean): void {
    getFT()?.imageInsertFinished(requestId, path, success);
  },

  consumeReceivedImageDelete(path: string): boolean {
    return getFT()?.consumeReceivedImageDelete(path) === true;
  },

  setLocale(loc: 'zh' | 'en'): void {
    try { getFT()?.setLocale(loc); } catch (_) {}
  },

  setPluginVersion(versionName: string, versionCode: string): void {
    try { getFT()?.setPluginVersion?.(versionName, versionCode); } catch (_) {}
  },

  openPanel(screen: string): void {
    try { getFT()?.openPanel(screen); } catch (_) {}
  },

  openPluginSettingsAfterFileWritePermissionDenied(): void {
    try {
      getFT()?.openPluginSettingsAfterFileWritePermissionDenied?.();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: openPluginSettingsAfterFileWritePermissionDenied failed:', e);
    }
  },

  async requestFileReadPermission(): Promise<boolean> {
    try {
      return await getFT()?.requestFileReadPermission?.() ?? false;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestFileReadPermission failed:', e);
      return false;
    }
  },

  async requestFileWritePermission(): Promise<boolean> {
    try {
      return await getFT()?.requestFileWritePermission?.() ?? false;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestFileWritePermission failed:', e);
      return false;
    }
  },

  async requestFileDeletePermission(): Promise<boolean> {
    try {
      return await getFT()?.requestFileDeletePermission?.() ?? false;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestFileDeletePermission failed:', e);
      return false;
    }
  },

  async requestInternetPermission(): Promise<boolean> {
    try {
      return await getFT()?.requestInternetPermission?.() ?? false;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestInternetPermission failed:', e);
      return false;
    }
  },

  setLassoData(text: string, imagePathsJson: string, linkedFilesJson?: string): void {
    try {
      getFT()?.setLassoData(text, imagePathsJson, linkedFilesJson ?? '[]');
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: setLassoData failed:', e);
    }
  },

  drainReceivedDeletes(): string[] {
    try {
      const json = getFT()?.drainReceivedDeletes();
      return json ? JSON.parse(json) : [];
    } catch (e) {
      return [];
    }
  },

  docLinkInsertFinished(requestId: string, selectionReady: boolean, targetFile: string, targetPage: number): void {
    getFT()?.docLinkInsertFinished(requestId, selectionReady, targetFile, targetPage);
  },

  showImagePanel(): void {
    try {
      getFT()?.showImagePanel();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showImagePanel failed:', e);
    }
  },

  showDocLinkPanel(currentFilePath?: string | null): void {
    try {
      getFT()?.showDocLinkPanel(currentFilePath ?? null);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showDocLinkPanel failed:', e);
    }
  },

  showPalettePanel(infoJson: string): void {
    try {
      getFT()?.showPalettePanel(infoJson);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showPalettePanel failed:', e);
    }
  },

  handleDocScreenshot(): void {
    try {
      getFT()?.handleDocScreenshot?.();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: handleDocScreenshot failed:', e);
    }
  },

  
  isMosaicBoardVisible(): boolean {
    try { return getFT()?.isMosaicBoardVisible?.() === true; } catch { return false; }
  },

  
  getMosaicSurface(): string {
    try { return getFT()?.getMosaicSurface() ?? ''; } catch { return ''; }
  },

  
  isDocumentMode(): boolean {
    try { return getFT()?.isDocumentMode() === true; } catch { return false; }
  },

  
  isMosaicBoardSurface(): boolean {
    try { return getFT()?.isMosaicBoardSurface() === true; } catch { return false; }
  },

  getMosaicNoteRef(): string | null {
    try {
      const value = getFT()?.getMosaicNoteRef?.();
      return typeof value === 'string' && value.length > 0 ? value : null;
    } catch { return null; }
  },

  
  async enqueueMosaicImage(path: string, source: string): Promise<boolean> {
    try { return (await getFT()?.enqueueMosaicImage?.(path, source)) === true; }
    catch (e) { console.warn('[FloatingToolbarBridge]: enqueueMosaicImage failed:', e); return false; }
  },

  
  requestMosaicTextCard(text: string, anchorScreenX: number, anchorScreenY: number): void {
    try { getFT()?.requestMosaicTextCard?.(text, Math.round(anchorScreenX), Math.round(anchorScreenY)); }
    catch (e) { console.warn('[FloatingToolbarBridge]: requestMosaicTextCard failed:', e); }
  },

  toggleScreenshotBubble(mode?: 'note_notipal' | 'doc_notipal'): void {
    try {
      getFT()?.toggleScreenshotBubble?.(mode ?? null);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: toggleScreenshotBubble failed:', e);
    }
  },

  showDocTextSendPanel(text: string): void {
    try {
      getFT()?.showDocTextSendPanel(text);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showDocTextSendPanel failed:', e);
    }
  },

  showSendPanelFromBubble(): void {
    try {
      getFT()?.showSendPanelFromBubble();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showSendPanelFromBubble failed:', e);
    }
  },

  openSendPanelClipboardSync(): void {
    try {
      getFT()?.openSendPanelClipboardSync();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: openSendPanelClipboardSync failed:', e);
    }
  },

  showLassoScreenshotPanelFromBubble(): void {
    try {
      getFT()?.showLassoScreenshotPanelFromBubble();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showLassoScreenshotPanelFromBubble failed:', e);
    }
  },

  showLassoScreenshotPanelForSendFromBubble(): void {
    try {
      getFT()?.showLassoScreenshotPanelForSendFromBubble();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showLassoScreenshotPanelForSendFromBubble failed:', e);
    }
  },

  showSmartLassoCapture(): void {
    try {
      getFT()?.showSmartLassoCapture?.();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showSmartLassoCapture failed:', e);
    }
  },
  onToolModeExit(
    cb: (e: { toolId: string; toolAction: string }) => void
  ): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolModeExit', cb);
  },

  setActiveModes(modeIds: string[]): void {
    try {
      getFT()?.setActiveModes(JSON.stringify(modeIds));
    } catch (_) {}
  },

  onToolTap(callback: (event: ToolTapEvent) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolTap', callback);
  },

  onToolLongPress(callback: (event: { toolId: string; toolName: string }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolLongPress', callback);
  },

  onToolbarOpenMain(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolbarOpenMain', () => callback());
  },

  onNativePanelClose(
    callback: (data: {
      panel: string;
      cameFromBubble: boolean;
      restoreNoteBubbles?: boolean;
      screenshotBbox?: { left: number; top: number; right: number; bottom: number };
      detailId?: string;
    }) => void
  ): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onNativePanelClose', callback);
  },

  
  onNoteForegroundChanged(callback: (data: { inNote: boolean }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onNoteForegroundChanged', callback);
  },

  onHostAppModeChanged(callback: (data: { document: boolean; reason?: string }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onHostAppModeChanged', callback);
  },

  onMosaicBoardVisible(callback: (data: MosaicBoardState) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onMosaicBoardVisible', callback);
  },

  onDestroyAll(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolbarDestroyAll', () => callback());
  },

  updateTitleClips(filled: boolean[]): void {
    try { getFT()?.updateTitleClips(JSON.stringify(filled)); } catch (_) {}
  },

  onTitleClipTap(callback: (event: { slot: string }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onTitleClipTap', callback);
  },

  onTitleClipLongPress(callback: (event: { slot: string }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onTitleClipLongPress', callback);
  },

  showPenLassoOverlay(): void {
    try { getFT()?.showPenLassoOverlay(); } catch (e) {
      console.warn('[FloatingToolbarBridge]: showPenLassoOverlay failed:', e);
    }
  },

  beginPenLassoSelection(requestId: string): void {
    const ft = getFT();
    if (!ft?.beginPenLassoSelection) throw new Error('Pen selection bridge unavailable');
    ft.beginPenLassoSelection(requestId);
  },

  async finishPenLassoSelection(requestId: string): Promise<boolean> {
    return (await getFT()?.finishPenLassoSelection?.(requestId)) === true;
  },

  cancelPenLassoSelection(requestId: string): void {
    getFT()?.cancelPenLassoSelection?.(requestId);
  },

  onPenLassoBbox(callback: (event: { left: number; top: number; right: number; bottom: number; requestId?: string }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onPenLassoBbox', callback);
  },

  onPenLassoCancel(callback: (event?: { requestId?: string }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onPenLassoCancel', callback);
  },

};

export default FloatingToolbarBridge;
