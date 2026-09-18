

import { NativeModules, NativeEventEmitter, DeviceEventEmitter } from 'react-native';
import { PluginCommAPI, PluginFileAPI, NativeUIUtils } from 'sn-plugin-lib';
import { TextInserter, InsertMode, TextSource, presetPageInfo } from './TextInserter';
import { FileLogger } from './FileLogger';
import LocalSendBridge, { TextReceivedInfo } from './LocalSendBridge';
import { LassoExtractor } from './LassoExtractor';
import FloatingBubbleBridge, { AiBubbleBridge } from './BubbleBridges';

import { loadAiBubbleActions, resolveAiBubbleActions } from './ToolPresets';
import { sendFormulaQuery, processRelayBlocks } from './FormulaService';
import { t } from './i18n';
import FloatingToolbarBridge from './FloatingToolbarBridge';
import { TOOL_IDS } from './ToolIdentifiers';

const { AIRelayModule } = NativeModules;



const AI_REPLY_GAP_PX = 52;
const LAST_MODE_STORE_KEY = 96;

const { FloatingToolbar } = NativeModules;

async function saveLastMode(mode: InsertMode): Promise<void> {
  try { await FloatingToolbar?.savePreset(LAST_MODE_STORE_KEY, mode); } catch (_) {}
}

async function loadLastMode(): Promise<InsertMode> {
  try {
    const v = await FloatingToolbar?.loadPreset(LAST_MODE_STORE_KEY);
    if (v === 'nospacing' || v === 'paragraph') return v;
  } catch (_) {}
  return 'nospacing';
}

let _textInserter: TextInserter | null = null;
let _broadcastTextSub: { remove(): void } | null = null;
let _lsTextSub: { remove(): void } | null = null;
let _initialized = false;
let _activeMode: InsertMode | null = null;
let _lastMode: InsertMode = 'nospacing';
let _aiWaiting = false;
let _aiTimeoutRef: ReturnType<typeof setTimeout> | null = null;

let _lastAiSendAt = 0;

let _aiSendInFlight = false;

let _lastAiSendText = '';
const AI_SAME_TEXT_DEBOUNCE_MS = 8000;

let _aiLongPressSub: { remove(): void } | null = null;

let _blocksSub: { remove(): void } | null = null;
let _replaceSub: { remove(): void } | null = null;

let _nativePanelCloseSub: { remove(): void } | null = null;

let _noteForegroundSub: { remove(): void } | null = null;
let _aiCollapseSub: { remove(): void } | null = null;

let _cachedAiBubbleActionIds: string[] = [];

let _aiActive = false;
let _relayEnabled = false;

let _aiPositionLocked = false;




let _pendingCards: { text: string; source: TextSource }[] = [];




let _mosaicTextActive = false;
let _mosaicTextBuffer: string[] = [];

let _switchVersion = 0;

let _localSendStarted = false;
let _localSendStartPromise: Promise<boolean> | null = null;


type InboxItem = { id: string; title: string; preview: string; source: string; time: number; pinned?: boolean };
let _relayInboxSub: { remove(): void } | null = null;
let _inboxItems: InboxItem[] = [];


let _aiSessionStart = 0;





let _autoOpenDetail = true;

let _dismissedCapsuleIds = new Set<string>();

let _capsuleOpenCount: Record<string, number> = {};

let _autoOpenedIds = new Set<string>();

let _currentCapsuleDetailId: string | null = null;

let _lastPermissionTipAt = 0;

async function _ensurePermanentTextReceiverPermissions(): Promise<boolean> {
  const readGranted = await FloatingToolbarBridge.requestFileReadPermission();
  const writeGranted = readGranted
    ? await FloatingToolbarBridge.requestFileWritePermission()
    : false;
  const ready = readGranted && writeGranted;
  console.log('[BackgroundService]: text receiver permanent permissions',
    'read=', readGranted, 'write=', writeGranted, 'ready=', ready);
  if (!ready && Date.now() - _lastPermissionTipAt > 1500) {
    _lastPermissionTipAt = Date.now();
    NativeUIUtils.showErrorTipDialog(t('initial_permissions_required'));
  }
  return ready;
}

async function _startTextInserter(
  inserter: TextInserter,
  mode: InsertMode,
  preservePosition = false,
): Promise<boolean> {
  if (!await _ensureTextReceiverRuntime()) {
    _activeMode = null;
    _pushActiveModes();
    emitInsertMode(null);
    if (FloatingBubbleBridge.isAvailable) FloatingBubbleBridge.hide();
    return false;
  }
  return inserter.start(mode, preservePosition);
}

async function _ensureTextReceiverRuntime(): Promise<boolean> {
  if (!await _ensurePermanentTextReceiverPermissions()) return false;
  return startLocalSend();
}

function clearAiTimeout(): void {
  if (_aiTimeoutRef !== null) {
    clearTimeout(_aiTimeoutRef);
    _aiTimeoutRef = null;
  }
}

function _aiReadyText(): string {
  return _relayEnabled ? t('bubble_ai_ready') : t('bubble_relay_off');
}

function _resolvedAiBubbleActions() {
  
  
  return resolveAiBubbleActions(_cachedAiBubbleActionIds);
}

function _syncRelayUi(): void {
  if (!_aiActive || !AiBubbleBridge.isAvailable) return;
  AiBubbleBridge.setActionButtons(_resolvedAiBubbleActions());
}

function _showRelayDisabledHint(): void {
  if (AiBubbleBridge.isAvailable) AiBubbleBridge.updateText(t('bubble_relay_disabled'));
  setTimeout(() => {
    if (!_aiWaiting && !_relayEnabled && AiBubbleBridge.isAvailable) {
      AiBubbleBridge.updateText(_aiReadyText());
    }
  }, 3000);
}

async function _toggleAirRelay(): Promise<void> {
  try {
    const enabled = await AIRelayModule?.toggleRelayEnabled?.();
    _relayEnabled = enabled === true;
    _syncRelayUi();
    if (_relayEnabled) {
      if (AiBubbleBridge.isAvailable) AiBubbleBridge.updateText(t('bubble_relay_on'));
      await AIRelayModule?.openRelayMain?.('AIRelay');
    } else {
      _aiWaiting = false;
      clearAiTimeout();
      if (AiBubbleBridge.isAvailable) AiBubbleBridge.updateText(t('bubble_relay_off'));
    }
  } catch (e) {
    console.warn('[BackgroundService]: AIRelay toggle failed:', e);
    if (AiBubbleBridge.isAvailable) AiBubbleBridge.updateText(t('bubble_ai_send_failed'));
  }
}


async function _startAirRelayAndExpand(): Promise<void> {
  try {
    if (!_relayEnabled) {
      const enabled = await AIRelayModule?.setRelayEnabled?.(true);
      _relayEnabled = enabled === true;
      _syncRelayUi();
    }
    if (AiBubbleBridge.isAvailable) {
      showAiBubble(_relayEnabled ? t('bubble_relay_on') : t('bubble_ai_not_paired'));
    }
  } catch (e) {
    console.warn('[BackgroundService]: AIRelay start failed:', e);
    if (AiBubbleBridge.isAvailable) {
      AiBubbleBridge.updateText(t('bubble_ai_send_failed'));
    }
  }
}

function emitInsertMode(mode: InsertMode | null): void {
  DeviceEventEmitter.emit('insertModeChanged', { mode });
}




function _syncHeartbeat(): void {
  const want = _activeMode !== null || _aiActive;
  try {
    if (want) AIRelayModule?.startHeartbeat?.();
    else AIRelayModule?.stopHeartbeat?.();
  } catch (_) {}
}

function _pushActiveModes(): void {
  const modes: string[] = [];
  if (_activeMode || _mosaicTextActive) modes.push('insert_text');
  if (_aiActive) {
    modes.push(TOOL_IDS.AI_RELAY);
  }
  FloatingToolbarBridge.setActiveModes(modes);
  _syncHeartbeat();
}

