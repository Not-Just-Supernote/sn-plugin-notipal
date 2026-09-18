import { PluginCommAPI, PluginNoteAPI, PluginFileAPI } from 'sn-plugin-lib';

const TAG = '[ElementOps]';
const MARKER_PT = 11;

export function layerIdOf(layer: any): number {
  return layer?.layerId !== undefined ? layer.layerId : layer?.layerNum;
}

function asLayer(el: any): number {
  const n = el?.layerNum;
  return typeof n === 'number' && n >= 0 && n <= 3 ? n : 0;
}


export async function primePageDisplaySize(): Promise<void> {
  try {
    await PluginCommAPI.getPageDisplaySize();
  } catch (e) {
    console.warn(TAG, 'getPageDisplaySize failed:', e);
  }
}


export async function saveCurrentPage(reason: string): Promise<boolean> {
  try {
    const res: any = await PluginNoteAPI.saveCurrentNote();
    if (res?.success === false) {
      console.warn(TAG, `saveCurrentNote failed reason=${reason}`, res.error);
      return false;
    }
    return true;
  } catch (e) {
    console.warn(TAG, `saveCurrentNote error reason=${reason}:`, e);
    return false;
  }
}

function groupByLayer(elements: any[]): Map<number, any[]> {
  const groups = new Map<number, any[]>();
  for (const el of elements) {
    const layer = asLayer(el);
    const list = groups.get(layer);
    if (list) list.push(el);
    else groups.set(layer, [el]);
  }
  return groups;
}


export async function modifyLiveElements(elements: any[], page: number): Promise<any> {
  if (!elements.length) return { success: true, result: [] };
  await primePageDisplaySize();
  const modified: number[] = [];
  for (const [layer, els] of groupByLayer(elements)) {
    console.log(TAG, `modifyPageElements n=${els.length} page=${page} layer=${layer}`);
    const res: any = await PluginCommAPI.modifyPageElements(els, page, layer);
    console.log(TAG, `modifyPageElements ok=${res?.success} err=${res?.error?.message ?? ''} result=${JSON.stringify(res?.result)}`);
    if (!res?.success) return res;
    if (Array.isArray(res.result)) modified.push(...res.result);
  }
  return { success: true, result: modified };
}







export async function modifyFileElements(
  filePath: string,
  page: number,
  elements: any[],
): Promise<any> {
  if (!elements.length) return { success: true, result: [] };
  console.log(TAG, `modifyElements(file) n=${elements.length} page=${page}`);
  const res: any = await (PluginFileAPI as any).modifyElements(filePath, page, elements);
  console.log(TAG, `modifyElements(file) ok=${res?.success} err=${res?.error?.message ?? ''} result=${JSON.stringify(res?.result)}`);
  return res;
}


export async function moveLiveElements(
  page: number,
  toInsertByTargetLayer: Map<number, any[]>,
  toDeleteBySourceLayer: Map<number, number[]>,
): Promise<{ ok: boolean; error?: string }> {
  await primePageDisplaySize();

  for (const [layer, els] of toInsertByTargetLayer) {
    if (!els.length) continue;
    console.log(TAG, `insertPageElements n=${els.length} page=${page} layer=${layer}`);
    const res: any = await PluginCommAPI.insertPageElements(els, page, layer);
    console.log(TAG, `insertPageElements ok=${res?.success} err=${res?.error?.message ?? ''}`);
    if (!res?.success) {
      return { ok: false, error: res?.error?.message ?? 'insertPageElements failed' };
    }
  }

  for (const [layer, nums] of toDeleteBySourceLayer) {
    if (!nums.length) continue;
    console.log(TAG, `deletePageElements n=${nums.length} page=${page} layer=${layer}`);
    const res: any = await PluginCommAPI.deletePageElements(nums, page, layer);
    console.log(TAG, `deletePageElements ok=${res?.success} err=${res?.error?.message ?? ''}`);
    if (!res?.success) {
      return { ok: false, error: res?.error?.message ?? 'deletePageElements failed' };
    }
  }

  return { ok: true };
}


export async function ensureMarkerDirection(el: any): Promise<void> {
  const stroke = el?.stroke;
  if (!stroke || el?.type !== 0) return;
  const dir = stroke.markPenDirection;
  const pts = stroke.points;
  if (!dir || typeof dir.size !== 'function' || !pts || typeof pts.size !== 'function') return;

  let dirN = 0;
  try { dirN = await dir.size(); } catch (_) { return; }
  if (dirN > 0) return;

  let n = 0;
  try { n = await pts.size(); } catch (_) { return; }
  if (n < 1) return;

  const points = await pts.getRange(0, n);
  if (!points?.length) return;

  const dirs: { x: number; y: number }[] = [];
  for (let i = 0; i < points.length; i++) {
    const a = points[Math.max(0, i - 1)] ?? points[i];
    const b = points[Math.min(points.length - 1, i + 1)] ?? points[i];
    let dx = (b?.x ?? 0) - (a?.x ?? 0);
    let dy = (b?.y ?? 0) - (a?.y ?? 0);
    const len = Math.hypot(dx, dy);
    if (len < 1e-3) { dx = 1; dy = 0; }
    else { dx /= len; dy /= len; }
    dirs.push({ x: dx, y: dy });
  }

  const ok = await dir.setRange(0, dirs.length - 1, dirs);
  console.log(TAG, `ensureMarkerDirection uuid=${el.uuid} n=${dirs.length} ok=${ok}`);
}

export function isMarkerStroke(el: any): boolean {
  return el?.type === 0 && el?.stroke?.penType === MARKER_PT;
}

export { MARKER_PT };
