


import { PluginNoteAPI, PluginFileAPI, PluginCommAPI } from 'sn-plugin-lib';
import FloatingToolbarBridge from './FloatingToolbarBridge';
import { clampHostPageRect, getCurrentHostPageSize, getHostPageSize } from './HostPageCoordinates';

export type LassoRect = { left: number; top: number; right: number; bottom: number };


let lassoTxnInFlight = false;

export function isLassoTxnBusy(): boolean {
  return lassoTxnInFlight;
}

export function beginLassoTxn(): boolean {
  if (lassoTxnInFlight) return false;
  lassoTxnInFlight = true;
  return true;
}

export function endLassoTxn(): void {
  lassoTxnInFlight = false;
}



export async function hostCall(promise: Promise<any>, tag: string): Promise<any> {
  try {
    return await promise;
  } catch (e) {
    console.warn(`[PLT/${tag}] host request failed:`, e);
    return null;
  }
}





export async function prepareInsertedStickerSelection(): Promise<boolean> {
  if (lassoTxnInFlight) return false;
  if (!await FloatingToolbarBridge.hasPendingDocSticker()) return true;
  let pageElements: any[] = [];
  let selectedBefore: any[] = [];
  let commitStarted = false;
  lassoTxnInFlight = true;
  FloatingToolbarBridge.beginPaletteApply();
  try {
    const fp: any = await hostCall(PluginCommAPI.getCurrentFilePath() as Promise<any>, 'paste/file');
    const pg: any = await hostCall(PluginCommAPI.getCurrentPageNum() as Promise<any>, 'paste/page');
    if (!fp?.success || !pg?.success || !Number.isInteger(pg.result)) return false;
    const before: any = await hostCall(
      PluginCommAPI.getLassoElements() as Promise<any>, 'paste/selected-before');
    if (!before?.success || !Array.isArray(before.result)) return false;
    selectedBefore = before.result;
    
    
    
    let previewRect: { left: number; top: number; right: number; bottom: number } | null = null;
    try {
      const preview: any = await hostCall(
        PluginCommAPI.getLassoRect() as Promise<any>, 'paste/preview-rect',
      );
      const candidate = preview?.success ? rectFromHost(preview.result) : null;
      if (candidate && (candidate.left > 1 || candidate.top > 1)) previewRect = candidate;
    } catch (_) {}
    const selectedNums = new Set<number>(selectedBefore
      .filter(isPaletteInk)
      .map(el => el.numInPage)
      .filter((n: any): n is number => Number.isInteger(n)));
    if (selectedNums.size === 0) {
      
      
      console.log('[PLT/paste] no floating sticker left; clearing stale pending marker');
      FloatingToolbarBridge.clearPendingDocSticker();
      return true;
    }
    
    
    const closed: any = await hostCall(PluginCommAPI.setLassoBoxState(2) as Promise<any>, 'paste/commit');
    console.log(`[PLT/paste] setLassoBoxState(2) ok=${closed?.success}`);
    commitStarted = true;
    await waitForLassoCommit('paste');
    
    
    const saved: any = await hostCall(PluginNoteAPI.saveCurrentNote() as Promise<any>, 'paste/save');
    if (!saved?.success) {
      console.warn('[PLT/paste] saveCurrentNote failed, cannot read committed sticker');
      return false;
    }
    try { (PluginCommAPI as any).clearElementCache?.(); } catch (_) {}
    const size = await getHostPageSize(fp.result, pg.result, 'paste/page-size');
    if (!size) return false;

    let inserted: any[] = [];
    for (let attempt = 0; attempt < 3; attempt++) {
      for (const el of pageElements) { try { el?.recycle?.(); } catch (_) {} }
      pageElements = [];
      const fetched = await getElementsStable(pg.result, fp.result, 'paste/committed');
      if (!fetched.ok) return false;
      pageElements = fetched.els;
      inserted = pageElements.filter(el => selectedNums.has(el.numInPage) && isPaletteInk(el));
      if (inserted.length > 0) break;
    }
    if (inserted.length === 0) {
      console.warn(`[PLT/paste] committed sticker not found: want=${JSON.stringify([...selectedNums])}`);
      return false;
    }
    if (inserted.length !== selectedNums.size) {
      
      
      console.warn(`[PLT/paste] partial ${inserted.length}/${selectedNums.size}, proceeding`);
    }
    const pointRect = await committedInkRect(inserted, size);
    if (!pointRect) return false;
    
    
    
    const rect = previewRect ? {
      left: Math.max(0, Math.floor(Math.min(previewRect.left, pointRect.left)) - 5),
      top: Math.max(0, Math.floor(Math.min(previewRect.top, pointRect.top)) - 5),
      right: Math.min(size.width, Math.ceil(Math.max(previewRect.right, pointRect.right)) + 5),
      bottom: Math.min(size.height, Math.ceil(Math.max(previewRect.bottom, pointRect.bottom)) + 5),
    } : {
      left: Math.max(0, Math.floor(pointRect.left) - 32),
      top: Math.max(0, Math.floor(pointRect.top) - 32),
      right: Math.min(size.width, Math.ceil(pointRect.right) + 32),
      bottom: Math.min(size.height, Math.ceil(pointRect.bottom) + 32),
    };
    const expected = inserted.map(el => el.numInPage);
    
    
    
    
    const shown = await reselectLasso(rect, 'paste/ready');
    if (!shown) return false;
    FloatingToolbarBridge.parkToolbarForLasso(true);
    console.log(`[PLT/paste] committed nums=${JSON.stringify(expected)} pageRect=${JSON.stringify(rect)} selected=${shown}`);
    return shown;
  } catch (e) {
    console.warn('[PLT/paste] cannot prepare committed sticker selection:', e);
    return false;
  } finally {
    for (const el of selectedBefore) { try { el?.recycle?.(); } catch (_) {} }
    for (const el of pageElements) { try { el?.recycle?.(); } catch (_) {} }
    
    
    if (commitStarted) FloatingToolbarBridge.clearPendingDocSticker();
    lassoTxnInFlight = false;
    FloatingToolbarBridge.paletteApplyFinished();
  }
}

