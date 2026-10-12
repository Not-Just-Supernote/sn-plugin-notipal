

import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  View, StyleSheet, StatusBar,
  DeviceEventEmitter, AppState, AppStateStatus,
} from 'react-native';
import { PluginManager, PluginNoteAPI, PluginFileAPI, PluginCommAPI, NativeUIUtils, FileUtils } from 'sn-plugin-lib';

import FloatingToolbarBridge, {
  checkPendingButton,
  peekPendingButton,
  setAppMounted,
  getLastHostButtonEventAt,
  getLastHostButtonHandledAt,
} from './components/FloatingToolbarBridge';
import FloatingBubbleBridge, { PaletteBubbleBridge } from './components/BubbleBridges';
import { loadClips, clipFilledFlags } from './components/ToolPresets';
import {
  ensureInit, getActiveMode, flushPendingTexts, reviveIfNeeded,
  stopMode, stopAiMode, stopTextReceive,
} from './components/BackgroundService';
import { executeAction, attachModeListeners, detachModeListeners, getPaletteLassoInfo, snapshotCreate, snapshotRestore, snapshotDelete } from './components/ToolActions';
import { modifyLiveElements, modifyFileElements, ensureMarkerDirection, isMarkerStroke, MARKER_PT } from './components/ElementOps';
import {
  hostCall, rectFromHost, reselectLasso, prepareInsertedStickerSelection,
  commitSelectionAndRead, pickReselectRect, beginLassoTxn, endLassoTxn, isLassoTxnBusy,
} from './components/LassoTxn';
import { FileLogger } from './components/FileLogger';
import { LassoExtractor } from './components/LassoExtractor';
import { t } from './components/i18n';
import { TOOL_IDS } from './components/ToolIdentifiers';


type AppScreen = 'nativeHelper';



let _lassoExtractGen = 0;
let _lassoExtractStartedAt = 0;
let _lassoDataPrePopulated = false;
export function markLassoDataPrePopulated() { _lassoDataPrePopulated = true; }

