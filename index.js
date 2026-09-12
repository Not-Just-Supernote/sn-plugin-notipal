

import { AppRegistry, Image, DeviceEventEmitter } from 'react-native';
import App from './App';
import { PluginManager, FileUtils } from 'sn-plugin-lib';
import { ensureInit, stopAllModes, startLocalSend } from './components/BackgroundService';
import { PluginCommAPI } from 'sn-plugin-lib';
import { loadClips } from './components/ToolPresets';
import FloatingToolbarBridge, {
  ENABLE_DEBUG,
  setPendingButton,
  isAppMounted,
  markHostButtonEvent,
  markHostButtonHandled,
} from './components/FloatingToolbarBridge';
import LocalSendBridge from './components/LocalSendBridge';
import { executeAction } from './components/ToolActions';
import { setLocale, getLocale } from './components/i18n';
import { ensureInitialPermissions } from './components/PermissionCoordinator';
import { normalizeToolAction, normalizeToolId } from './components/ToolIdentifiers';

if (!ENABLE_DEBUG) {
  console.log = () => {};
  console.warn = () => {};
  console.info = () => {};
  console.debug = () => {};
}

AppRegistry.registerComponent('me_laumss_notipal', () => App);

PluginManager.init();
setLocale(getLocale());
ensureInit();
if (ENABLE_DEBUG) {
  const names = Object.keys(require('react-native').NativeModules || {});
  console.log('[index] FloatingToolbar available=', FloatingToolbarBridge.isAvailable, 'nativeModules=', names.join(','));
}




DeviceEventEmitter.addListener('plugin_button_event', (msg) => {
  markHostButtonEvent(msg && msg.id);
  FloatingToolbarBridge.reportHostButtonChannel(true);
  FloatingToolbarBridge.reportHostButtonRaw('probe ' + JSON.stringify(msg));
  console.log('[index] raw plugin_button_event:', JSON.stringify(msg));
});
async function refreshTitleClips() {
  try {
    const clips = await loadClips();
    const filled = [1, 2, 3, 4, 5, 6].map(n => !!clips[String(n)]);
    FloatingToolbarBridge.updateTitleClips(filled);
  } catch (e) {
    console.warn('[index]: refreshTitleClips failed:', e);
  }
}

PluginManager.registerLangListener({
  onMsg(msg) {
    const lang = msg.lang || '';
    const locale = lang.toLowerCase().startsWith('zh') ? 'zh' : 'en';
    setLocale(locale);
  },
});

DeviceEventEmitter.addListener('nativeDeleteFile', async (evt) => {
  if (evt && evt.path) {
    try {
      await FileUtils.deleteFile(evt.path);
    } catch (e) {
      console.warn('[index]: nativeDeleteFile failed:', e);
    }
  }
});

DeviceEventEmitter.addListener('startLocalSendFromNative', async (evt) => {
  console.log('[index]: startLocalSendFromNative');
  const ok = await startLocalSend();
  if (ok && evt && evt.openClipboardSync) {
    FloatingToolbarBridge.openSendPanelClipboardSync();
  }
});

DeviceEventEmitter.addListener('clipsChanged', async () => {
  console.log('[index]: clipsChanged (from native sync) → refresh title clips');
  await refreshTitleClips();
});

FloatingToolbarBridge.onDestroyAll(() => {
  console.log('[index]: onDestroyAll → stopAllModes');
  stopAllModes();
});

FloatingToolbarBridge.onToolTap(async ({ toolAction }) => {
  const canonicalAction = normalizeToolAction(toolAction);
  console.log('[index]: onToolTap action=', toolAction, '=>', canonicalAction);

  if (canonicalAction === 'lasso_send') {
    FloatingToolbarBridge.openPanel('nativeSendHelper');
    return;
  }

  if (isAppMounted()) return;

  const result = await executeAction(canonicalAction);
  console.log('[index]: executeAction result=', result);

  if (typeof result === 'string' &&
      (result.startsWith('Saved to clip') || (result.startsWith('Clip') && result.endsWith('cleared')))) {
    await refreshTitleClips();
  }
});

FloatingToolbarBridge.onToolLongPress(async ({ toolId }) => {
  const canonicalId = normalizeToolId(toolId);
  if (canonicalId.startsWith('clip_')) {
    const slot = canonicalId.split('_')[1];
    await executeAction(`clip_clear_${slot}`);
    await refreshTitleClips();
  }
});

FloatingToolbarBridge.onTitleClipLongPress(async ({ slot }) => {
  const clips = await loadClips();
  if (clips[slot]) {
    await executeAction(`clip_clear_${slot}`);
  } else {
    const result = await executeAction(`clip_save_clear_${slot}`);
    console.log('[index]: blank clip long press result=', result);
  }
  await refreshTitleClips();
});

PluginManager.registerButtonListener({
  async onButtonPress(event) {
    FloatingToolbarBridge.reportHostButtonRaw('listener id=' + (event && event.id));
    const buttonId = Number(event && event.id);
    if (!Number.isFinite(buttonId)) {
      console.warn('[index]: ignored host button event without numeric id:', event);
      return;
    }
    console.log('[index]: button id=', buttonId);

    if (buttonId === 100 || buttonId === 300) {
      
      
      
      markHostButtonHandled();
      setPendingButton(buttonId);
      
      const ready = await ensureInitialPermissions();
      if (!ready) {
        setPendingButton(null);
        
        try { PluginManager.closePluginView(); } catch (_) {}
        return;
      }
      
      
      setPendingButton(null);
    }

    if (buttonId === 100 || buttonId === 300) {
      const toolbarMode = buttonId === 300 ? 'doc_notipal' : 'note_notipal';
      console.log('[index]: toolbar entry=', toolbarMode, 'available=', FloatingToolbarBridge.isAvailable, 'showing=', FloatingToolbarBridge.isShowingSync());
      stopAllModes();
      const invoked = FloatingToolbarBridge.toggleFromPluginButton(toolbarMode);
      
      setPendingButton(null);
      console.log('[index]: native toolbar toggle invoked=', invoked, 'mode=', toolbarMode);
      refreshTitleClips();
      return;
    }

  },
});

PluginManager.registerButton(1, ['NOTE'], {
  id: 100,
  name: 'Inkling',
  icon: Image.resolveAssetSource(require('./assets/toolbar_icon.png')).uri,
  showType: 0,
});




LocalSendBridge.getServerStatus().then(st => {
  if (st && st.running) {
    console.log('[index]: adopting already-running LocalSend server');
    startLocalSend(false);
  }
}).catch(() => {});

PluginManager.registerButton(1, ['DOC'], {
  id: 300,
  name: 'Inkling',
  icon: Image.resolveAssetSource(require('./assets/toolbar_icon.png')).uri,
  showType: 0,
});

if (ENABLE_DEBUG) {
  PluginManager.registerConfigButton();
  PluginManager.registerConfigButtonListener({
    onClick() {
      console.log('[index]: config button clicked → opening main panel');
      setPendingButton(999);
      DeviceEventEmitter.emit('quickToolbarButton', { id: 999 });
    },
  });
}