export async function committedInkRect(elements: any[], size: { width: number; height: number }) {
  let left = Infinity, top = Infinity, right = -Infinity, bottom = -Infinity;
  for (const el of elements) {
    let points: any[];
    if (el.type === 0 && el.stroke?.points) {
      const count = await el.stroke.points.size();
      if (!(count > 0)) return null;
      
      
      points = await el.stroke.points.getRange(0, count);
    } else if (el.type === 700 && el.geometry?.points?.length) {
      points = el.geometry.points;
    } else return null;
    if (!points.length) return null;
    for (const p of points) {
      if (!Number.isFinite(p.x) || !Number.isFinite(p.y)) return null;
      left = Math.min(left, p.x); top = Math.min(top, p.y);
      right = Math.max(right, p.x); bottom = Math.max(bottom, p.y);
    }
  }
  return rectFromHost({
    left: Math.max(0, Math.floor(left) - 5), top: Math.max(0, Math.floor(top) - 5),
    right: Math.min(size.width, Math.ceil(right) + 5), bottom: Math.min(size.height, Math.ceil(bottom) + 5),
  });
}




export async function targetInkRect(elements: any[], tag: string) {
  try {
    const size = await getCurrentHostPageSize(`${tag}/ink-rect-page-size`);
    if (!size) return null;
    const r = await committedInkRect(elements, size);
    if (!r) return null;
    return rectFromHost({
      left: Math.max(0, r.left - 32), top: Math.max(0, r.top - 32),
      right: Math.min(size.width, r.right + 32), bottom: Math.min(size.height, r.bottom + 32),
    });
  } catch (e) {
    console.warn(`[PLT/${tag}] ink rect failed (non-fatal):`, e);
    return null;
  }
}




export async function getElementsStable(pageNum: number, filePath: string, tag: string): Promise<{ ok: boolean; els: any[] }> {
  for (let attempt = 0; attempt < 5; attempt++) {
    const res: any = await hostCall(
      PluginFileAPI.getElements(pageNum, filePath) as Promise<any>,
      `${tag}/elements-${attempt + 1}`,
    );
    if (!res?.success || !Array.isArray(res.result)) {
      console.warn(`[PLT/${tag}] getElements FAIL ok=${res?.success}`);
      return { ok: false, els: [] };
    }
    if (res.result.length > 0) return { ok: true, els: res.result };
    console.log(`[PLT/${tag}] getElements empty (attempt ${attempt + 1}) — page still reloading, retrying…`);
    for (const el of res.result) { try { el?.recycle?.(); } catch (_) {} }
    
    
  }
  console.warn(`[PLT/${tag}] getElements still empty after retries`);
  return { ok: true, els: [] };
}

