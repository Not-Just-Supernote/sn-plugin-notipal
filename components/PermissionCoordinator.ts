import { NativeUIUtils, PluginManager } from 'sn-plugin-lib';

import FloatingToolbarBridge, { ENABLE_DEBUG } from './FloatingToolbarBridge';
import { t } from './i18n';

const PERM_FILE_READ = 'plugin.permission.FILE:READ';
const PERM_FILE_WRITE = 'plugin.permission.FILE:WRITE';
const PERM_FILE_DELETE = 'plugin.permission.FILE:DELETE';
const PERM_INTERNET = 'plugin.permission.INTERNET';

const ALL_PERMISSIONS = [PERM_FILE_READ, PERM_FILE_WRITE, PERM_FILE_DELETE, PERM_INTERNET];
const PERMISSION_ALWAYS = 2;


const STATE_START = 2;
const STATE_STOP = 3;




const VISIBLE_WAIT_MS = 5000;     
const SETTLE_MS = 800;            
const REQUEST_TIMEOUT_MS = 8000;  
const POLL_INTERVAL_MS = 500;     


const MAX_ATTEMPTS = 1;

let permissionFlow: Promise<boolean> | null = null;
let pluginVisible = false;
let lastDialogAt = 0;
let visibleWaiters: Array<() => void> = [];


function isGranted(status: unknown): boolean {
  const n = typeof status === 'number' ? status : Number(status);
  return n > 0;
}

function isAlways(status: unknown): boolean {
  const n = typeof status === 'number' ? status : Number(status);
  return n === PERMISSION_ALWAYS;
}




async function requestViaNative(permission: string): Promise<boolean> {
  switch (permission) {
    case PERM_FILE_READ:
      return FloatingToolbarBridge.requestFileReadPermission();
    case PERM_FILE_WRITE:
      return FloatingToolbarBridge.requestFileWritePermission();
    case PERM_FILE_DELETE:
      return FloatingToolbarBridge.requestFileDeletePermission();
    case PERM_INTERNET:
      return FloatingToolbarBridge.requestInternetPermission();
    default:
      return false;
  }
}

function log(message: string, extra?: unknown) {
  if (!ENABLE_DEBUG) return;
  if (extra === undefined) console.error(message);
  else console.error(message, extra);
}

PluginManager.registerPluginLifeListener({
  onMsg(msg: any) {
    const state = Number(msg && msg.state);
    log(`[PermissionCoordinator] plugin life state=${state}`);
    if (state === STATE_START) {
      pluginVisible = true;
      const waiters = visibleWaiters;
      visibleWaiters = [];
      waiters.forEach(resume => resume());
    } else if (state === STATE_STOP) {
      pluginVisible = false;
    }
  },
});

function delay(ms: number): Promise<void> {
  return new Promise(resolve => {
    setTimeout(resolve, ms);
  });
}




function waitForPluginVisible(): Promise<void> {
  if (pluginVisible) return Promise.resolve();
  log('[PermissionCoordinator] waiting for plugin window');
  return new Promise(resolve => {
    let resumed = false;
    const resume = () => {
      if (resumed) return;
      resumed = true;
      resolve();
    };
    visibleWaiters.push(resume);
    setTimeout(resume, VISIBLE_WAIT_MS);
  });
}




function requestOnce(permission: string, description: string): Promise<number | null> {
  let settled = false;

  const request = PluginManager.requestPermission(permission, description)
    .then((status: number) => {
      settled = true;
      return status;
    })
    .catch((error: unknown) => {
      settled = true;
      log(`[PermissionCoordinator] request ${permission} rejected`, error);
      return null;
    });

  const watchdog = (async (): Promise<number | null> => {
    const deadline = Date.now() + REQUEST_TIMEOUT_MS;
    while (!settled && Date.now() < deadline) {
      await delay(POLL_INTERVAL_MS);
      if (settled) break;
      try {
        const status = await PluginManager.hasPermission(permission);
        if (isGranted(status)) return status;
      } catch (_) {
        
      }
    }
    return null;
  })();

  return Promise.race([request, watchdog]);
}