function App(): React.JSX.Element {

  const initialPending = peekPendingButton();
  const initialPendingScreen = FloatingToolbarBridge.getPendingScreenSync();
  console.log('[App] init: pendingBtn=', initialPending, 'pendingScreen=', JSON.stringify(initialPendingScreen));
  const [screen, setScreen]               = useState<AppScreen>('nativeHelper');
  const [_resumeTick, setResumeTick]      = useState(0);

  const actionInProgressRef = useRef(false);
  const nativeSendActiveRef = useRef(false);

  const hasPermissionRef = useRef(true);
  const screenRef     = useRef(screen);      screenRef.current     = screen;

  const openMainPanel = useCallback(() => {
    if (getActiveMode()) stopMode();
    stopAiMode();
    flushPendingTexts();
    reviveIfNeeded();
    FloatingToolbarBridge.ackPendingScreen();
    FloatingToolbarBridge.openPanel('config');
  }, []);

  useEffect(() => {
    console.log('[App]: ── mount ──');
    setAppMounted(true);

    attachModeListeners?.();
    ensureInit();

    const runLassoExtraction = () => {
      if (_lassoDataPrePopulated) {
        _lassoDataPrePopulated = false;
        console.log('[LASSO-DBG/App] runLassoExtraction skipped (pre-populated)');
        setTimeout(() => PluginManager.closePluginView(), 200);
        return;
      }
      
      const now = Date.now();
      if (now - _lassoExtractStartedAt < 800) {
        console.log('[LASSO-DBG/App] runLassoExtraction skipped (debounce)');
        return;
      }
      _lassoExtractStartedAt = now;
      
      const gen = ++_lassoExtractGen;
      LassoExtractor.extract().then(extracted => {
        if (gen !== _lassoExtractGen) {
          console.log('[LASSO-DBG/App] stale extraction discarded, gen=' + gen);
          return;
        }
        FloatingToolbarBridge.setLassoData(
          extracted.text,
          JSON.stringify(extracted.imagePaths),
          JSON.stringify(extracted.linkedFiles),
        );
        setTimeout(() => PluginManager.closePluginView(), 200);
      }).catch(() => {
        if (gen !== _lassoExtractGen) return;
        FloatingToolbarBridge.setLassoData('', '[]', '[]');
        setTimeout(() => PluginManager.closePluginView(), 200);
      });
    };

    const runActionFlow = (action: string) => {
      actionInProgressRef.current = true;
      executeAction(action).then(() => {
        setTimeout(() => {
          actionInProgressRef.current = false;
          if (hasPermissionRef.current) {
            FloatingToolbarBridge.showCurrent();
          }
          PluginManager.closePluginView();
        }, 300);
      }).catch(() => {
        actionInProgressRef.current = false;
        setTimeout(() => PluginManager.closePluginView(), 300);
      });
    };

    const fromToolbar    = FloatingToolbarBridge.checkPendingOpenMainSync();
    const toolbarShowing = FloatingToolbarBridge.isShowingSync();
    const pendingScreen  = FloatingToolbarBridge.getPendingScreenSync();
    console.log('[LASSO-DBG/App] mount: fromToolbar=', fromToolbar, 'toolbarShowing=', toolbarShowing, 'pendingScreen=', JSON.stringify(pendingScreen), 'initialScreen=', screen);

    if (fromToolbar) {

      if (getActiveMode()) stopMode();
      flushPendingTexts();
        FloatingToolbarBridge.ackPendingScreen();
      openMainPanel();
      setTimeout(() => FloatingToolbarBridge.ackOpenMain(), 200);
    } else if (pendingScreen) {

      FloatingToolbarBridge.hide();
      flushPendingTexts();
      reviveIfNeeded();
      if (pendingScreen === 'nativeSendHelper') {
        nativeSendActiveRef.current = true;
        setScreen('nativeHelper');
        setTimeout(() => FloatingToolbarBridge.ackPendingScreen(), 200);
        runLassoExtraction();
      } else if (pendingScreen === 'nativeInsertHelper') {

            setScreen('nativeHelper');
        setTimeout(() => FloatingToolbarBridge.ackPendingScreen(), 200);
      } else if (pendingScreen.startsWith('action:')) {

        const action = pendingScreen.slice(7);
        FloatingToolbarBridge.ackPendingScreen();
        setScreen('nativeHelper');
        runActionFlow(action);
      } else {
        FloatingToolbarBridge.ackPendingScreen();
        setScreen('nativeHelper');
      }
    } else if (toolbarShowing) {
      const earlyPending = checkPendingButton();
      if (earlyPending === 999 || initialPending === 999) {
        openMainPanel();
      } else {
        
        
        
        console.log('[App]: toolbar already showing; native lifecycle owns host close');
      }
    } else {
      
      
      const earlyPending = checkPendingButton();
      if (earlyPending === 100 || earlyPending === 300) {
        
      } else if (earlyPending === 999 || initialPending === 999) {
        openMainPanel();
      } else {
        
        
        
        console.log('[App]: source-less PluginView mount → inspect host entry');
        FloatingToolbarBridge.inspectAndFlushHostEntry().then((entry) => {
          if (entry === 'config') {
            console.log('[App]: host entry is config (showType=1) → opening config panel');
            openMainPanel();
            return;
          }
          if (entry === 'flushed') {
            const flushAt = Date.now();
            setTimeout(() => {
              
              
              const replayed = peekPendingButton();
              if (replayed === 999) {
                
                checkPendingButton();
                openMainPanel();
                return;
              }
              
              
              
              
              if (replayed !== null || FloatingToolbarBridge.isShowingSync()) {
                console.log('[App]: flushed entry already owned by button/native toolbar; skip fallback');
                return;
              }
              if (getLastHostButtonHandledAt() >= flushAt) {
                console.log('[App]: flushed entry handled by PluginManager listener; skip fallback');
                return;
              }
              if (getLastHostButtonEventAt() >= flushAt) return;
              FloatingToolbarBridge.reportHostButtonChannel(false);
              console.log('[App]: flushed event never reached JS → fallback toggle toolbar');
              
              
              FloatingToolbarBridge.toggleFromPluginButton();
            }, 1200);
            return;
          }
          
          
          
          
          console.log('[App]: source-less mount has no recoverable host entry; wait for button listener');
        });
      }
    }

    loadClips().then((clipData) => {
      const filled = clipFilledFlags(clipData);
      FloatingToolbarBridge.updateTitleClips(filled);
    });

    const toolTapSub = FloatingToolbarBridge.onToolTap(async ({ toolAction }) => {
      console.log('[BUBBLE-DBG/JS] onToolTap received action=', toolAction);
      if (toolAction === TOOL_IDS.INK_PALETTE) {
        try {
          prefetchedPaletteContext = null;
          if (prefetchedPaletteEls) {
            for (const el of prefetchedPaletteEls) { try { el?.recycle?.(); } catch (_) {} }
          }
          prefetchedPaletteEls = null;
          if (!await prepareInsertedStickerSelection()) return;
          console.log('[PLT/tap] checking lasso type counts…');
          const contextPromise = capturePaletteApplyContext('tap').catch((e) => {
            console.warn('[PLT/tap] context capture failed:', e);
            return null;
          });
          const infoPromise = getPaletteLassoInfo(true).catch((e) => {
            console.warn('[PLT/tap] lasso read failed:', e);
            return null;
          });
          const cntRes = await (PluginCommAPI as any).getLassoElementTypeCounts?.();
          const c = cntRes?.result;
          console.log('[PLT/tap] typeCounts:', JSON.stringify(c));
          const nonInk =
            (c?.titleNum ?? 0) +
            (c?.textLinkNum ?? 0) + (c?.trailLinkNum ?? 0) + (c?.todoLinkNum ?? 0) +
            (c?.normalTextBoxNum ?? 0) + (c?.digestTextBoxNum ?? 0) + (c?.digestTextBoxEditableNum ?? 0) +
            (c?.bitmapNum ?? 0);
          if (nonInk > 0) {
            console.warn(`[PLT/tap] rejected: nonInk=${nonInk}`);
            const rejectedInfo = await infoPromise;
            for (const el of rejectedInfo?.elements ?? []) { try { el?.recycle?.(); } catch (_) {} }
            NativeUIUtils.showErrorTipDialog(t('palette_ink_only'));
            return;
          }
          const info = await infoPromise;
          console.log(`[PLT/tap] info ready, nums=${info?.elementNums?.length ?? 0}`);
          if (info?.elementNums?.length) {
            await snapshotCreate(true, false);
          }
          if (prefetchedPaletteEls) {
            for (const el of prefetchedPaletteEls) { try { el?.recycle?.(); } catch (_) {} }
          }
          prefetchedPaletteEls = info?.elements ?? null;
          prefetchedPaletteContext = await contextPromise;
          if (!prefetchedPaletteContext) {
            console.warn('[PLT/tap] no context snapshot; Apply will read the host live instead');
          }
          FloatingToolbarBridge.showPalettePanel(JSON.stringify(info ?? {}));
        } catch (e) {
          console.error('[PLT/tap] CRASH:', e);
        }
        return;
      }
      const result = await executeAction(toolAction);
      console.log('[App]: tool result:', result);

      if (toolAction === TOOL_IDS.AI_RELAY && result === 'AI receive: ON') {
        if (hasPermissionRef.current) {
          FloatingToolbarBridge.showCurrent();
        }
        setTimeout(() => PluginManager.closePluginView(), 300);
        return;
      }
      if (typeof result === 'string' &&
          (result.startsWith('Saved to clip') || (result.startsWith('Clip') && result.endsWith('cleared')))) {
        const newClips = await loadClips();
        const filled = clipFilledFlags(newClips);
        FloatingToolbarBridge.updateTitleClips(filled);
      }
    });

    const toolModeExitSub = FloatingToolbarBridge.onToolModeExit(({ toolAction }) => {
      console.log('[App]: onToolModeExit:', toolAction);
      
      if (toolAction === TOOL_IDS.AI_RELAY) return;
      stopTextReceive();
    });

    const clipsChangedSub = DeviceEventEmitter.addListener('clipsChanged', async () => {
      const newClips = await loadClips();
      const filled = clipFilledFlags(newClips);
      FloatingToolbarBridge.updateTitleClips(filled);
    });

    const longPressSub = FloatingToolbarBridge.onToolLongPress(async ({ toolId }) => {
      if (toolId.startsWith('clip_')) {
        const slot   = toolId.split('_')[1];
        const result = await executeAction(`clip_clear_${slot}`);
        console.log('[App]: long press clear:', result);
        const newClips = await loadClips();
        const filled = clipFilledFlags(newClips);
        FloatingToolbarBridge.updateTitleClips(filled);
      } else if (toolId === TOOL_IDS.INK_PALETTE) {
        PaletteBubbleBridge.show();
      }
    });

    const openMainSub = FloatingToolbarBridge.onToolbarOpenMain(() => {
      console.log('[App]: onToolbarOpenMain received');
      flushPendingTexts();
      reviveIfNeeded();
      const pendingMain = FloatingToolbarBridge.checkPendingOpenMainSync();
      const pendingScr  = FloatingToolbarBridge.getPendingScreenSync();
      if (pendingMain) {

        openMainPanel();
        FloatingToolbarBridge.ackOpenMain();
      } else if (pendingScr) {
        if (pendingScr === 'nativeSendHelper') {
          FloatingToolbarBridge.hide();
          nativeSendActiveRef.current = true;
          setScreen('nativeHelper');
          FloatingToolbarBridge.ackPendingScreen();
          runLassoExtraction();
        } else if (pendingScr === 'nativeInsertHelper') {
          FloatingToolbarBridge.hide();
                setScreen('nativeHelper');
          FloatingToolbarBridge.ackPendingScreen();
        } else if (pendingScr.startsWith('action:')) {
          const action = pendingScr.slice(7);
          FloatingToolbarBridge.ackPendingScreen();
          FloatingToolbarBridge.hide();
          setScreen('nativeHelper');
          runActionFlow(action);
        } else {
          FloatingToolbarBridge.ackPendingScreen();
          setScreen('nativeHelper');
        }
      } else {

        if (!actionInProgressRef.current && !nativeSendActiveRef.current
            && !FloatingToolbarBridge.isShowingSync()) {
          openMainPanel();
        }
      }
      setResumeTick(n => n + 1);
      setTimeout(() => setResumeTick(n => n + 1), 150);
      setTimeout(() => setResumeTick(n => n + 1), 400);
    });

    const modeSub = DeviceEventEmitter.addListener(
      'insertModeChanged',
      ({ mode }: { mode: string | null }) => {
        console.log('[App]: insertModeChanged →', mode);

        if (!mode) {

          FloatingBubbleBridge.hide();
        }
      },
    );

    const appStateSub = AppState.addEventListener('change', (state: AppStateStatus) => {
      if (state === 'active') {
        reviveIfNeeded();
        const pending = FloatingToolbarBridge.checkPendingOpenMainSync();
        if (pending) {
          FloatingToolbarBridge.hide();
          openMainPanel();
          FloatingToolbarBridge.ackOpenMain();
        }
        setResumeTick(n => n + 1);
      }
    });

    const openConfigForButton = (buttonId: number) => {
      if (buttonId === 999) openMainPanel();
    };

    const pending = checkPendingButton();
    if (pending !== null) openConfigForButton(pending);

    const btnSub = DeviceEventEmitter.addListener('quickToolbarButton', ({ id }) => {
      checkPendingButton();
      openConfigForButton(id);
    });

    const handledInsertRequests = new Set<string>();
    const nativeInsertSub = DeviceEventEmitter.addListener('nativeInsertImage', async (evt: {
      path: string;
      requestId: string;
      fromInsertNext?: boolean;
    }) => {
      const { path, requestId, fromInsertNext } = evt;
      if (handledInsertRequests.has(requestId)) return;
      handledInsertRequests.add(requestId);
      if (handledInsertRequests.size > 64) {
        handledInsertRequests.delete(handledInsertRequests.values().next().value!);
      }
      let success = false;
      try {
        console.log('[INSERT-DBG/App] insert request=', requestId, 'path=', path);
        if (FloatingToolbarBridge.isMosaicBoardVisible()) {
          const source = fromInsertNext ? 'inkling-doc-screenshot' : 'inkling-insert-image';
          success = await FloatingToolbarBridge.enqueueMosaicImage(path, source);
        } else {
          const prepared = await FloatingToolbarBridge.prepareImageForInsertion(path);
          try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_) {}
          const result: any = await PluginNoteAPI.insertImage(prepared);
          success = result?.success === true;
          console.log('[INSERT-DBG/App] insertImage resolved:', result, 'prepared=', prepared);
        }
        if (success) {
          if (fromInsertNext) await FloatingToolbarBridge.deleteQueueFile(path);
          if (FloatingToolbarBridge.consumeReceivedImageDelete(path)) {
            await FileUtils.deleteFile(path).catch(() => {});
          }
        }
      } catch (error) {
        console.error('[INSERT-DBG/App] image insertion failed:', error);
      } finally {
        FloatingToolbarBridge.imageInsertFinished(requestId, path, success);
      }
    });

    const nativeDocLinkSub = DeviceEventEmitter.addListener('nativeInsertDocLink', async (evt: {
      path: string;
      linkName: string;
      requestId: string;
      targetFile: string;
      targetPage: number;
    }) => {
      const { path, linkName: rawLinkName } = evt;

      const dotIdx = rawLinkName.lastIndexOf('.');
      const linkName = dotIdx > 0 ? rawLinkName.substring(0, dotIdx) : rawLinkName;
      console.log('[App] nativeInsertDocLink: path=', path, 'linkName=', linkName);
      let selectionReady = false;
      let targetFile = '';
      let targetPage = -1;
      if (PluginNoteAPI) {
        try {
          const fpRes = await PluginCommAPI.getCurrentFilePath();
          const pgRes = await PluginCommAPI.getCurrentPageNum();
          let pageW = 0, pageH = 0;
          let notePath = '';
          let pageNum: number | null = null;
          if (fpRes?.success && fpRes.result) notePath = fpRes.result;
          if (pgRes?.success && Number.isInteger(pgRes.result)) pageNum = pgRes.result;
          if (evt.targetFile && (notePath !== evt.targetFile || pageNum !== evt.targetPage)) {
            console.log('[App] document link queue ended: target page changed');
            return;
          }
          targetFile = notePath;
          targetPage = pageNum ?? -1;
          if (notePath && pageNum !== null) {
            const layersRes: any = await PluginFileAPI.getLayers(notePath, pageNum);
            const currentLayer = Array.isArray(layersRes?.result)
              ? layersRes.result.find((layer: any) => layer.isCurrentLayer)
              : null;
            const currentLayerId = currentLayer?.layerId ?? currentLayer?.layerNum;
            if (currentLayerId !== undefined && currentLayerId !== 0) {
              console.warn('[App] nativeInsertDocLink blocked on non-main layer:', currentLayerId);
              NativeUIUtils.showErrorTipDialog(t('doc_link_main_only'));
              return;
            }

            const psRes: any = await PluginFileAPI.getPageSize(notePath, pageNum);
            if (psRes?.success && psRes.result?.width > 0 && psRes.result?.height > 0) {
              pageW = psRes.result.width;
              pageH = psRes.result.height;
            }
          }

          if (!(notePath && pageNum !== null && pageW > 0 && pageH > 0)) {
            console.warn('[App] nativeInsertDocLink skipped: actual page size unavailable');
            return;
          }

          
          try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_le) {}

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
                      console.log('[App] docLink collision at top=', top,
                        'with existing [', range.top, ',', range.bottom, '] → skip to', range.bottom + GAP);
                      top = range.bottom + GAP;
                      collision = true;
                      break;
                    }
                  }
                  if (!collision) break;
                }
                if (top + lineH > pageH - 50) {
                  console.warn('[App] docLink: no room on page, using original position');
                  top = Math.round(pageH * 0.15);
                }
              }
            }
          } catch (ce) {
            console.warn('[App] docLink collision scan failed (non-fatal):', ce);
          }

          const ext = path.split('.').pop()?.toLowerCase() || '';
          const isNoteFile = ext === 'note';
          const isDocFile = ['epub', 'pdf', 'cbz', 'doc', 'docx', 'djvu', 'mobi', 'fb2'].includes(ext);
          const linkType = isNoteFile ? 1 : isDocFile ? 2 : 0;

          const destPath = path.replace('/sdcard/', '/storage/emulated/0/');

          const textLink = {
            destPath,
            destPage: -1,
            style: 0,
            linkType,
            rect: { left, top, right: left + Math.round(linkW), bottom: top + lineH },
            fontSize,
            fullText: linkName,
            showText: linkName,
            isItalic: 0,
          };
          const r: any = await (PluginNoteAPI as any).insertTextLink(textLink);
          console.log('[App] insertTextLink result:', r);
          if (!r?.success || r.result !== 0) {
            console.warn('[App] insertTextLink failed:', JSON.stringify(r));
            if (r?.error?.code === 810) {
              NativeUIUtils.showErrorTipDialog(t('doc_link_main_only'));
            }
            return;
          }

          try {
            await PluginCommAPI.reloadFile();
            
            
            const pad = 5;
            const lassoRect = {
              left: textLink.rect.left - pad,
              top: textLink.rect.top - pad,
              right: textLink.rect.right + pad,
              bottom: textLink.rect.bottom + pad,
            };
            console.log('[App] lassoElements rect:', JSON.stringify(lassoRect),
              'textLink.rect:', JSON.stringify(textLink.rect),
              'pageW:', pageW, 'pageH:', pageH);
            const lr: any = await PluginCommAPI.lassoElements(lassoRect);
            console.log('[App] lassoElements result:', JSON.stringify(lr));
            if (lr?.success && lr.result !== false) {
              const shown: any = await (PluginCommAPI as any).setLassoBoxState?.(0);
              selectionReady = shown?.success === true && shown.result !== false;
              console.log('[App] lassoElements succeeded, setLassoBoxState(0) result:', JSON.stringify(shown));
            } else {
              console.warn('[App] lassoElements did not select: success=', lr?.success, 'result=', lr?.result);
            }
          } catch (le) {
            console.warn('[App] lassoElements after insertTextLink failed:', le);
          }
        } catch (e) {
          console.error('[App] insertTextLink error:', e);
        } finally {
          FloatingToolbarBridge.docLinkInsertFinished(evt.requestId, selectionReady, targetFile, targetPage);
        }
      } else {
        FloatingToolbarBridge.docLinkInsertFinished(evt.requestId, false, '', -1);
      }
    });

    
    
    let paletteApplyOwnsPanel = false;
    let prefetchedPaletteEls: any[] | null = null;
    type PaletteApplyContext = {
      filePath: string;
      pageNum: number;
      lassoRect: { left: number; top: number; right: number; bottom: number } | null;
    };
    
    
    let prefetchedPaletteContext: PaletteApplyContext | null = null;


    async function capturePaletteApplyContext(tag: string): Promise<PaletteApplyContext | null> {
      const [fpRes, pgRes, rectRes] = await Promise.all([
        hostCall(PluginCommAPI.getCurrentFilePath() as Promise<any>, `${tag}/file`),
        hostCall(PluginCommAPI.getCurrentPageNum() as Promise<any>, `${tag}/page`),
        hostCall(PluginCommAPI.getLassoRect() as Promise<any>, `${tag}/rect`),
      ]);
      if (!fpRes?.success || typeof fpRes.result !== 'string' || fpRes.result.length === 0 ||
          !pgRes?.success || !Number.isInteger(pgRes.result) || pgRes.result < 0) {
        console.warn(`[PLT/${tag}] context capture incomplete: file=${fpRes?.success} page=${pgRes?.success}`);
        return null;
      }
      const lassoRect = rectRes?.success ? rectFromHost(rectRes.result) : null;
      if (!lassoRect) {
        console.warn(`[PLT/${tag}] context capture has no usable lasso rect`);
        return null;
      }
      console.log(`[PLT/${tag}] context captured page=${pgRes.result} rect=${JSON.stringify(lassoRect)}`);
      return { filePath: fpRes.result, pageNum: pgRes.result, lassoRect };
    }





    function applyPaletteFields(
      targets: any[],
      penColor: number | null,
      thickness: number | null,
      penType: number | null,
    ): void {
      for (const el of targets) {
        if (penColor !== null) {
          if (el.type === 0 && el.stroke) el.stroke.penColor = penColor;
          if (el.type === 700 && el.geometry) el.geometry.penColor = penColor;
        }
        if (thickness !== null) {
          if (el.type === 0) el.thickness = Math.max(10, thickness);
          if (el.type === 700 && el.geometry) el.geometry.penWidth = Math.max(10, thickness);
        }
        if (penType !== null && el.type === 0 && el.stroke) {
          el.stroke.penType = penType;
        }
      }
    }










    async function doPalette(
      elementNums: number[],
      penColor: number | null,
      thickness: number | null,
      penType: number | null,
      tag: string,
      prefetchedEls?: any[],
      hasMarker?: boolean,
      capturedContext?: PaletteApplyContext,
    ): Promise<void> {
      if (elementNums.length === 0) { console.log(`[PLT/${tag}] skip: empty elementNums`); return; }
      const wantColorOrThickness = penColor !== null || thickness !== null;
      const wantPenType = penType !== null;
      if (!wantColorOrThickness && !wantPenType) { console.log(`[PLT/${tag}] skip: nothing to change`); return; }

      if (!beginLassoTxn()) {
        console.warn(`[PLT/${tag}] BLOCKED: another doPalette is running`);
        return;
      }
      let ownedEls: any[] | null = null;
      let prefetchRecycled = false;
      const t0 = Date.now();
      const convertingToMarker = wantPenType && penType === MARKER_PT;
      const prefetchedHasMarker = !!prefetchedEls?.some(isMarkerStroke);
      const hasMarkerSelection = !!hasMarker || prefetchedHasMarker || convertingToMarker;
      console.log(`[PLT/${tag}] START nums=${elementNums.length} color=${penColor} thick=${thickness} pt=${penType} prefetch=${!!prefetchedEls} marker=${hasMarkerSelection}`);

      try {
        let filePath: string;
        let pageNum: number;
        if (capturedContext) {
          filePath = capturedContext.filePath;
          pageNum = capturedContext.pageNum;
          console.log(`[PLT/${tag}] using prefetched context page=${pageNum}`);
        } else {
          const fpRes: any = await hostCall(
            PluginCommAPI.getCurrentFilePath() as Promise<any>,
            `${tag}/file-fallback`,
          );
          const pgRes: any = await hostCall(
            PluginCommAPI.getCurrentPageNum() as Promise<any>,
            `${tag}/page-fallback`,
          );
          if (!fpRes?.success || !pgRes?.success) {
            console.warn(`[PLT/${tag}] ABORT: filePath.ok=${fpRes?.success} pageNum.ok=${pgRes?.success}`);
            return;
          }
          filePath = fpRes.result;
          pageNum = pgRes.result;
        }
        const isNoteFile = /\.note$/i.test(filePath);

        const sel = await commitSelectionAndRead({
          tag, pageNum, filePath, elementNums,
          prefetchedEls, fallbackRect: capturedContext?.lassoRect ?? null, t0,
        });
        prefetchRecycled = true;
        if (!sel) return;
        ownedEls = sel.pageEls;
        const { targets, lassoRectBefore } = sel;

        if (targets.length === 0) {
          
          
          const reselected = lassoRectBefore
            ? await reselectLasso(lassoRectBefore, `${tag}/no-targets`)
            : false;
          try { FloatingToolbarBridge.parkToolbarForLasso(reselected); } catch (_) {}
          return;
        }
        console.log(`[PLT/${tag}] targets=${targets.length} page=${pageNum} +${Date.now() - t0}ms`);

        
        const reselectRect = await pickReselectRect(sel, tag);

        applyPaletteFields(targets, penColor, thickness, penType);
        if (convertingToMarker) {
          for (const el of targets) {
            if (el.type === 0) await ensureMarkerDirection(el);
          }
        }

        
        
        const modRes: any = isNoteFile
          ? await modifyLiveElements(targets, pageNum)
          : await modifyFileElements(filePath, pageNum, targets);
        const modCount = Array.isArray(modRes?.result) ? modRes.result.length : -1;
        console.log(`[PLT/${tag}] live ok=${modRes?.success} modified=${modCount}/${targets.length} err=${modRes?.error?.message ?? ''} +${Date.now() - t0}ms`);
        if (!modRes?.success) {
          console.warn(`[PLT/${tag}] modifyPageElements failed`, modRes?.error);
        } else if (modCount >= 0 && modCount < targets.length) {
          const got = new Set(Array.isArray(modRes.result) ? modRes.result : []);
          const skipped = targets.filter((el: any) => !got.has(el.numInPage)).map((el: any) => el.numInPage);
          console.warn(`[PLT/${tag}] host SKIPPED ${skipped.length} strokes: nums=${JSON.stringify(skipped)}`);
        }

        if (modRes?.success && !isNoteFile) {
          
          const rel: any = await PluginCommAPI.reloadFile();
          console.log(`[PLT/${tag}] reloadFile ok=${rel?.success} +${Date.now() - t0}ms`);
        }

        
        
        
        
        
        
        
        
        if (modRes?.success) {
          const reselected = isNoteFile && reselectRect
            ? await reselectLasso(reselectRect, tag)
            : false;
          if (!isNoteFile) console.log(`[PLT/${tag}] doc: skip reselect after reloadFile`);
          try { FloatingToolbarBridge.parkToolbarForLasso(reselected); } catch (_) {}
        }
        console.log(`[PLT/${tag}] DONE ${Date.now() - t0}ms`);
      } catch (e) {
        console.error(`[PLT/${tag}] CRASH:`, e);
        throw e;
      } finally {
        if (ownedEls) {
          for (const el of ownedEls) { try { el?.recycle?.(); } catch (_) {} }
        } else if (prefetchedEls && !prefetchRecycled) {
          for (const el of prefetchedEls) { try { el?.recycle?.(); } catch (_) {} }
        }
        endLassoTxn();
      }
    }

    const paletteApplySub = DeviceEventEmitter.addListener('paletteApply', async (evt: any) => {
      console.log('[PLT/apply] event:', JSON.stringify(evt));
      let ownsPaletteApply = false;
      try {
        const penColor = evt.penColor != null ? evt.penColor : null;
        const thickness = evt.thickness != null ? evt.thickness : null;
        const penType = evt.penType != null ? evt.penType : null;
        let elementNums: number[] = [];
        try {
          elementNums = evt.elementNums ? JSON.parse(evt.elementNums) : [];
        } catch (pe) {
          console.error('[PLT/apply] elementNums parse FAIL:', pe, 'raw=', evt.elementNums);
          return;
        }
        const hasMarker = evt.hasMarkerStroke === true;
        const prefetched = prefetchedPaletteEls;
        prefetchedPaletteEls = null;
        const capturedContext = prefetchedPaletteContext;
        prefetchedPaletteContext = null;
        console.log(`[PLT/apply] nums=${elementNums.length} c=${penColor} t=${thickness} pt=${penType} marker=${hasMarker} prefetch=${!!prefetched}`);
        
        
        
        
        if (isLassoTxnBusy()) {
          console.warn('[PLT/apply] BLOCKED: another palette transaction is running');
          for (const el of prefetched ?? []) { try { el?.recycle?.(); } catch (_) {} }
          
          
          
          
          if (!paletteApplyOwnsPanel) FloatingToolbarBridge.finishPaletteApply();
          return;
        }
        ownsPaletteApply = true;
        paletteApplyOwnsPanel = true;
        
        if (!capturedContext) console.warn('[PLT/apply] no context snapshot, reading host live');
        await doPalette(elementNums, penColor, thickness, penType, 'apply', prefetched ?? undefined, hasMarker, capturedContext ?? undefined);
      } catch (e) {
        console.error('[PLT/apply] CRASH:', e);
      } finally {
        
        
        
        if (ownsPaletteApply) {
          paletteApplyOwnsPanel = false;
          FloatingToolbarBridge.finishPaletteApply();
          FloatingToolbarBridge.paletteApplyFinished();
        }
      }
    });

    
    const paletteSnapshotSub = DeviceEventEmitter.addListener('paletteSnapshot', async (evt: any) => {
      console.log('[SNAP] event:', JSON.stringify(evt));
      try {
        if (evt?.op === 'create') {
          console.log('[SNAP] create:', await snapshotCreate());
        } else if (evt?.op === 'restore' && evt.sticker) {
          await snapshotCreate(true);
          console.log('[SNAP] restore:', await snapshotRestore(evt.sticker));
        } else if (evt?.op === 'delete' && evt.id) {
          console.log('[SNAP] delete:', await snapshotDelete(evt.id));
        }
      } catch (e) {
        console.error('[SNAP] error:', e);
      } finally {
        
        FloatingToolbarBridge.paletteSnapshotsChanged();
      }
    });

    const COLOR_MAP: Record<string, number> = { black: 0x00, darkGray: 0x9D, lightGray: 0xC9, ghost: 0xFE };
    let cachedPaletteNums: number[] = [];
    let paletteBubbleInFlight = false;

    const paletteBubbleSlotSub = PaletteBubbleBridge.onSlotTap(async ({ color, thickness, penType }) => {
      if (paletteBubbleInFlight) return;
      paletteBubbleInFlight = true;
      let bubblePaletteGuard = false;
      console.log(`[PLT/bubble] slotTap color=${color} thick=${thickness} pt=${penType}`);
      try {
        if (!await prepareInsertedStickerSelection()) return;
        const contextPromise = capturePaletteApplyContext('bubble').catch((e) => {
          console.warn('[PLT/bubble] context capture failed:', e);
          return null;
        });
        const infoPromise = getPaletteLassoInfo(true).catch((e) => {
          console.warn('[PLT/bubble] lasso read failed:', e);
          return null;
        });
        const cntRes = await (PluginCommAPI as any).getLassoElementTypeCounts?.();
        const c = cntRes?.result;
        console.log('[PLT/bubble] typeCounts:', JSON.stringify(c));
        const nonInk =
          (c?.titleNum ?? 0) +
          (c?.textLinkNum ?? 0) + (c?.trailLinkNum ?? 0) + (c?.todoLinkNum ?? 0) +
          (c?.normalTextBoxNum ?? 0) + (c?.digestTextBoxNum ?? 0) + (c?.digestTextBoxEditableNum ?? 0) +
          (c?.bitmapNum ?? 0);
        if (nonInk > 0) {
          console.warn(`[PLT/bubble] rejected: nonInk=${nonInk}`);
          const rejectedInfo = await infoPromise;
          for (const el of rejectedInfo?.elements ?? []) { try { el?.recycle?.(); } catch (_) {} }
          NativeUIUtils.showErrorTipDialog(t('palette_ink_only'));
          return;
        }
        const inkCount = (c?.trailNum ?? 0) + (c?.geometryNum ?? 0) +
          (c?.straightLineNum ?? 0) + (c?.curveLineNum ?? 0) + (c?.circleNum ?? 0) +
          (c?.ellipseNum ?? 0) + (c?.polygonNum ?? 0);
        const info = await infoPromise;
        if (!info?.elementNums && inkCount > 0) {
          console.warn('[PLT/bubble] ABORT: lasso has ink counts but getLassoElements returned no elements');
          return;
        }
        const fromCache = !info?.elementNums;
        const elementNums: number[] = info?.elementNums ?? cachedPaletteNums;
        if (elementNums.length === 0) {
          console.log('[PLT/bubble] no selection (info=null, cache empty)');
          NativeUIUtils.showErrorTipDialog(t('palette_need_lasso'));
          return;
        }
        console.log(`[PLT/bubble] nums=${elementNums.length} fromCache=${fromCache} prevCache=${cachedPaletteNums.length}`);
        cachedPaletteNums = elementNums;
        const penColor = COLOR_MAP[color] ?? null;
        if (inkCount > 0 || cachedPaletteNums.length > 0) {
          await snapshotCreate(true, false);
        }
        const capturedContext = await contextPromise;
        if (!capturedContext) console.warn('[PLT/bubble] no context snapshot, reading host live');
        if (isLassoTxnBusy()) {
          console.warn('[PLT/bubble] BLOCKED: another palette transaction is running');
          for (const el of info?.elements ?? []) { try { el?.recycle?.(); } catch (_) {} }
          return;
        }
        
        
        
        
        FloatingToolbarBridge.beginPaletteApply();
        bubblePaletteGuard = true;
        await doPalette(
          elementNums,
          penColor,
          thickness,
          penType,
          'bubble',
          info?.elements,
          info?.hasMarkerStroke === true,
          capturedContext ?? undefined,
        );
      } catch (e) {
        console.error('[PLT/bubble] CRASH:', e);
      } finally {
        if (bubblePaletteGuard) FloatingToolbarBridge.paletteApplyFinished();
        paletteBubbleInFlight = false;
      }
    });

    const titleClipSub = FloatingToolbarBridge.onTitleClipTap(async ({ slot }) => {
      const result = await executeAction(`clip_paste_${slot}`);
      if (typeof result === 'string' &&
          (result.startsWith('Saved to clip') || (result.startsWith('Clip') && result.endsWith('cleared')))) {
        const newClips = await loadClips();
        const filled = clipFilledFlags(newClips);
        FloatingToolbarBridge.updateTitleClips(filled);
      }
    });

    const titleClipLongSub = (FloatingToolbarBridge as any).onTitleClipLongPress
      ? (FloatingToolbarBridge as any).onTitleClipLongPress(async ({ slot }: { slot: string }) => {
          await executeAction(`clip_clear_${slot}`);
          const newClips = await loadClips();
          const filled = clipFilledFlags(newClips);
          FloatingToolbarBridge.updateTitleClips(filled);
        })
      : { remove() {} };

    const nativePanelCloseSub = FloatingToolbarBridge.onNativePanelClose(async ({ panel }) => {
      if (panel === 'send') nativeSendActiveRef.current = false;
    });

    return () => {
      setAppMounted(false);
      toolTapSub.remove();
      toolModeExitSub.remove();
      longPressSub.remove();
      clipsChangedSub.remove();
      openMainSub.remove();
      modeSub.remove();
      btnSub.remove();
      appStateSub.remove();
      nativeInsertSub.remove();
      nativeDocLinkSub.remove();
      paletteApplySub.remove();
      paletteSnapshotSub.remove();
      paletteBubbleSlotSub.remove();
      nativePanelCloseSub.remove();
      titleClipSub.remove();
      titleClipLongSub.remove();
      detachModeListeners();
    };
  }, []);


  console.log('[App] render: screen=', screen);
  return (
    <View style={st.container}>
      <StatusBar barStyle="dark-content" />
      <View key={`ct-${_resumeTick}`} style={st.centerWrapper}>

        {screen === 'nativeHelper' && (
          <View style={{ flex: 1, backgroundColor: 'transparent' }} />
        )}

      </View>

    </View>
  );
}


const st = StyleSheet.create({
  container:     { flex: 1, backgroundColor: 'transparent' },
  centerWrapper: { flex: 1, justifyContent: 'center', alignItems: 'center' },

});

export default App;