function reviveBridge(): void {
  if (!AIRelayModule || !_aiActive) return;

  if (_broadcastTextSub) {
    _broadcastTextSub.remove();
    _broadcastTextSub = null;
  }

  AIRelayModule.startListening();
  
  
  
  
  try { AIRelayModule.startInboxListening?.(); } catch (_) {}

  const bbEmitter = new NativeEventEmitter(AIRelayModule);
  _broadcastTextSub = bbEmitter.addListener('onTextFromRelay', (text: string) => {
    console.log('[BackgroundService]: onTextFromRelay len=', text.length);
    FileLogger.logTextReceived('Broadcast', text);

    try { AIRelayModule.sendAck(text, true, null); } catch (_) {}

    clearAiTimeout();

    if (_aiWaiting) {
      _aiWaiting = false;
      if (AiBubbleBridge.isAvailable) AiBubbleBridge.updateText(_aiReadyText());
    }

    
    
    
    if (_mosaicTextEngaged() || _isMosaicBoard()) {
      _stageMosaicText(text, 'broadcast');
      return;
    }

    
    
    
    
    
    
    if (!_textInserter) return;
    
    
    
    if (_activeMode) {
      _stagePendingCard(text, 'broadcast');
      return;
    }
    (async () => {
      const inserter = _textInserter;
      if (!inserter) return;
      let ok = inserter.isRunning();
      if (inserter.isPaused()) {
        inserter.resume();
        ok = true;
      } else if (!ok) {
        ok = await _startTextInserter(inserter, 'nospacing');
      }
      if (!ok) return;

      _activeMode = inserter.getMode() ?? 'nospacing';
      _pushActiveModes();
      emitInsertMode(_activeMode);
      if (FloatingBubbleBridge.isAvailable) {
        console.log('[BUBBLE-DBG/JS] text receiver: calling bridge.show, mode=', _activeMode);
        const ps = inserter.getPageSize();
        if (ps) {
          FloatingBubbleBridge.setPageHeight(ps.height);
          FloatingBubbleBridge.setPageWidth(ps.width);
        }
        _aiPositionLocked = false;
        FloatingBubbleBridge.show(_getBubbleStatusText(), _activeMode);
      }
      _stagePendingCard(text, 'broadcast');
    })().catch(e => console.error('[BackgroundService]: relay text receiver start error:', e));
  });

  
  _blocksSub?.remove();
  _blocksSub = bbEmitter.addListener('onBlocksFromRelay',
    (payload: { text: string; blocks: string }) => {
      handleRelayBlocks(payload, false).catch(e =>
        console.error('[BackgroundService]: onBlocksFromRelay error:', e));
    });

  
  _replaceSub?.remove();
  _replaceSub = bbEmitter.addListener('onReplaceFromRelay',
    (payload: { text: string; blocks: string }) => {
      handleRelayBlocks(payload, true).catch(e =>
        console.error('[BackgroundService]: onReplaceFromRelay error:', e));
    });

  console.log('[BackgroundService]: bridge revived');
}

async function handleRelayBlocks(
  payload: { text: string; blocks: string },
  replace: boolean,
): Promise<void> {
  console.log('[BackgroundService]: relay blocks replace=', replace, 'len=', payload.blocks?.length);
  FileLogger.logEvent('RelayBlocks', `replace=${replace} bytes=${payload.blocks?.length ?? 0}`);

  try { AIRelayModule.sendAck(payload.text ?? '', true, null); } catch (_) {}

  clearAiTimeout();
  if (_aiWaiting) {
    _aiWaiting = false;
    if (AiBubbleBridge.isAvailable) AiBubbleBridge.updateText(_aiReadyText());
  }

  
  
  if (_mosaicTextEngaged() || _isMosaicBoard()) {
    const flat = (payload.text ?? '').trim();
    if (flat.length > 0) _stageMosaicText(flat, 'broadcast');
    return;
  }

  if (!_textInserter) return;

  
  if (_textInserter.isPaused()) {
    _textInserter.resume();
  } else if (!_textInserter.isRunning()) {
    _textInserter.clearQueue();
    const ok = await _startTextInserter(_textInserter, 'nospacing');
    if (ok) {
      _activeMode = 'nospacing';
      _pushActiveModes();
      emitInsertMode('nospacing');
    }
  }

  
  try {
    const pgRes: any = await PluginCommAPI.getCurrentPageNum();
    if (pgRes?.success && typeof pgRes.result === 'number'
        && pgRes.result !== _textInserter.getTargetPage()) {
      _textInserter.relocateTo(pgRes.result);
    }
  } catch (_) {}

  await processRelayBlocks(payload.blocks, {
    replace,
    insertText: (text, anchorTop, anchorLeft) => {
      if (anchorTop != null && anchorLeft != null) {
        const anchor = setProgrammaticInsertionAnchor(anchorTop, anchorLeft, replace ? 'formula-replace' : 'formula-insert');
        if (anchor) showTextBubbleAtInsertion(anchor);
      }
      _textInserter?.enqueue(text, 'broadcast');
    },
    
    
    
    waitTextIdle: async () => {
      const ti = _textInserter;
      if (!ti) return null;
      const deadline = Date.now() + 45_000;
      while (Date.now() < deadline
          && (ti.getQueueLength() > 0 || ti.isBusy())) {
        await new Promise<void>(r => setTimeout(r, 300));
      }
      if (ti.getQueueLength() > 0 || ti.isBusy()) {
        FileLogger.logEvent('RelayBlocks', 'waitTextIdle timeout — using current nextTop');
      }
      await new Promise<void>(r => setTimeout(r, 200));
      return ti.getNextTop();
    },
    
    
    onEmbedded: (bottom, left) => {
      const anchor = setProgrammaticInsertionAnchor(bottom + 24, left, 'formula-embedded');
      if (anchor) showTextBubbleAtInsertion(anchor);
    },
    onStatus: (stage) => {
      if (!AiBubbleBridge.isAvailable) return;
      try {
        if (stage === 'rendering') AiBubbleBridge.updateText(t('bubble_formula_rendering'));
        else if (stage === 'inserting') AiBubbleBridge.updateText(t('bubble_ai_sending'));
        else if (stage === 'failed') AiBubbleBridge.updateText(t('bubble_formula_failed'));
        else AiBubbleBridge.updateText(_aiReadyText());
      } catch (_) {}
    },
  });
}

function teardownBridge(): void {
  if (_broadcastTextSub) {
    _broadcastTextSub.remove();
    _broadcastTextSub = null;
  }
  _blocksSub?.remove();
  _blocksSub = null;
  _replaceSub?.remove();
  _replaceSub = null;
  try { AIRelayModule?.stopListening?.(); } catch (_) {}
}

function showAiBubble(statusText: string, mode: 'ai' | 'voice' = 'ai'): void {
  if (!AiBubbleBridge.isAvailable) return;
  const ps = _textInserter?.getPageSize();
  if (ps) AiBubbleBridge.setPageHeight(ps.height);
  AiBubbleBridge.setActionButtons(_resolvedAiBubbleActions());
  AiBubbleBridge.show(statusText, mode);
  
  
  if (_inboxItems.length > 0) AiBubbleBridge.setInboxItems(_inboxItems);
}

function _getBubbleStatusText(): string {
  if (!_activeMode) return '';
  const base = _activeMode === 'nospacing'
    ? t('bubble_recv_nospacing')
    : t('bubble_recv_paragraph');

  if (_textInserter?.isWaitingPage()) {
    return base + ' ⊕';
  }

  const qLen = _textInserter?.getQueueLength() ?? 0;
  if (qLen > 0) return base + ` (${qLen})`;
  return base;
}

