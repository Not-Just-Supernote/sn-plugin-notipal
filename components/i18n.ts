


import { NativeModules, Platform } from 'react-native';

export type Locale = 'zh' | 'en';

const STRINGS = {

  perm_required:          { zh: '需要权限',     en: 'Permission Required' },
  initial_permissions_required: {
    zh: 'Notipal 需要文件读写、删除和网络权限。请为每项权限选择“始终允许”，完成后再次点击 Notipal。',
    en: 'Notipal requires permanent file read/write/delete and network access. Select Always allow for every permission, then tap Notipal again.',
  },
  perm_always_required: {
    zh: '部分权限未设置为“始终允许”。Notipal 需要始终权限才能在后台正常运行，请在接下来的弹窗中选择“始终允许”。',
    en: 'Some permissions are not set to Always allow. Notipal needs permanent access to work properly. Please select Always allow in the following dialogs.',
  },
  perm_desc_file_read: {
    zh: 'Notipal 需要读取笔记与文档以提供快捷工具',
    en: 'Notipal needs read access to notes and documents for the toolbar',
  },
  perm_desc_file_write: {
    zh: 'Notipal 需要写入存储以保存剪藏与配置',
    en: 'Notipal needs storage write access to save clips and settings',
  },
  perm_desc_file_delete: {
    zh: 'Notipal 需要删除存储中的临时导出文件',
    en: 'Notipal needs storage delete access for temporary export files',
  },
  perm_desc_internet: {
    zh: 'Notipal 需要访问局域网以提供 LocalSend 发送与接收',
    en: 'Notipal needs LAN access for LocalSend send and receive',
  },

  tool_lasso_send:        { zh: '发送选区',     en: 'Send Lasso' },
  tool_lasso_ai:          { zh: '选区提问',      en: 'Ask from selection' },
  tool_ai_conversations:  { zh: '会话', en: 'Conversations' },
  ai_mode_text:           { zh: '文字提问', en: 'Text query' },
  ai_mode_formula:        { zh: '公式处理', en: 'Formula' },
  ai_mode_choose:         { zh: '选择处理方式', en: 'Selection mode' },
  ai_selection_hint:      { zh: '用笔圈选内容后确认', en: 'Circle content with the pen, then confirm' },
  ai_selection_retry:     { zh: '请在当前页重新圈选内容', en: 'Select content again on the current page' },

  bubble_recv_nospacing:  { zh: '无间距接收中', en: 'Receiving (No Gap)' },
  bubble_recv_paragraph:  { zh: '段落接收中',   en: 'Receiving (Paragraph)' },
  bubble_ai_recognizing:  { zh: '识别中…',     en: 'Recognizing…' },
  bubble_ai_sending:      { zh: '发送中…',     en: 'Sending…' },
  bubble_ai_waiting:      { zh: '等待 AI 回复', en: 'Waiting for AI' },
  bubble_ai_no_lasso:     { zh: '请先套索选中文字', en: 'Select text with lasso first' },
  lasso_send_ocr_failed:  { zh: '无法识别所选笔迹内容', en: 'Unable to recognize the selected handwriting' },
  bubble_ai_ready:        { zh: '就绪 · 文字提问', en: 'Ready · Text query' },
  bubble_formula_ready:   { zh: '就绪 · 公式处理', en: 'Ready · Formula' },
  bubble_ai_not_paired:   { zh: '未连接手机，请先在手机开启 RikkaHub', en: 'Phone not connected · start RikkaHub' },
  bubble_ai_queued:       { zh: '手机离线，已排队待发送', en: 'Phone offline · queued' },
  bubble_ai_send_failed:  { zh: '发送失败，请重试',      en: 'Send failed · try again' },
  bubble_relay_on:        { zh: 'AIRelay 已开启',         en: 'AIRelay is on' },
  bubble_relay_off:       { zh: '点会话按钮开启 AIRelay', en: 'Tap Conversations to start AIRelay' },
  bubble_relay_disabled:  { zh: '请先点会话按钮开启 AIRelay', en: 'Tap Conversations to start AIRelay first' },
  bubble_ai_reply_ready:  { zh: '回复已就绪，点下方卡片查看', en: 'Reply ready · tap a card below' },
  bubble_formula_rendering: { zh: '渲染公式中…', en: 'Rendering formula…' },
  bubble_formula_failed:  { zh: '公式处理失败', en: 'Formula failed' },
  formula_no_relay:       { zh: 'AIRelay 未连接，请确认手机端已启动并可访问', en: 'AIRelay is not connected. Confirm the phone endpoint is running and reachable.' },
  bubble_insert_sent:     { zh: '正在插入笔记…', en: 'Inserting into note…' },
  bubble_insert_done:     { zh: '已插入',         en: 'Inserted' },
  bubble_ai_timeout:      { zh: 'AI 无响应，请检查中转站', en: 'AI timed out · check the relay' },

  bubble_mosaic_ready:    { zh: '等待文本…点此放卡片', en: 'Waiting for text… tap to place card' },
  bubble_mosaic_pending:  { zh: '已收到文本，点此创建卡片', en: 'Text received · tap to create card' },
  note_switched_stop:     { zh: '笔记已切换，文本接收已停止', en: 'Note switched, text receiving stopped' },
  doc_text_receive_stop:  { zh: '已进入文档，文本接收已关闭', en: 'Opened a document, text receiving turned off' },
  doc_text_receive_unsupported: { zh: '文档中不支持文本接收', en: 'Text receiving is not available in documents' },
  bubble_doc_insert_unsupported: { zh: '文档中不能插入，点下方卡片查看回复', en: "Can't insert into documents · tap a card below" },
  bubble_doc_formula_unsupported: { zh: '文档中不支持公式识别', en: 'Formula mode is not available in documents' },
  pages_changed_stop:     { zh: '页面结构变更，文本接收已停止', en: 'Page structure changed, text receiving stopped' },

  tool_screenshot_ai:     { zh: '图像提问',  en: 'Ask from image' },
  tool_screenshot_send:   { zh: '截图发送',   en: 'Screenshot Send' },

  tool_toggle_spacing:    { zh: '切换间距',   en: 'Toggle Gap' },
  tool_ink_palette:       { zh: '笔迹调色板',     en: 'Ink Palette' },
  tool_cancel_ai:         { zh: '取消 AI',    en: 'Cancel AI' },

  doc_link_main_only:     { zh: '请切换到主图层后再插入文档链接', en: 'If you want to insert a document link, you should switch to the main layer first' },

  clip_err_title:         { zh: '标题暂不支持剪贴板保存', en: 'Titles cannot be saved to clipboard' },
  clip_err_link:          { zh: '链接暂不支持剪贴板保存', en: 'Links cannot be saved to clipboard' },
  clip_err_textbox:       { zh: '文本框暂不支持剪贴板保存', en: 'Text boxes cannot be saved to clipboard' },
  clip_err_image:         { zh: '图片暂不支持剪贴板保存', en: 'Images cannot be saved to clipboard' },
  clip_err_unsupported:   { zh: '{types}暂不支持剪贴板保存', en: '{types} cannot be saved to clipboard' },
  clip_saved_with_skipped:{ zh: '已跳过{skipped}，其余内容已添加到剪贴板', en: 'Skipped {skipped}; the remaining content was added to the clipboard' },
  clip_skip_title:        { zh: '标题', en: 'titles' },
  clip_skip_image:        { zh: '图片', en: 'images' },
  clip_skip_link:         { zh: '链接', en: 'links' },
  clip_skip_textbox:      { zh: '文本框', en: 'text boxes' },
  clip_skip_other:        { zh: '其他不可保存元素', en: 'other unsupported elements' },
  clip_skip_separator:    { zh: '、', en: ', ' },
  clip_overwrite:         { zh: '剪贴板已有内容，是否覆盖？', en: 'Clipboard slot is not empty. Overwrite?' },
  clip_empty_hint:        { zh: '剪贴板为空，请先用套索选中内容保存', en: 'Clipboard is empty. Select content with lasso first.' },
  palette_ink_only:       { zh: 'Palette 只对手写笔迹生效', en: 'Palette only works on handwriting' },
  palette_need_lasso:     { zh: '请先圈选要换笔的笔迹', en: 'Lasso the strokes to restyle first' },
  palette_apply_failed:   { zh: '调色盘处理失败，请重新圈选后重试', en: 'Palette update failed. Select the strokes again and retry.' },
  clip_err_read_failed:   { zh: '读取套索内容失败，请稍候重试', en: 'Failed to read lasso content. Please try again.' },
  clip_save_failed:       { zh: '剪贴板保存失败，请重试', en: 'Clipboard save failed, please retry' },
  snapshot_empty_page:    { zh: '当前页没有可保存的内容', en: 'Nothing on this page to snapshot' },
  snapshot_create_failed: { zh: '创建还原点失败，请重试', en: 'Failed to create restore point, please retry' },
  snapshot_missing:       { zh: '还原点文件已丢失', en: 'Restore point file is missing' },
  snapshot_restore_failed:{ zh: '还原失败，请重试', en: 'Restore failed, please retry' },
  btn_cancel:             { zh: '取消', en: 'Cancel' },
  btn_confirm:            { zh: '确定', en: 'OK' },

  no_wifi:                { zh: '未连接 WiFi', en: 'WiFi is not connected' },
  localsend_started:      { zh: 'LocalSend 已开启', en: 'LocalSend started' },
  localsend_stopped:      { zh: 'LocalSend 已关闭', en: 'LocalSend stopped' },

};

type StringKey = keyof typeof STRINGS;

function detectLocale(): Locale {
  try {
    let lang: string | undefined;
    if (Platform.OS === 'ios') {
      lang = NativeModules.SettingsManager?.settings?.AppleLocale
           || NativeModules.SettingsManager?.settings?.AppleLanguages?.[0];
    } else {
      lang = NativeModules.I18nManager?.localeIdentifier;
    }
    if (lang && lang.toLowerCase().startsWith('zh')) return 'zh';
  } catch {}
  return 'en';
}

let _locale: Locale = detectLocale();

function syncNativeLocale(loc: Locale): void {
  try {
    NativeModules.FloatingToolbar?.setLocale?.(loc);
  } catch {}
}

export function getLocale(): Locale {
  return _locale;
}

export function setLocale(loc: Locale): void {
  if (loc !== _locale) {
    _locale = loc;
  }
  syncNativeLocale(_locale);
}

export function t(key: StringKey, params?: Record<string, string | number>): string {
  const entry = STRINGS[key];
  let s = entry ? entry[_locale] || entry.en : String(key);
  if (params) {
    for (const k of Object.keys(params)) {
      s = s.replace(new RegExp(`\\{${k}\\}`, 'g'), String(params[k]));
    }
  }
  return s;
}
