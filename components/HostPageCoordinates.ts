import { PluginCommAPI, PluginFileAPI } from 'sn-plugin-lib';


export interface HostPageSize {
  width: number;
  height: number;
}

export interface HostPageRect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

function validDimension(value: any): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

function pageSizeFromResult(result: any): HostPageSize | null {
  if (!validDimension(result?.width) || !validDimension(result?.height)) return null;
  return { width: Math.round(result.width), height: Math.round(result.height) };
}


export async function getHostPageSize(
  filePath: string,
  pageNum: number,
  tag = 'page-size',
): Promise<HostPageSize | null> {
  try {
    const response: any = await PluginFileAPI.getPageSize(filePath, pageNum);
    const size = response?.success ? pageSizeFromResult(response.result) : null;
    if (!size) {
      console.warn(`[HostPageCoordinates/${tag}] getPageSize failed`, response?.error ?? response);
    }
    return size;
  } catch (error) {
    console.warn(`[HostPageCoordinates/${tag}] getPageSize threw`, error);
    return null;
  }
}


export async function getCurrentHostPageSize(tag = 'current-page-size'): Promise<HostPageSize | null> {
  try {
    const [fileResponse, pageResponse]: any[] = await Promise.all([
      PluginCommAPI.getCurrentFilePath(),
      PluginCommAPI.getCurrentPageNum(),
    ]);
    if (!fileResponse?.success || typeof fileResponse.result !== 'string' ||
        !pageResponse?.success || !Number.isInteger(pageResponse.result)) {
      console.warn(`[HostPageCoordinates/${tag}] current page unavailable`);
      return null;
    }
    return await getHostPageSize(fileResponse.result, pageResponse.result, tag);
  } catch (error) {
    console.warn(`[HostPageCoordinates/${tag}] current page lookup threw`, error);
    return null;
  }
}


export function clampHostPageRect(
  rect: HostPageRect,
  size: HostPageSize,
): HostPageRect | null {
  const clamped = {
    left: Math.max(0, Math.floor(rect.left)),
    top: Math.max(0, Math.floor(rect.top)),
    right: Math.min(size.width, Math.ceil(rect.right)),
    bottom: Math.min(size.height, Math.ceil(rect.bottom)),
  };
  return clamped.right - clamped.left >= 2 && clamped.bottom - clamped.top >= 2
    ? clamped
    : null;
}