type InsertionAnchor = { top: number; left: number; safeMaxLeft: number };

function setProgrammaticInsertionAnchor(top: number, left: number, source: string): InsertionAnchor | null {
  if (!_textInserter) return null;
  const anchor = _textInserter.setNextInsertionAnchor(top, left, source);
  _aiPositionLocked = true;
  return anchor;
}

function showTextBubbleAtInsertion(anchor: InsertionAnchor): void {
  if (!FloatingBubbleBridge.isAvailable || !_textInserter) return;
  const ps = _textInserter.getPageSize();
  if (ps) {
    FloatingBubbleBridge.setPageHeight(ps.height);
    FloatingBubbleBridge.setPageWidth(ps.width);
  }
  _aiPositionLocked = true;
  FloatingBubbleBridge.showAt(_getBubbleStatusText(), anchor.left, anchor.top, _activeMode ?? undefined);
  console.log('[BackgroundService]: show bubble at insertion origin=', JSON.stringify(anchor));
}


function _isMosaicBoard(): boolean {
  try { return (FloatingToolbar as any)?.isMosaicBoardVisible?.() === true; } catch { return false; }
}

function _mosaicBubbleStatus(): string {
  return _mosaicTextBuffer.length > 0 ? t('bubble_mosaic_pending') : t('bubble_mosaic_ready');
}


function _mosaicBubbleOwned(): boolean {
  if (_mosaicTextActive) return true;
  if (_mosaicTextBuffer.length === 0) return false;
  if (_isMosaicBoard()) return true;
  FileLogger.logEvent('MosaicText', `buffer dropped (board closed) pending=${_mosaicTextBuffer.length}`);
  _mosaicTextBuffer = [];
  return false;
}


function _refreshMosaicBubble(): void {
  if (!FloatingBubbleBridge.isAvailable) return;
  if (_mosaicTextActive || _mosaicTextBuffer.length > 0) {
    FloatingBubbleBridge.setPending(_mosaicTextBuffer.length > 0);
    FloatingBubbleBridge.show(_mosaicBubbleStatus(), 'nospacing');
    return;
  }
  if (_activeMode) {
    _syncTextBubblePending();
    FloatingBubbleBridge.show(_getBubbleStatusText(), _activeMode);
  } else {
    FloatingBubbleBridge.setPending(false);
    FloatingBubbleBridge.hide();
  }
}


async function toggleMosaicTextMode(): Promise<boolean> {
  if (_mosaicTextActive) {
    stopMosaicTextMode();
    return false;
  }
  
  if (!await _ensureTextReceiverRuntime()) {
    return false;
  }
  _mosaicTextActive = true;
  _mosaicTextBuffer = [];
  _pushActiveModes();
  if (FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.setPending(false);
    FloatingBubbleBridge.show(_mosaicBubbleStatus(), 'nospacing');
  }
  FileLogger.logEvent('MosaicText', 'mode ON');
  return true;
}

function stopMosaicTextMode(): void {
  if (!_mosaicTextActive) return;
  _mosaicTextActive = false;
  _mosaicTextBuffer = [];
  _pushActiveModes();
  if (FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.setPending(false);
    FloatingBubbleBridge.hide();
  }
  FileLogger.logEvent('MosaicText', 'mode OFF');
}


function _mosaicTextEngaged(): boolean {
  if (!_mosaicTextActive) return false;
  if (_isMosaicBoard()) return true;
  stopMosaicTextMode();
  return false;
}


function _stageMosaicText(text: string, source: TextSource): void {
  const trimmed = text ?? '';
  if (trimmed.length === 0) return;
  _mosaicTextBuffer.push(trimmed);
  console.log('[BackgroundService]: staged mosaic text, source=', source,
    'pending=', _mosaicTextBuffer.length);
  FileLogger.logEvent('MosaicText', `staged source=${source} pending=${_mosaicTextBuffer.length}`);
  _refreshMosaicBubble();
}


function _flushMosaicTextCard(d: {
  screenX: number; screenBottomY: number; bubbleWidth: number;
}): void {
  if (_mosaicTextBuffer.length === 0) return;
  const text = _mosaicTextBuffer.join('\n\n');
  _mosaicTextBuffer = [];
  const anchorX = Math.round(d.screenX + (d.bubbleWidth ?? 0));
  const anchorY = Math.round(d.screenBottomY);
  FloatingToolbarBridge.requestMosaicTextCard(text, anchorX, anchorY);
  console.log('[BackgroundService]: mosaic text card sent anchor=', anchorX, anchorY, 'len=', text.length);
  FileLogger.logEvent('MosaicText', `flush anchor=(${anchorX},${anchorY}) len=${text.length}`);
  _refreshMosaicBubble();
}

function _stagePendingCard(text: string, source: TextSource): void {
  _pendingCards.push({ text, source });
  console.log('[BackgroundService]: staged text card, source=', source, 'pending=', _pendingCards.length);
  FileLogger.logEvent('StageCard', `source=${source} pending=${_pendingCards.length}`);
  _syncTextBubblePending();
}

function _syncTextBubblePending(): void {
  const pending = _pendingCards.length > 0
    || (_textInserter?.isWaitingForPageTap() ?? false);
  if (FloatingBubbleBridge.isAvailable) FloatingBubbleBridge.setPending(pending);
}


async function _flushPendingCards(beforeDrain?: () => void): Promise<void> {
  if (!_textInserter) {
    beforeDrain?.();
    return;
  }
  const inserter = _textInserter;
  try {
    const pgRes: any = await PluginCommAPI.getCurrentPageNum();
    if (pgRes?.success && typeof pgRes.result === 'number'
        && pgRes.result !== inserter.getTargetPage()) {
      inserter.relocateTo(pgRes.result);
    }
  } catch (e) {
    console.warn('[BackgroundService]: bubble tap page lookup:', e);
  }

  
  beforeDrain?.();

  const cards = _pendingCards;
  _pendingCards = [];
  if (cards.length > 0) {
    console.log('[BackgroundService]: bubble tap → inserting', cards.length, 'staged card(s)');
    FileLogger.logEvent('FlushCards', `count=${cards.length}`);
    for (const c of cards) inserter.enqueue(c.text, c.source);
  }

  const continued = inserter.continueAfterPageTap();
  if (continued) {
    FileLogger.logEvent('ContinueAfterPageTap',
      `page=${inserter.getTargetPage()} queueLen=${inserter.getQueueLength()}`);
  }
  _syncTextBubblePending();
}


function _clearPendingCards(): void {
  if (_pendingCards.length > 0) {
    console.log('[BackgroundService]: dropping', _pendingCards.length, 'staged card(s)');
    _pendingCards = [];
  }
  _syncTextBubblePending();
}


function _clearTextReceiveQueueFromBubble(): void {
  const staged = _pendingCards.length;
  const queued = _textInserter?.getQueueLength() ?? 0;
  _pendingCards = [];
  _textInserter?.clearQueue();
  _syncTextBubblePending();
  console.log('[BackgroundService]: bubble long press cleared text queue, staged=', staged,
    'queued=', queued);
  FileLogger.logEvent('ClearTextQueue', `staged=${staged} queued=${queued}`);
}


async function prefetchPageInfo(attempt = 0): Promise<void> {
  const MAX_ATTEMPTS = 6;
  try {
    const fpRes: any = await PluginCommAPI.getCurrentFilePath();
    const notePath = fpRes?.success ? fpRes.result : null;
    if (!notePath) throw new Error('no notePath');
    const pgRes: any = await PluginCommAPI.getCurrentPageNum();
    const page = pgRes?.success && typeof pgRes.result === 'number' ? pgRes.result : 0;
    const psRes: any = await PluginFileAPI.getPageSize(notePath, page);
    if (psRes?.success && psRes.result?.width) {
      presetPageInfo(notePath, { width: psRes.result.width, height: psRes.result.height });
      return;
    }
    throw new Error('getPageSize failed: ' + JSON.stringify(psRes?.error ?? psRes));
  } catch (e) {
    if (attempt + 1 >= MAX_ATTEMPTS) {
      console.warn('[BackgroundService]: prefetchPageInfo gave up:', e);
      return;
    }
    console.log('[BackgroundService]: prefetchPageInfo retry', attempt + 1, '(', e, ')');
    setTimeout(() => prefetchPageInfo(attempt + 1), 1200);
  }
}