export function isPaletteInk(el: any): boolean {
  return el?.type === 0 || el?.type === 700;
}

export function rectFromHost(r: any): { left: number; top: number; right: number; bottom: number } | null {
  if (!r || ![r.left, r.top, r.right, r.bottom].every((n) => typeof n === 'number' && Number.isFinite(n))) return null;
  if ((r.right - r.left) < 2 || (r.bottom - r.top) < 2) return null;
  return { left: r.left, top: r.top, right: r.right, bottom: r.bottom };
}


export function pickReplacementInk(
  pool: any[],
  wantCount: number,
  oldNums: number[],
  oldUuids: string[],
): any[] {
  if (wantCount <= 0) return [];
  const numSet = new Set(oldNums);
  const uuidSet = new Set(oldUuids);
  const ink = pool.filter(isPaletteInk);
  const newcomers = ink.filter((el: any) => {
    const u = el?.uuid;
    if (typeof u === 'string' && uuidSet.has(u)) return false;
    if (el?.numInPage != null && numSet.has(el.numInPage)) return false;
    return true;
  });
  if (newcomers.length === wantCount) return newcomers;
  const oldNumsGone = oldNums.every((n) => !ink.some((el: any) => el?.numInPage === n));
  if (oldNumsGone && ink.length === wantCount) return ink;
  return [];
}



export type InkShape = { type: number; count: number; samples: { x: number; y: number }[] };

export async function inkShape(el: any): Promise<InkShape | null> {
  try {
    let count = 0;
    let pick: (i: number) => Promise<{ x: number; y: number } | null>;
    if (el?.type === 0 && el.stroke?.points) {
      count = await el.stroke.points.size();
      pick = async (i) => {
        const r = await el.stroke.points.getRange(i, 1);
        return r?.[0] ?? null;
      };
    } else if (el?.type === 700 && Array.isArray(el.geometry?.points)) {
      count = el.geometry.points.length;
      pick = async (i) => el.geometry.points[i] ?? null;
    } else return null;
    if (!(count > 0)) return null;
    const idx = [...new Set([0, count >> 2, count >> 1, (count * 3) >> 2, count - 1])];
    const samples: { x: number; y: number }[] = [];
    for (const i of idx) {
      const p = await pick(i);
      if (!p || !Number.isFinite(p.x) || !Number.isFinite(p.y)) return null;
      samples.push({ x: p.x, y: p.y });
    }
    return { type: el.type, count, samples };
  } catch (_) {
    return null;
  }
}


export async function matchMovedInk(pool: any[], wanted: InkShape[]): Promise<any[]> {
  if (wanted.length === 0) return [];
  const TOL = 4;
  const near = (a: number, b: number) => Math.abs(a - b) <= TOL;
  const sameShape = (a: InkShape, b: InkShape) =>
    a.type === b.type && a.count === b.count && a.samples.length === b.samples.length &&
    a.samples.every((p, i) =>
      near(p.x - a.samples[0].x, b.samples[i].x - b.samples[0].x) &&
      near(p.y - a.samples[0].y, b.samples[i].y - b.samples[0].y));
  const counts = new Set(wanted.map((w) => w.count));
  const cands: { el: any; shape: InkShape }[] = [];
  for (const el of pool.filter(isPaletteInk)) {
    const shape = await inkShape(el);
    if (shape && counts.has(shape.count)) cands.push({ el, shape });
  }
  
  for (const first of cands.filter((c) => sameShape(wanted[0], c.shape))) {
    const dx = first.shape.samples[0].x - wanted[0].samples[0].x;
    const dy = first.shape.samples[0].y - wanted[0].samples[0].y;
    const used = new Set<any>([first.el]);
    const picked = [first.el];
    for (const w of wanted.slice(1)) {
      const hit = cands.find((c) => !used.has(c.el) && sameShape(w, c.shape) &&
        near(c.shape.samples[0].x - w.samples[0].x, dx) &&
        near(c.shape.samples[0].y - w.samples[0].y, dy));
      if (!hit) break;
      used.add(hit.el);
      picked.push(hit.el);
    }
    if (picked.length === wanted.length) return picked;
  }
  return [];
}



