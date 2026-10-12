

import { AppRegistry, Image, DeviceEventEmitter } from 'react-native';
import App from './App';
import { PluginManager, PluginDocAPI, FileUtils } from 'sn-plugin-lib';
import { ensureInit, stopAllModes, startLocalSend, stopLocalSend } from './components/BackgroundService';
import { PluginCommAPI } from 'sn-plugin-lib';
import { loadClips, clipFilledFlags } from './components/ToolPresets';
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
import { getRNFS } from './components/rnfs';

const DOC_SEND_BUTTON_ID = 301;
let docSendBusy = false;

function registerDocSendButton() {
  PluginManager.registerButton(3, ['DOC'], {
    id: DOC_SEND_BUTTON_ID,
    name: getLocale() === 'zh' ? '发给其他设备' : 'Send to other devices',
    icon: Image.resolveAssetSource(require('./assets/smart_lasso.png')).uri,
    showType: 0,
  });
}

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
reportPluginVersion();
if (ENABLE_DEBUG) {
  const names = Object.keys(require('react-native').NativeModules || {});
  console.log('[index] FloatingToolbar available=', FloatingToolbarBridge.isAvailable, 'nativeModules=', names.join(','));
}





async function reportPluginVersion() {
  try {
    const dir = await PluginManager.getPluginDirPath();
    const RNFS = getRNFS();
    if (!dir || !RNFS) return;
    const path = `${dir}/PluginConfig.json`;
    if (!(await RNFS.exists(path))) return;
    const cfg = JSON.parse(await RNFS.readFile(path, 'utf8'));
    if (cfg && cfg.versionName) {
      FloatingToolbarBridge.setPluginVersion(String(cfg.versionName), String(cfg.versionCode ?? ''));
    }
  } catch (e) {
    console.warn('[index]: reportPluginVersion failed:', e);
  }
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
    const filled = clipFilledFlags(clips);
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
    registerDocSendButton();
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
  console.log('[index]: startLocalSendFromNative silent=', !!(evt && evt.silent));
  const ok = await startLocalSend(!(evt && evt.silent));
  if (ok && evt && evt.openClipboardSync) {
    FloatingToolbarBridge.openSendPanelClipboardSync();
  }
});

DeviceEventEmitter.addListener('clipsChanged', async () => {
  console.log('[index]: clipsChanged (from native sync) → refresh title clips');
  await refreshTitleClips();
});

FloatingToolbarBridge.onDestroyAll(() => {
  console.log('[index]: onDestroyAll → stopAllModes + stopLocalSend');
  stopAllModes();
  stopLocalSend();
});

FloatingToolbarBridge.onToolTap(async ({ toolAction }) => {
  console.log('[index]: onToolTap action=', toolAction);

  if (toolAction === 'lasso_send') {
    FloatingToolbarBridge.openPanel('nativeSendHelper');
    return;
  }

  if (isAppMounted()) return;

  const result = await executeAction(toolAction);
  console.log('[index]: executeAction result=', result);

  if (typeof result === 'string' &&
      (result.startsWith('Saved to clip') || (result.startsWith('Clip') && result.endsWith('cleared')))) {
    await refreshTitleClips();
  }
});

FloatingToolbarBridge.onToolLongPress(async ({ toolId }) => {
  if (toolId.startsWith('clip_')) {
    const slot = toolId.split('_')[1];
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

    if (buttonId === DOC_SEND_BUTTON_ID) {
      markHostButtonHandled();
      if (docSendBusy) return;
      docSendBusy = true;
      try {
        const selection = await PluginDocAPI.getLastSelectedText();
        const text = selection?.success && typeof selection.result === 'string'
          ? selection.result : '';
        if (text.trim() && await startLocalSend()) {
          FloatingToolbarBridge.showDocTextSendPanel(text);
        }
      } catch (error) {
        console.warn('[index]: document text send failed:', error);
      } finally {
        docSendBusy = false;
      }
      return;
    }

    if (buttonId === 100 || buttonId === 300) {
      
      
      
      markHostButtonHandled();
      setPendingButton(buttonId);
      
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
  if (FloatingToolbarBridge.isSessionActiveSync()) {
    console.log('[index]: ensure LocalSend for active toolbar session');
    startLocalSend(false);
  } else if (st && st.running) {
    console.log('[index]: leftover LocalSend with no toolbar session → stop');
    stopLocalSend();
  }
}).catch(() => {});

PluginManager.registerButton(1, ['DOC'], {
  id: 300,
  name: 'Inkling',
  icon: Image.resolveAssetSource(require('./assets/toolbar_icon.png')).uri,
  showType: 0,
});

registerDocSendButton();

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