export function ensureInit(): void {
  if (_initialized) return;
  _initialized = true;

  console.log('[BackgroundService]: ── initializing ──');

  prefetchPageInfo();

  
  
  
  try { FloatingToolbarBridge.setActiveModes([]); } catch (_) {}

  loadLastMode().then(m => { _lastMode = m; });

  DeviceEventEmitter.addListener('onOpenTextReceiverRequested', () => {
    console.log('[BackgroundService]: opening text receiver from LocalSend prompt, mode=', _lastMode);
    startMode(_lastMode).then(ok => {
      console.log('[BackgroundService]: LocalSend prompt text receiver result=', ok);
    }).catch(error => {
      console.warn('[BackgroundService]: failed to open text receiver from LocalSend prompt:', error);
    });
  });

  if (AIRelayModule) {
    
    try {
      AIRelayModule.getAutoOpenDetail?.().then((v: boolean) => {
        _autoOpenDetail = v === true;
      }).catch(() => {});
    } catch (_) {}

    
    
    try {
      AIRelayModule.isRelayEnabled?.().then((enabled: boolean) => {
        _relayEnabled = enabled === true;
        _syncRelayUi();
        if (_aiActive && AiBubbleBridge.isAvailable) {
          AiBubbleBridge.updateText(_aiReadyText());
        }
      }).catch(() => {});
    } catch (_) {}

    
    try { AIRelayModule.startInboxListening?.(); } catch (_) {}

    if (!_relayInboxSub) {
      const inboxEmitter = new NativeEventEmitter(AIRelayModule);
      _relayInboxSub = inboxEmitter.addListener('onInboxFromRelay', (itemsJson: string) => {
        let parsed: InboxItem[];
        try {
          parsed = JSON.parse(itemsJson);
        } catch (e) {
          console.warn('[BackgroundService]: bad inbox payload:', e);
          return;
        }
        
        const prevIds = new Set(_inboxItems.map(it => it.id));
        const wasWaiting = _aiWaiting;

        
        
        
        
        _inboxItems = parsed.filter(it =>
          !_dismissedCapsuleIds.has(it.id) &&
          (it.pinned === true ||
          (typeof it.time === 'number' && it.time >= _aiSessionStart)));
        console.log('[BackgroundService]: inbox from relay, items=', parsed.length,
          'afterSessionFilter=', _inboxItems.length);
        if (AiBubbleBridge.isAvailable) AiBubbleBridge.setInboxItems(_inboxItems);

        
        
        
        
        if (_aiWaiting && _inboxItems.some(it => it.source === 'llm')) {
          clearAiTimeout();
          _aiWaiting = false;
          if (AiBubbleBridge.isAvailable) AiBubbleBridge.updateText(t('bubble_ai_reply_ready'));
        }

        
        
        if (_autoOpenDetail && wasWaiting && _aiActive) {
          const newLlmItems = _inboxItems.filter(it =>
            it.source === 'llm' && !prevIds.has(it.id) && !_autoOpenedIds.has(it.id));
          if (newLlmItems.length > 0) {
            const target = newLlmItems[newLlmItems.length - 1];
            _autoOpenedIds.add(target.id);
            _currentCapsuleDetailId = target.id;
            _capsuleOpenCount[target.id] = (_capsuleOpenCount[target.id] || 0) + 1;
            console.log('[BackgroundService]: auto-opening detail for', target.id);
            AIRelayModule?.openRelayDetail?.('AIRelay', target.id)
              .catch((e: any) => console.warn('[BackgroundService]: auto-open detail failed:', e));
          }
        }
      });
    }

  }

  _textInserter = new TextInserter(
    (text, success, error) => {

      if (AIRelayModule) {
        try { AIRelayModule.sendAck(text, success, error); } catch (_) {}
      }
      if (_aiWaiting) {
        _aiWaiting = false;

        if (AiBubbleBridge.isAvailable) {
          AiBubbleBridge.updateText(t('bubble_insert_done'));
          setTimeout(() => {
            if (!_aiWaiting) AiBubbleBridge.updateText(_aiReadyText());
          }, 2000);
        }
      }
    },
    (page, nextTop, source, isPageChange) => {

      try {
        if (AIRelayModule?.sendInsertPosition) {
          AIRelayModule.sendInsertPosition(page, nextTop);
        }
      } catch (e) {
        console.warn('[BackgroundService]: sendInsertPosition failed:', e);
      }

      if (FloatingBubbleBridge.isAvailable) {
        if (isPageChange) {
          const ps = _textInserter?.getPageSize();
          if (ps) { FloatingBubbleBridge.setPageHeight(ps.height); FloatingBubbleBridge.setPageWidth(ps.width); }
          const left = _textInserter?.getNextLeft();
          if (left !== undefined) {
            _aiPositionLocked = true;
            FloatingBubbleBridge.showAt(_getBubbleStatusText(), left, nextTop, _activeMode ?? undefined);
            console.log('[BackgroundService]: pageChange showAt left=', left, 'top=', nextTop);
          } else {
            _aiPositionLocked = false;
            FloatingBubbleBridge.show(_getBubbleStatusText(), _activeMode ?? undefined);
            console.log('[BackgroundService]: pageChange show (bubble decides position)');
          }
        }
      }

    },

    (reason, detail) => {
      console.warn('[BackgroundService]: note context changed:', reason, detail);
      FileLogger.logEvent('NoteContextChanged', `${reason}: ${detail}`);
      const prevMode = _activeMode;
      _activeMode = null;
      clearAiTimeout();
      
      _clearPendingCards();
      _pushActiveModes();
      emitInsertMode(null);

      if (_aiWaiting) {
        _aiWaiting = false;
        if (_aiActive && AiBubbleBridge.isAvailable) {
          AiBubbleBridge.updateText(_aiReadyText());
        }
      }

      if (reason === 'note_switched' && prevMode && _textInserter) {
        console.log('[BackgroundService]: note switched, restarting inserter mode=', prevMode);
        _textInserter.clearQueue();
        _startTextInserter(_textInserter, prevMode).then(ok => {
          if (ok) {
            _activeMode = prevMode;
            _pushActiveModes();
            emitInsertMode(prevMode);
            if (FloatingBubbleBridge.isAvailable) {
              FloatingBubbleBridge.show(_getBubbleStatusText(), prevMode);
            }
          }
        });
      } else if (FloatingBubbleBridge.isAvailable) {
        const msg = reason === 'note_switched'
          ? t('note_switched_stop')
          : t('pages_changed_stop');
        FloatingBubbleBridge.show(msg);
        setTimeout(() => FloatingBubbleBridge.hide(), 3500);
      }
    },
    (waiting, queueLength) => {
      console.log('[BackgroundService]: page continuation gate waiting=', waiting,
        'queue=', queueLength);
      _syncTextBubblePending();
    },
  );

  reviveBridge();

  if (!_lsTextSub) {
    _lsTextSub = LocalSendBridge.onTextReceived((info: TextReceivedInfo) => {
      console.log('[BackgroundService]: onTextReceived(LocalSend) len=', info.text.length);
      FileLogger.logTextReceived('LocalSend', info.text);
      if (info._pendingId) LocalSendBridge.ackPendingText(info._pendingId);
      
      if (_mosaicTextEngaged()) _stageMosaicText(info.text, 'localsend');
      else if (_activeMode) _stagePendingCard(info.text, 'localsend');
      else _textInserter?.enqueue(info.text, 'localsend');
    });
  }

  try { AIRelayModule?.sendAlive?.(); } catch (_) {}

  DeviceEventEmitter.addListener('onLocalSendStopped', () => {
    console.log('[BackgroundService]: WiFi lost – LocalSend server stopped');
    _localSendStarted = false;
  });


  if (FloatingBubbleBridge.isAvailable) {
    const applyBubblePosition = (data: {
      pageY: number; pageX: number;
      pageBottomY: number; bubbleHeight: number;
      screenY: number; screenBottomY: number;
    }, source: 'user-drag' | 'user-tap' | 'initial-layout') => {
      
      if (_mosaicBubbleOwned()) return;
      const bottomY = (data.pageBottomY > data.pageY) ? data.pageBottomY : data.pageY;
      const insertTop = bottomY + 4;
      const anchor = _textInserter?.setNextInsertionAnchor(insertTop, data.pageX, source);
      _aiPositionLocked = anchor !== undefined;
      console.log('[BackgroundService]: applyBubblePosition source=', source,
        'raw=', JSON.stringify(data), 'accepted=', JSON.stringify(anchor));
    };

    FloatingBubbleBridge.onTap((d) => {
      if (_mosaicBubbleOwned()) {
        console.log('[BackgroundService]: mosaic text bubble tapped, pending=', _mosaicTextBuffer.length);
        _flushMosaicTextCard(d);
        return;
      }
      console.log('[BackgroundService]: text bubble tapped, pending=', _pendingCards.length);
      _aiPositionLocked = false;
      _flushPendingCards(() => applyBubblePosition(d, 'user-tap')).catch(e =>
        console.warn('[BackgroundService]: text bubble tap:', e));
    });

    FloatingBubbleBridge.onLongPress(() => {
      if (_mosaicBubbleOwned()) {
        if (_mosaicTextBuffer.length > 0) {
          _mosaicTextBuffer = [];
          FileLogger.logEvent('MosaicText', 'buffer cleared (long press)');
          _refreshMosaicBubble();
        }
        return;
      }
      const armed = _pendingCards.length > 0
        || (_textInserter?.isWaitingForPageTap() ?? false);
      if (armed) _clearTextReceiveQueueFromBubble();
    });

    FloatingBubbleBridge.onDragEnd((d) => {
      _aiPositionLocked = false;
      applyBubblePosition(d, 'user-drag');
    });
    FloatingBubbleBridge.onLayout((d) => {
      if (_aiPositionLocked) {
        console.log('[BackgroundService]: ignored programmatic bubble layout=', JSON.stringify(d));
        return;
      }
      applyBubblePosition(d, 'initial-layout');
    });

    if (AiBubbleBridge.isAvailable) {
      if (!_aiLongPressSub) {
        _aiLongPressSub = AiBubbleBridge.onLongPress(async () => {
          if (!_aiActive) return;
          if (!_relayEnabled) {
            _showRelayDisabledHint();
            return;
          }
          let hasLasso = false;
          try {
            const result = await PluginCommAPI.getLassoRect();
            hasLasso = result?.success === true && result.result != null;
          } catch (e) {
            console.warn('[BackgroundService]: AI bubble long-press lasso check failed:', e);
          }
          if (hasLasso) {
            handleAiSend().catch(e => console.error('[BackgroundService]: AI bubble long-press lasso send failed:', e));
          } else {
            handleScreenshotAi().catch(e => console.error('[BackgroundService]: AI bubble long-press screenshot failed:', e));
          }
        });
      }

      AiBubbleBridge.onAction(({ actionId }) => {
        console.log('[BackgroundService]: AI bubble action:', actionId);
        if ((actionId === 'lasso_ai' || actionId === 'screenshot_ai' || actionId === 'pen_lasso_ai')
            && !_relayEnabled) {
          _showRelayDisabledHint();
          return;
        }
        
        
        if (actionId === 'lasso_ai') {
          handleAiSend().catch(e => console.error('[BackgroundService]: AI lasso_ai error:', e));
        } else if (actionId === 'screenshot_ai') {
          handleScreenshotAi().catch(e => console.error('[BackgroundService]: AI screenshot_ai error:', e));
        } else if (actionId === 'pen_lasso_ai') {
          if (AiBubbleBridge.isAvailable) AiBubbleBridge.hide();
          if (FloatingBubbleBridge.isAvailable) FloatingBubbleBridge.hide();

          const bboxSub = FloatingToolbarBridge.onPenLassoBbox(() => {
            bboxSub.remove();
            cancelSub.remove();
            setTimeout(() => {
              restoreBubbleAfterLasso();
              handleFormulaSend().catch(e => console.error('[BackgroundService]: pen_lasso_ai formula error:', e));
            }, 300);
          });
          const cancelSub = FloatingToolbarBridge.onPenLassoCancel(() => {
            bboxSub.remove();
            cancelSub.remove();
            setTimeout(() => restoreBubbleAfterLasso(), 300);
          });

          const { PenLasso } = require('./PenTools');
          PenLasso.arm().catch((e: any) => {
            console.error('[BackgroundService]: PenLasso.arm error:', e);
            bboxSub.remove();
            cancelSub.remove();
            restoreBubbleAfterLasso();
          });
        } else if (actionId === 'copilot') {
          
          
          if (!_relayEnabled) {
            _startAirRelayAndExpand().catch(e =>
              console.warn('[BackgroundService]: copilot start AIRelay failed:', e));
          } else {
            AIRelayModule?.openRelayMain?.('AIRelay').catch((e: any) =>
              console.warn('[BackgroundService]: copilot open inbox failed:', e));
          }
        } else if (actionId === 'cancel_ai') {
          stopAiMode();
        }
      });

      
      AiBubbleBridge.onExpand(() => {
        if (!_aiActive) return;
        _startAirRelayAndExpand().catch(e =>
          console.warn('[BackgroundService]: ball expand start AIRelay failed:', e));
      });

      
      
      
      if (!_aiCollapseSub) {
        _aiCollapseSub = AiBubbleBridge.onCollapse(() => {
          if (!_aiActive) return;
          console.log('[BackgroundService]: AI bubble collapsed to ball — resetting session');
          _aiSessionStart = Date.now();
          _inboxItems = [];
          _dismissedCapsuleIds = new Set();
          _capsuleOpenCount = {};
          _autoOpenedIds = new Set();
          _currentCapsuleDetailId = null;
          if (AiBubbleBridge.isAvailable) AiBubbleBridge.setInboxItems([]);
          stopMode();
        });
      }

      
      
      AiBubbleBridge.onCapsuleTap(({ id }) => {
        _currentCapsuleDetailId = id;
        _capsuleOpenCount[id] = (_capsuleOpenCount[id] || 0) + 1;
        console.log('[BackgroundService]: capsule tap id=', id, 'openCount=', _capsuleOpenCount[id]);
        AIRelayModule?.openRelayDetail?.('AIRelay', id)
          .catch((e: any) => console.warn('[BackgroundService]: open AIRelay detail failed:', e));
      });

      
      
      AiBubbleBridge.onCapsuleLongPress(({ id }) => {
        try { AIRelayModule?.sendInsertRequest?.(id); } catch (_) {}
        if (AiBubbleBridge.isAvailable) {
          AiBubbleBridge.updateText(t('bubble_insert_sent'));
          setTimeout(() => {
            if (!_aiWaiting) AiBubbleBridge.updateText(_aiReadyText());
          }, 2000);
        }
      });
    }

    if (!_nativePanelCloseSub) {
      _nativePanelCloseSub = FloatingToolbarBridge.onNativePanelClose(({
        panel,
        cameFromBubble,
        restoreNoteBubbles,
        screenshotBbox,
        detailId,
      }) => {
        
        
        const shouldRestoreNoteBubbles = restoreNoteBubbles ?? cameFromBubble;
        console.log('[BackgroundService]: onNativePanelClose panel=', panel,
          'fromBubble=', cameFromBubble, 'restoreNoteBubbles=', shouldRestoreNoteBubbles);

        
        
        
        
        
        
        if (panel === 'config' && AIRelayModule) {
          try {
            AIRelayModule.getAutoOpenDetail?.().then((v: boolean) => {
              _autoOpenDetail = v === true;
            }).catch(() => {});
          } catch (_) {}
        }

        if (panel === 'airRelay') {
          const closedId = detailId || _currentCapsuleDetailId;
          if (closedId && _capsuleOpenCount[closedId]) {
            const maxOpens = _autoOpenDetail ? 2 : 1;
            if (_capsuleOpenCount[closedId] >= maxOpens) {
              _dismissedCapsuleIds.add(closedId);
              _inboxItems = _inboxItems.filter(it => it.id !== closedId);
              if (AiBubbleBridge.isAvailable) AiBubbleBridge.setInboxItems(_inboxItems);
              console.log('[BackgroundService]: capsule dismissed id=', closedId,
                'opens=', _capsuleOpenCount[closedId], 'max=', maxOpens);
            }
          }
          _currentCapsuleDetailId = null;
        }

        const screenshotAnchor = screenshotBbox
          ? setProgrammaticInsertionAnchor(
              screenshotBbox.bottom + AI_REPLY_GAP_PX,
              screenshotBbox.left,
              `screenshot-${panel}`,
            )
          : null;
        if (shouldRestoreNoteBubbles) {
          setTimeout(() => {
            if (screenshotAnchor) showTextBubbleAtInsertion(screenshotAnchor);
            restoreBubbleAfterLasso();
          }, 300);
        }
      });
    }

    if (!_noteForegroundSub) {
      _noteForegroundSub = FloatingToolbarBridge.onNoteForegroundChanged(({ inNote }) => {
        console.log('[BackgroundService]: onNoteForegroundChanged inNote=', inNote);
        if (inNote) {
          
          
          _textInserter?.holdInserts(1500);
        }
      });
    }
  }

  loadAiBubbleActions().then(ids => { _cachedAiBubbleActionIds = ids; });

  FileLogger.logEvent('BackgroundInit', 'ready');
}