export async function lassoNumsInRect(
  rect: { left: number; top: number; right: number; bottom: number },
  tag: string,
): Promise<number[]> {
  let nums: number[] = [];
  try {
    const lr: any = await PluginCommAPI.lassoElements(rect);
    if (lr?.success && lr.result !== false) {
      const fresh: any = await PluginCommAPI.getLassoElements();
      if (fresh?.success && Array.isArray(fresh.result)) {
        nums = fresh.result
          .map((el: any) => el?.numInPage)
          .filter((n: any): n is number => Number.isInteger(n));
        for (const el of fresh.result) { try { el?.recycle?.(); } catch (_) {} }
      }
    }
    await hostCall((PluginCommAPI as any).setLassoBoxState?.(2) as Promise<any>, `${tag}/re-lasso-close`);
  } catch (e) {
    console.warn(`[PLT/${tag}] re-lasso failed (non-fatal):`, e);
  }
  console.log(`[PLT/${tag}] re-lasso rect=${JSON.stringify(rect)} nums=${JSON.stringify(nums)}`);
  return nums;
}








export async function waitForLassoCommit(tag: string, attempts = 40): Promise<boolean> {
  for (let i = 0; i < attempts; i++) {
    const res: any = await hostCall(
      PluginCommAPI.getLassoRect() as Promise<any>, `${tag}/commit-probe`);
    if (res?.success === false) {
      console.log(`[PLT/${tag}] lasso commit observed after ${i + 1} probe(s)`);
      return true;
    }
  }
  console.warn(`[PLT/${tag}] lasso commit not observed after ${attempts} probes; proceeding anyway`);
  return false;
}




export async function reselectLasso(
  rect: { left: number; top: number; right: number; bottom: number },
  tag: string,
): Promise<boolean> {
  console.log(`[PLT/${tag}] reselect start rect=${JSON.stringify(rect)}`);
  const size = await getCurrentHostPageSize(`${tag}/page-size`);
  const clamped = size ? clampHostPageRect(rect, size) : null;
  if (!clamped) {
    console.warn(`[PLT/${tag}] reselect skipped: degenerate rect=${JSON.stringify(clamped)}`);
    return false;
  }
  try {
    
    
    
    for (let attempt = 1; attempt <= 4; attempt++) {
      if (attempt > 1) await new Promise(resolve => setTimeout(resolve, 180));
      const lr: any = await PluginCommAPI.lassoElements(clamped);
      console.log(`[PLT/${tag}] reselect lassoElements attempt=${attempt} rect=${JSON.stringify(clamped)} ok=${lr?.success} result=${lr?.result}`);
      if (!lr?.success || lr.result === false || lr.result == null) continue;
      const shown: any = await (PluginCommAPI as any).setLassoBoxState?.(0);
      console.log(`[PLT/${tag}] reselect setLassoBoxState(0) ok=${shown?.success}`);
      return true;
    }
    return false;
  } catch (e) {
    console.warn(`[PLT/${tag}] reselect failed (non-fatal):`, e);
    return false;
  }
}

export type CommittedSelection = {
  
  lassoRectBefore: LassoRect | null;
  
  pageEls: any[];
  
  targets: any[];
  
  numsStale: boolean;
};


