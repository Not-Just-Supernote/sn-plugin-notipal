
export const TOOL_IDS = {
  SMART_LASSO: 'smart_lasso',
  AI_RELAY: 'ai_relay',
  INK_PALETTE: 'ink_palette',
} as const;

export type CanonicalToolId = typeof TOOL_IDS[keyof typeof TOOL_IDS];

const TOOL_ID_ALIASES: Record<string, string> = {
  send_ai: TOOL_IDS.SMART_LASSO,
  voice_transcribe: TOOL_IDS.AI_RELAY,
  invert_ink: TOOL_IDS.INK_PALETTE,
};

const TOOL_ACTION_ALIASES: Record<string, string> = {
  send_ai: TOOL_IDS.SMART_LASSO,
  lasso_smart_send: TOOL_IDS.SMART_LASSO,
  voice_transcribe: TOOL_IDS.AI_RELAY,
  invert_ink: TOOL_IDS.INK_PALETTE,
};


export function normalizeToolId(id: string | null | undefined): string {
  if (!id) return '';
  return TOOL_ID_ALIASES[id] ?? id;
}


export function normalizeToolAction(action: string | null | undefined): string {
  if (!action) return '';
  return TOOL_ACTION_ALIASES[action] ?? action;
}

export function isAiRelayAction(action: string | null | undefined): boolean {
  return normalizeToolAction(action) === TOOL_IDS.AI_RELAY;
}

export function isInkPaletteAction(action: string | null | undefined): boolean {
  return normalizeToolAction(action) === TOOL_IDS.INK_PALETTE;
}