export function getActiveMode(): InsertMode | null {
  return _activeMode;
}

export function getLastMode(): InsertMode {
  return _lastMode;
}

export function isAiActive(): boolean {
  return _aiActive;
}

export async function toggleMode(target: InsertMode): Promise<InsertMode | null> {
  ensureInit();
  
  if (_isMosaicBoard()) {
    return (await toggleMosaicTextMode()) ? target : null;
  }
  if (!_textInserter) return null;

  const cur = _activeMode;

  const isActuallyRunning = _textInserter.isRunning() || _textInserter.isPaused();
  if (cur === null || !isActuallyRunning) {
    await switchToMode(target);
  } else if (cur === target) {
    stopMode();
  } else {
    await switchToMode(target);
  }

  FileLogger.logEvent('ToggleMode', `target=${target} result=${_activeMode}`);
  return _activeMode;
}

export async function startMode(mode: InsertMode): Promise<boolean> {
  _lastMode = mode;
  saveLastMode(mode);
  return switchToMode(mode);
}

export function stopMode(): void {
  if (_textInserter) {
    const cur = _textInserter.getMode();
    if (cur) { _lastMode = cur; saveLastMode(cur); }
    _textInserter.clearQueue();
    _textInserter.stop(true);
  }
  _activeMode = null;
  _aiPositionLocked = false;
  _clearPendingCards();
  if (FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.hide();
  }
  _pushActiveModes();
  emitInsertMode(null);
}