export async function commitSelectionAndRead(opts: {
  tag: string;
  pageNum: number;
  filePath: string;
  elementNums: number[];
  prefetchedEls?: any[] | null;
  fallbackRect?: LassoRect | null;
  accept?: (el: any) => boolean;
  t0?: number;
}): Promise<CommittedSelection | null> {
  const { tag, pageNum, filePath, elementNums } = opts;
  const accept = opts.accept ?? isPaletteInk;
  const prefetchedEls = opts.prefetchedEls ?? [];
  const t0 = opts.t0 ?? Date.now();

  
  
  
  let lassoRectBefore: LassoRect | null = null;
  try {
    const lrRes: any = await PluginCommAPI.getLassoRect();
    if (lrRes?.success) lassoRectBefore = rectFromHost(lrRes.result);
  } catch (e) {
    console.warn(`[PLT/${tag}] getLassoRect failed (non-fatal):`, e);
  }
  if (!lassoRectBefore && opts.fallbackRect) {
    lassoRectBefore = opts.fallbackRect;
    console.log(`[PLT/${tag}] lassoRect fallback to captured snapshot`);
  }
  console.log(`[PLT/${tag}] lassoRect before=${JSON.stringify(lassoRectBefore)}`);

  
  
  const wantShapes: InkShape[] = [];
  for (const el of prefetchedEls.filter(isPaletteInk)) {
    const shape = await inkShape(el);
    if (!shape) { wantShapes.length = 0; break; }
    wantShapes.push(shape);
  }
  console.log(`[PLT/${tag}] shapes captured=${wantShapes.length}`);

  
  
  
  
  const closed: any = await hostCall(
    (PluginCommAPI as any).setLassoBoxState?.(2) as Promise<any>, `${tag}/close`);
  console.log(`[PLT/${tag}] close lasso box before modify ok=${closed?.success}`);
  
  
  await waitForLassoCommit(tag);

  
  
  const wantUuids: string[] = prefetchedEls
    .map((el: any) => el?.uuid)
    .filter((u: any): u is string => typeof u === 'string' && u.length > 0);
  for (const el of prefetchedEls) { try { el?.recycle?.(); } catch (_) {} }

  
  
  
  const saved: any = await hostCall(PluginNoteAPI.saveCurrentNote() as Promise<any>, `${tag}/save`);
  console.log(`[PLT/${tag}] saveCurrentNote after lasso commit ok=${saved?.success} +${Date.now() - t0}ms`);
  
  
  
  try { (PluginCommAPI as any).clearElementCache?.(); } catch (_) {}
  const fetched = await getElementsStable(pageNum, filePath, tag);
  if (!fetched.ok) return null;
  const pageEls = fetched.els;

  const numSet = new Set(elementNums);
  let targets = pageEls.filter(
    (el: any) => el?.numInPage != null && numSet.has(el.numInPage) && accept(el));

  let numsStale = false;
  if (targets.length === 0) {
    numsStale = true;
    
    
    
    const uuidSet = new Set(wantUuids);
    if (uuidSet.size > 0) {
      targets = pageEls.filter(
        (el: any) => typeof el?.uuid === 'string' && uuidSet.has(el.uuid) && accept(el));
      if (targets.length > 0) console.warn(`[PLT/${tag}] nums stale -> uuid match ${targets.length}/${wantUuids.length}`);
    }
    if (targets.length === 0 && wantShapes.length > 0) {
      targets = (await matchMovedInk(pageEls, wantShapes)).filter(accept);
      if (targets.length > 0) {
        console.warn(`[PLT/${tag}] nums stale -> shape match nums=${JSON.stringify(targets.map((el: any) => el?.numInPage))}`);
      }
    }
    if (targets.length === 0) {
      targets = pickReplacementInk(pageEls, elementNums.length, elementNums, wantUuids).filter(accept);
      if (targets.length > 0) {
        console.warn(`[PLT/${tag}] nums stale -> replacement nums=${JSON.stringify(targets.map((el: any) => el?.numInPage))}`);
      }
    }
    if (targets.length === 0 && lassoRectBefore) {
      const freshNums = new Set(await lassoNumsInRect(lassoRectBefore, tag));
      targets = pageEls.filter(
        (el: any) => el?.numInPage != null && freshNums.has(el.numInPage) && accept(el));
      if (targets.length > 0) {
        console.warn(`[PLT/${tag}] nums stale -> re-lasso nums=${JSON.stringify(targets.map((el: any) => el?.numInPage))}`);
      }
    }
  }
  if (targets.length === 0) {
    
    
    const pool = pageEls.map((el: any) => `${el?.numInPage}:${el?.type}`);
    console.warn(`[PLT/${tag}] no targets: want=${JSON.stringify(elementNums)} pool=${JSON.stringify(pool)}`);
  } else if (targets.length !== elementNums.length) {
    console.warn(`[PLT/${tag}] partial match ${targets.length}/${elementNums.length}, proceeding`);
  }
  return { lassoRectBefore, pageEls, targets, numsStale };
}


export async function pickReselectRect(sel: CommittedSelection, tag: string): Promise<LassoRect | null> {
  if (!sel.numsStale) return sel.lassoRectBefore;
  const inkRect = await targetInkRect(sel.targets, tag);
  if (!inkRect) return sel.lassoRectBefore;
  console.log(`[PLT/${tag}] reselect rect from moved ink=${JSON.stringify(inkRect)} (lasso said ${JSON.stringify(sel.lassoRectBefore)})`);
  return inkRect;
}