async function requestPermissionStatus(permission: string, description: string): Promise<number | null> {
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    const sinceDialog = Date.now() - lastDialogAt;
    if (sinceDialog < SETTLE_MS) await delay(SETTLE_MS - sinceDialog);

    log(`[PermissionCoordinator] request ${permission} attempt=${attempt}`);
    const result = await requestOnce(permission, description);
    lastDialogAt = Date.now();

    if (result !== null) {
      log(`[PermissionCoordinator] request ${permission} status=${result}`);
      return result;
    }
    log(`[PermissionCoordinator] request ${permission} dropped by host`);
  }
  return null;
}


async function grantPermission(permission: string, description: string): Promise<boolean> {
  try {
    const current = await PluginManager.hasPermission(permission);
    log(`[PermissionCoordinator] has ${permission} status=${current}`);
    if (isGranted(current)) return true;
  } catch (error) {
    log(`[PermissionCoordinator] has ${permission} failed`, error);
  }

  const result = await requestPermissionStatus(permission, description);
  if (result !== null && isGranted(result)) return true;

  const nativeGranted = await requestViaNative(permission);
  log(`[PermissionCoordinator] request ${permission} via native granted=${nativeGranted}`);
  return nativeGranted;
}





async function ensureAlwaysGranted(): Promise<boolean> {
  const notAlways: string[] = [];
  for (const perm of ALL_PERMISSIONS) {
    try {
      const status = await PluginManager.hasPermission(perm);
      if (!isAlways(status)) notAlways.push(perm);
    } catch (_) {
      notAlways.push(perm);
    }
  }
  if (notAlways.length === 0) return true;

  log(`[PermissionCoordinator] not always: ${notAlways.join(', ')}`);
  const confirmed = await NativeUIUtils.showRattaDialog(
    t('perm_always_required'), t('btn_cancel'), t('btn_confirm'), false,
  ).catch(() => false);
  if (!confirmed) return false;

  
  lastDialogAt = Date.now();
  for (const perm of notAlways) {
    const result = await requestPermissionStatus(perm, t('perm_always_required'));
    if (result === null || !isAlways(result)) return false;
  }
  return true;
}

async function runInitialPermissionFlow(): Promise<boolean> {
  
  const steps: Array<{
    permission: string;
    descKey: 'perm_desc_file_read' | 'perm_desc_file_write' | 'perm_desc_file_delete' | 'perm_desc_internet';
  }> = [
    { permission: PERM_FILE_READ, descKey: 'perm_desc_file_read' },
    { permission: PERM_FILE_WRITE, descKey: 'perm_desc_file_write' },
    { permission: PERM_FILE_DELETE, descKey: 'perm_desc_file_delete' },
    { permission: PERM_INTERNET, descKey: 'perm_desc_internet' },
  ];

  for (const step of steps) {
    if (!await grantPermission(step.permission, t(step.descKey))) {
      log(`[PermissionCoordinator] ${step.permission} denied`);
      return false;
    }
  }

  
  
  
  if (!await ensureAlwaysGranted()) {
    log('[PermissionCoordinator] running without permanent access');
  }

  return true;
}

export function ensureInitialPermissions(): Promise<boolean> {
  if (permissionFlow) return permissionFlow;

  permissionFlow = runInitialPermissionFlow()
    .then(ready => {
      if (!ready) {
        NativeUIUtils.showErrorTipDialog(t('initial_permissions_required'));
      }
      return ready;
    })
    .catch(error => {
      log('[PermissionCoordinator][FLOW-EXCEPTION]', error);
      NativeUIUtils.showErrorTipDialog(t('initial_permissions_required'));
      return false;
    })
    .finally(() => {
      permissionFlow = null;
    });

  return permissionFlow;
}