export async function startAiReceiveMode(): Promise<void> {
  ensureInit();
  
  try {
    AIRelayModule?.getAutoOpenDetail?.().then((v: boolean) => {
      _autoOpenDetail = v === true;
    }).catch(() => {});
  } catch (_) {}
  
  
  
  _aiSessionStart = Date.now();
  _inboxItems = [];
  _dismissedCapsuleIds = new Set();
  _capsuleOpenCount = {};
  _autoOpenedIds = new Set();
  _currentCapsuleDetailId = null;
  if (AiBubbleBridge.isAvailable) AiBubbleBridge.setInboxItems([]);
  try { AIRelayModule?.sendAlive?.(); } catch (_) {}
  if (!_aiActive) {
    _aiActive = true;
    reviveBridge();
    _pushActiveModes();
  }
  await _startAirRelayAndExpand();
}

export function stopAiMode(): void {
  _aiActive = false;
  _aiWaiting = false;
  _aiPositionLocked = false;
  
  _aiSessionStart = Date.now();
  _inboxItems = [];
  _dismissedCapsuleIds = new Set();
  _capsuleOpenCount = {};
  _autoOpenedIds = new Set();
  _currentCapsuleDetailId = null;
  
  
  try { AIRelayModule?.sendFormulaSessionReset?.(); } catch (_) {}
  if (AiBubbleBridge.isAvailable) AiBubbleBridge.setInboxItems([]);
  clearAiTimeout();
  _relayEnabled = false;
  try { AIRelayModule?.setRelayEnabled?.(false).catch(() => {}); } catch (_) {}
  teardownBridge();
  AiBubbleBridge.hide();
  
  stopMode();
  _pushActiveModes();
}

export function stopAllModes(): void {
  stopAiMode(); 
}

export async function switchToMode(mode: InsertMode): Promise<boolean> {
  ensureInit();
  if (!_textInserter) return false;
  if (!await _ensureTextReceiverRuntime()) {
    stopMode();
    return false;
  }

  const myVersion = ++_switchVersion;
  const previousMode = _lastMode;
  _lastMode = mode;
  saveLastMode(mode);

  if (_textInserter.isPaused()) {
    _textInserter.resume();
  }

  if (_textInserter.isRunning()) {
    const ok = _textInserter.switchMode(mode);
    if (ok) {
      _activeMode = mode;
      _pushActiveModes();
      emitInsertMode(mode);
      return true;
    }
  }

  _textInserter.clearQueue();
  const ok = await _textInserter.start(mode, true);
  if (myVersion !== _switchVersion) return false;
  if (ok) {
    _textInserter.applyModeSwitchGap(previousMode, mode);
    _activeMode = mode;
    if (FloatingBubbleBridge.isAvailable) {
      const ps = _textInserter.getPageSize();
      if (ps) { FloatingBubbleBridge.setPageHeight(ps.height); FloatingBubbleBridge.setPageWidth(ps.width); }
    }
  } else {
    let retryOk = false;
    const retryDelays = [800, 2000];
    for (const delay of retryDelays) {
      await new Promise<void>(r => setTimeout(r, delay));
      if (myVersion !== _switchVersion) return false;
      if (_textInserter.isRunning()) { retryOk = true; break; }
      console.log('[BackgroundService]: retrying start, delay=', delay);
      FileLogger.logEvent('StartRetry', `mode=${mode} delay=${delay}`);
      retryOk = await _textInserter.start(mode, true);
      if (myVersion !== _switchVersion) return false;
      if (retryOk) break;
    }
    if (myVersion !== _switchVersion) return false;
    if (retryOk) {
      _textInserter.applyModeSwitchGap(previousMode, mode);
      _activeMode = mode;
      if (FloatingBubbleBridge.isAvailable) {
        const ps = _textInserter.getPageSize();
        if (ps) { FloatingBubbleBridge.setPageHeight(ps.height); FloatingBubbleBridge.setPageWidth(ps.width); }
      }
    } else {
      _activeMode = null;
    }
  }
  _pushActiveModes();
  emitInsertMode(_activeMode);

  if (_activeMode && FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.show(_getBubbleStatusText(), _activeMode);
  }

  return ok;
}

export async function handleAiSend(): Promise<void> {
  ensureInit();

  if (_aiSendInFlight) {
    console.log('[BackgroundService]: handleAiSend skipped (in-flight: extracting/OCR/sending)');
    FileLogger.logEvent('AiSendDebounce', 'inFlight');
    return;
  }
  if (_aiWaiting) {
    console.log('[BackgroundService]: handleAiSend skipped (already waiting for AI reply)');
    FileLogger.logEvent('AiSendDebounce', 'aiWaiting');
    return;
  }
  const now = Date.now();
  if (now - _lastAiSendAt < 3000) {
    console.log('[BackgroundService]: handleAiSend debounced (< 3s since last send)');
    FileLogger.logEvent('AiSendDebounce', `gap=${now - _lastAiSendAt}ms`);
    return;
  }
  _aiSendInFlight = true;

  reviveBridge();
  console.log('[BackgroundService]: ── handleAiSend ──');

  const onProgress = (stage: 'recognizing' | 'done') => {
    if (!AiBubbleBridge.isAvailable) return;
    try {
      if (stage === 'recognizing') AiBubbleBridge.updateText(t('bubble_ai_recognizing'));
      else AiBubbleBridge.updateText(t('bubble_ai_sending'));
    } catch (_) {}
  };

  try {

    let extracted: any;
    const mosaicBoard = _isMosaicBoard();
    if (mosaicBoard) {
      
      
      const { extractMosaicLassoText } = require('./ToolActions');
      const mosaicText = await extractMosaicLassoText(onProgress);
      extracted = { text: mosaicText ?? '', imagePaths: [], linkedFiles: [], lassoRect: null };
    } else {
      const warmed = await LassoExtractor.consumeWarmed(onProgress);
      extracted = warmed ?? await LassoExtractor.extract(onProgress);
    }
    if (!extracted.text) {
      FileLogger.logEvent('AiSendAbort', 'no text');

      if (AiBubbleBridge.isAvailable) {
        AiBubbleBridge.updateText(t('bubble_ai_no_lasso'));
        setTimeout(() => {
          if (!_aiWaiting) AiBubbleBridge.updateText(_aiReadyText());
        }, 2500);
      }
      return;
    }
    if (!AIRelayModule) {
      console.error('[BackgroundService]: AIRelayModule not available');
      return;
    }

    const sendTimeNow = Date.now();
    if (extracted.text === _lastAiSendText && sendTimeNow - _lastAiSendAt < AI_SAME_TEXT_DEBOUNCE_MS) {
      console.log('[BackgroundService]: handleAiSend dropped — same text within debounce window');
      FileLogger.logEvent('AiSendDebounce', `sameText gap=${sendTimeNow - _lastAiSendAt}ms len=${extracted.text.length}`);
      if (AiBubbleBridge.isAvailable) {
        AiBubbleBridge.updateText(t('bubble_ai_waiting'));
      }
      return;
    }

    reviveBridge();

    
    if (!mosaicBoard) {
      try {
        await Promise.race([
          (PluginCommAPI.setLassoBoxState as any)(2),
          new Promise((_, reject) => setTimeout(() => reject(new Error('setLassoBoxState timeout')), 3000)),
        ]);
        console.log('[BackgroundService]: lasso selection cleared');
      } catch (e) {
        console.warn('[BackgroundService]: setLassoBoxState failed (non-fatal):', e);
      }
    }

    const anchorRect = extracted.lassoRect ?? extracted.lastTextBoxRect;

    
    
    
    if (!mosaicBoard && _textInserter && _textInserter.isRunning()) {
      try {
        const pgRes: any = await PluginCommAPI.getCurrentPageNum();
        if (pgRes?.success && typeof pgRes.result === 'number'
            && pgRes.result !== _textInserter.getTargetPage()) {
          console.log('[BackgroundService]: sync targetPage',
            _textInserter.getTargetPage(), '→', pgRes.result, 'before AI anchor');
          _textInserter.relocateTo(pgRes.result);
        }
      } catch (e) {
        console.warn('[BackgroundService]: pre-anchor page sync failed:', e);
      }
    }

    if (!mosaicBoard && _textInserter) {
      let insertionAnchor: InsertionAnchor | null = null;
      let startedHere = false;

      if (_textInserter.isPaused()) {
        
        if (anchorRect) {
          insertionAnchor = setProgrammaticInsertionAnchor(
            anchorRect.bottom + AI_REPLY_GAP_PX,
            anchorRect.left,
            'lasso-resume',
          );
        }
        _textInserter.resume();
      } else if (!_textInserter.isRunning()) {
        _textInserter.clearQueue();
        const ok = await _startTextInserter(_textInserter, 'nospacing');
        if (ok) {
          startedHere = true;
          _activeMode = 'nospacing';
          _pushActiveModes();
          emitInsertMode('nospacing');
          if (anchorRect) {
            insertionAnchor = setProgrammaticInsertionAnchor(
              anchorRect.bottom + AI_REPLY_GAP_PX,
              anchorRect.left,
              'lasso-start',
            );
          }
        }
      } else if (anchorRect) {
        insertionAnchor = setProgrammaticInsertionAnchor(
          anchorRect.bottom + AI_REPLY_GAP_PX,
          anchorRect.left,
          'lasso-running',
        );
      }

      if (insertionAnchor) {
        showTextBubbleAtInsertion(insertionAnchor);
      } else if (startedHere && FloatingBubbleBridge.isAvailable) {
        _aiPositionLocked = false;
        const ps = _textInserter.getPageSize();
        if (ps) {
          FloatingBubbleBridge.setPageHeight(ps.height);
          FloatingBubbleBridge.setPageWidth(ps.width);
        }
        FloatingBubbleBridge.show(_getBubbleStatusText(), _activeMode);
      }
    }

    try { AIRelayModule.sendAlive?.(); } catch (_) {}

    
    
    
    
    let sendResult = 'sent';
    try {
      if (AIRelayModule.sendQueryWithResult) {
        sendResult = await AIRelayModule.sendQueryWithResult(extracted.text);
      } else {
        AIRelayModule.sendQuery(extracted.text);
      }
    } catch (e) {
      console.warn('[BackgroundService]: sendQuery failed', e);
      FileLogger.logEvent('SendToAI', `failed: ${e}`);
      sendResult = 'failed';
    }

    if (sendResult === 'disabled' || sendResult === 'no_endpoint' || sendResult === 'failed') {
      
      
      
      _lastAiSendAt = Date.now();
      const msg = sendResult === 'disabled'
        ? t('bubble_relay_disabled')
        : sendResult === 'no_endpoint'
          ? t('bubble_ai_not_paired')
          : t('bubble_ai_send_failed');
      FileLogger.logEvent('SendToAI', `not delivered (${sendResult})`);
      if (AiBubbleBridge.isAvailable) {
        AiBubbleBridge.updateText(msg);
        setTimeout(() => {
          if (!_aiWaiting && AiBubbleBridge.isAvailable) {
            AiBubbleBridge.updateText(_aiReadyText());
          }
        }, 4000);
      }
      return;
    }

    _aiWaiting = true;
    _lastAiSendAt = Date.now();
    _lastAiSendText = extracted.text;
    if (AiBubbleBridge.isAvailable) {
      
      
      AiBubbleBridge.updateText(
        sendResult === 'queued' ? t('bubble_ai_queued') : t('bubble_ai_waiting'),
      );
    }
    FileLogger.logEvent('SendToAI', `${extracted.text.length} chars (${sendResult})`);

    clearAiTimeout();
    _aiTimeoutRef = setTimeout(() => {
      _aiTimeoutRef = null;
      if (_aiWaiting) {
        console.warn('[BackgroundService]: AI reply timeout');
        FileLogger.logEvent('AiTimeout', 'no reply in 90s');
        if (AiBubbleBridge.isAvailable) {
          AiBubbleBridge.updateText(t('bubble_ai_timeout'));
          setTimeout(() => {
            if (!_aiWaiting) return;
            AiBubbleBridge.updateText(_aiReadyText());
            _aiWaiting = false;
          }, 5000);
        } else {
          _aiWaiting = false;
        }
      }
    }, 90_000);

  } catch (e) {
    console.error('[BackgroundService]: handleAiSend error:', e);
    FileLogger.logEvent('AiSendError', String(e));
  } finally {

    _aiSendInFlight = false;
  }
}


export async function handleFormulaSend(): Promise<void> {
  ensureInit();

  if (_aiSendInFlight) {
    FileLogger.logEvent('FormulaSend', 'skipped (in-flight)');
    return;
  }
  _aiSendInFlight = true;
  reviveBridge();

  const revertBubbleSoon = () => {
    setTimeout(() => {
      if (!_aiWaiting && AiBubbleBridge.isAvailable) AiBubbleBridge.updateText(_aiReadyText());
    }, 2500);
  };

  try {
    if (AiBubbleBridge.isAvailable) AiBubbleBridge.updateText(t('bubble_ai_sending'));
    const res = await sendFormulaQuery();
    if (res === 'ok') {
      _aiWaiting = true;
      _lastAiSendAt = Date.now();
      if (AiBubbleBridge.isAvailable) AiBubbleBridge.updateText(t('bubble_ai_waiting'));

      clearAiTimeout();
      _aiTimeoutRef = setTimeout(() => {
        _aiTimeoutRef = null;
        if (_aiWaiting) {
          _aiWaiting = false;
          FileLogger.logEvent('FormulaSend', 'AI reply timeout');
          if (AiBubbleBridge.isAvailable) {
            AiBubbleBridge.updateText(t('bubble_ai_timeout'));
            revertBubbleSoon();
          }
        }
      }, 90_000);
    } else if (res === 'no-lasso') {
      if (AiBubbleBridge.isAvailable) AiBubbleBridge.updateText(t('bubble_ai_no_lasso'));
      revertBubbleSoon();
    } else if (res === 'no-relay') {
      
      try { NativeUIUtils.showErrorTipDialog(t('formula_no_relay')); } catch (_) {}
      if (AiBubbleBridge.isAvailable) AiBubbleBridge.updateText(_aiReadyText());
    } else {
      if (AiBubbleBridge.isAvailable) AiBubbleBridge.updateText(t('bubble_formula_failed'));
      revertBubbleSoon();
    }
  } catch (e) {
    console.error('[BackgroundService]: handleFormulaSend error:', e);
    FileLogger.logEvent('FormulaSend', `error: ${String(e)}`);
  } finally {
    _aiSendInFlight = false;
  }
}

async function _startLocalSendOnce(): Promise<boolean> {
  if (_localSendStarted) return true;
  try {
    const status = await LocalSendBridge.getServerStatus();
    if (status?.running) {
      _localSendStarted = true;
      return true;
    }
    const canWrite = await FloatingToolbarBridge.requestFileWritePermission();
    if (!canWrite) {
      NativeUIUtils.showErrorTipDialog(t('perm_required'));
      FloatingToolbarBridge.openPluginSettingsAfterFileWritePermissionDenied();
      return false;
    }
    const canNet = await FloatingToolbarBridge.requestInternetPermission();
    if (!canNet) {
      NativeUIUtils.showErrorTipDialog(t('perm_required'));
      return false;
    }
    try {
      await LocalSendBridge.startServer({
        alias: 'Supernote', port: 53317,
        dest: '/sdcard/INBOX', pin: '',
      });
      _localSendStarted = true;
      console.log('[BackgroundService]: LocalSend server started');
      LocalSendBridge.scanForPeers().catch(() => {});
      return true;
    } catch (startErr) {
      console.warn('[BackgroundService]: startLocalSend: LocalSend start failed:', startErr);
      NativeUIUtils.showErrorTipDialog(t('perm_required'));
      return false;
    }
  } catch (e) {
    console.warn('[BackgroundService]: startLocalSend error:', e);
    return false;
  }
}

export async function startLocalSend(showNetworkTip = true): Promise<boolean> {
  const wifi = await LocalSendBridge.isWifiConnected().catch(() => false);
  if (!wifi) {
    _localSendStarted = false;
    if (showNetworkTip) NativeUIUtils.showErrorTipDialog(t('no_wifi'));
    return false;
  }
  if (_localSendStarted) return true;
  if (_localSendStartPromise) return _localSendStartPromise;
  const attempt = _startLocalSendOnce();
  _localSendStartPromise = attempt;
  try {
    return await attempt;
  } finally {
    if (_localSendStartPromise === attempt) _localSendStartPromise = null;
  }
}

export async function stopLocalSend(): Promise<void> {
  try { await LocalSendBridge.stopServer(); } catch (_) {}
  _localSendStarted = false;
  console.log('[BackgroundService]: LocalSend server stopped');
}

export function isLocalSendRunning(): boolean {
  return _localSendStarted;
}

export async function flushPendingTexts(): Promise<number> {
  if (!_textInserter) return 0;
  try {
    let count = 0;
    const localSendTexts: TextReceivedInfo[] = await LocalSendBridge.flushPendingTexts().catch(() => []);
    if (localSendTexts && localSendTexts.length > 0) {
      for (const info of localSendTexts) {
        FileLogger.logTextReceived('LocalSend', info.text);
        if (info._pendingId) LocalSendBridge.ackPendingText(info._pendingId);
        if (_mosaicTextEngaged()) _stageMosaicText(info.text, 'localsend');
        else if (_activeMode) _stagePendingCard(info.text, 'localsend');
        else _textInserter.enqueue(info.text, 'localsend');
      }
      FileLogger.logEvent('FlushPendingLocalSend', `count=${localSendTexts.length}`);
      count += localSendTexts.length;
    }
    return count;
  } catch (e) {
    console.warn('[BackgroundService]: flushPendingTexts error:', e);
    return 0;
  }
}

export async function reviveIfNeeded(): Promise<void> {

  reviveBridge();
  if (!_textInserter || !_activeMode) return;
  if (!_textInserter.hasLiveTimers()) {
    const mode = _activeMode;
    _textInserter.clearQueue();
    const ok = await _startTextInserter(_textInserter, mode, true);
    if (ok) {
      _pushActiveModes();
      emitInsertMode(_activeMode);
    } else {

      console.warn('[BackgroundService]: reviveIfNeeded start() failed, keeping mode=', mode);
      FileLogger.logEvent('ReviveFailed', `mode=${mode}`);
    }
  }
}

export async function handleScreenshotAi(): Promise<void> {
  console.log('[BackgroundService] handleScreenshotAi START (native path)');

  FloatingToolbarBridge.showLassoScreenshotPanelFromBubble();
  FileLogger.logEvent('ScreenshotAi', 'native panel invoked');
}

export async function handleScreenshotSend(): Promise<void> {
  console.log('[BackgroundService] handleScreenshotSend START (native path)');
  
  
  FloatingToolbarBridge.showSmartLassoCapture();
  FileLogger.logEvent('ScreenshotSend', 'native panel invoked');
}

export function restoreBubbleAfterLasso(): void {
  if (_activeMode && FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.show(_getBubbleStatusText(), _activeMode);
  }
  if (_aiActive && AiBubbleBridge.isAvailable) {
    const txt = _aiWaiting ? t('bubble_ai_waiting') : _aiReadyText();
    showAiBubble(txt);
  }
}
