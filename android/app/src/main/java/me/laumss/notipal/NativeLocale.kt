package me.laumss.notipal

import java.util.Locale

object NativeLocale {

    @Volatile
    private var localeOverride: String? = null

    private val isZh: Boolean
        get() {
            val override = localeOverride
            if (override != null) return override.startsWith("zh")
            return Locale.getDefault().language.startsWith("zh")
        }

    fun setLocale(loc: String) {
        localeOverride = loc.lowercase()
    }

    private val STRINGS = mapOf(

        "airrelay_pair_request" to ("发现 %s（%s）的 AI 服务，是否连接？" to "Found AI server %s (%s). Connect?"),
        "airrelay_auth_unsupported" to ("%s 开启了身份验证，AIRelay 无法连接。请在该设备的 Web 服务器设置中关闭「启用身份验证」。" to "%s has authentication enabled; AIRelay can't connect. Turn off \"Enable authentication\" in its web server settings."),

        "image_panel_title"  to ("插入图片" to "Insert Image"),
        "cropper_title"      to ("裁剪图片" to "Crop Image"),
        "crop_and_insert"    to ("裁剪并插入" to "Crop & Insert"),
        "cancel"             to ("取消" to "Cancel"),
        "no_images"          to ("此目录没有图片文件" to "No image files in this directory"),
        "no_received"        to ("还没有接收到图片文件\n从其他设备通过 LocalSend 发送图片即可在此查看"
                                 to "No images received yet\nSend images from another device via LocalSend"),
        "file_read_permission_needed" to ("需要授予 Notipal 文件读取权限，请在弹窗中允许后重试"
                                 to "Notipal needs file read permission. Allow it in the dialog, then retry."),
        "file_write_permission_needed" to ("需要授予 Notipal 文件写入权限，请在弹窗中允许后重试"
                                  to "Notipal needs file write permission. Allow it in the dialog, then retry."),
        "mosaic_permission_needed" to ("Mosaic 需要文件读写权限才能接收截图，请在设置中允许后重试"
                                   to "Mosaic needs file read/write permission to receive captures. Allow it in Settings, then retry."),
        "mosaic_plugin_needed" to ("请先安装 Mosaic 插件，再使用此功能"
                                   to "Install the Mosaic plugin before using this feature."),
        "mosaic_permission_request" to ("Mosaic 需要访问共享截图目录，请允许文件读写权限"
                                   to "Mosaic needs access to the shared capture folder. Allow file read/write permission."),
        "open_settings" to ("打开设置" to "Open Settings"),

        "doc_panel_title"    to ("插入文档链接" to "Insert Doc Link"),
        "doc_insert_link"    to ("链接文档" to "Insert Link"),
        "doc_no_files"       to ("此目录没有文档文件" to "No document files in this directory"),
        "picker_back"        to ("返回" to "Back"),
        "picker_select"      to ("选择" to "Select"),
        "picker_file_default" to ("此目录没有可选文件" to "No selectable files in this directory"),
        "picker_empty_folder" to ("此目录为空" to "This directory is empty"),

        "send_title"         to ("发送到设备" to "Send to Device"),
        "peers_scanning"     to ("正在扫描局域网设备..." to "Scanning LAN peers..."),
        "peers_none"         to ("未发现设备。请确保对方已打开 LocalSend。"
                                 to "No peers found. Make sure LocalSend is open on the other device."),
        "send_content"       to ("发送内容" to "Content"),
        "send_devices"       to ("发送到" to "Send to"),
        "send_btn"           to ("发送" to "Send"),
        "send_btn_count"     to ("发送 %d 项" to "Send %d items"),
        "send_item_text"     to ("文本 · %d 字" to "Text · %d chars"),
        "send_item_image"    to ("图片" to "Image"),
        "send_item_image_n"  to ("图片 %d" to "Image %d"),
        "send_item_link"     to ("网址" to "Link"),
        "send_nothing"       to ("没有可发送的内容" to "Nothing to send"),
        "send_default_hint"  to ("长按设备设为默认，以后单张图片或文本会直接发给它"
                                 to "Long-press a device to make it default; single images or text then go straight to it"),
        "send_rejected"      to ("对方拒绝了接收" to "The receiver declined"),
        "send_err_connect"   to ("连不上该设备，请确认对方已打开 LocalSend 且在同一网络"
                                 to "Can't reach this device. Make sure LocalSend is open on the same network."),
        "send_err_missing"   to ("要发送的文件已不存在" to "The file to send no longer exists"),
        "sending"            to ("发送中..." to "Sending..."),
        "sending_progress"   to ("发送中 %d/%d..." to "Sending %d/%d..."),
        "peers_scanning_short" to ("扫描中..." to "Scanning..."),
        "sync_clipboard_desc" to ("把剪贴板里的内容同步到对方的 Notipal" to "Sync clipboard items to Notipal on the other device"),
        "send_success"       to ("发送成功" to "Sent successfully"),
        "send_failed"        to ("发送失败" to "Send failed"),
        "sync_clipboard_btn" to ("同步剪贴板" to "Sync Clipboard"),
        "sync_packaging"     to ("正在打包剪贴板..." to "Packaging clipboard..."),
        "sync_clipboard_empty" to ("剪贴板为空" to "Clipboard is empty"),
        "sync_clipboard_ask" to ("是否接受来自 %s 的剪贴板？" to "Accept clipboard from %s?"),
        "sync_clipboard_ok"  to ("剪贴板已同步" to "Clipboard synced"),
        "sync_waiting"       to ("等待对方确认…" to "Waiting for confirmation…"),
        "sync_rejected"      to ("对方拒绝了同步请求" to "Sync request rejected"),
        "image_send_rejected" to ("对方拒绝了图片接收" to "The receiver rejected the image"),
        "image_receive_ask" to ("检测到 %s 传来一张图片" to "An image is arriving from %s"),
        "image_receive_more" to ("其他选项" to "More options"),
        "image_receive_insert_now" to ("直接插入" to "Insert now"),
        "image_receive_keep_ask" to ("接收图片后保留在收件箱，便于后续手动插入" to "Keep the image in Inbox for manual insertion"),
        "image_receive_reject" to ("拒绝接收" to "Reject"),
        "extracting"         to ("正在提取套索内容..." to "Extracting lasso content..."),
        "rescan"             to ("重新扫描" to "Rescan"),

        "screenshot_panel_title" to ("文档截图" to "Doc Screenshots"),
        "no_queue"               to ("没有待插入的截图\n在文档中使用截图裁切功能添加" to "No queued screenshots\nUse screenshot crop in DOC to add"),
        "no_history"             to ("没有历史截图" to "No history screenshots"),
        "insert"                 to ("插入" to "Insert"),
        "delete"                 to ("删除" to "Delete"),

        "confirm"                to ("确认" to "Confirm"),
        "rotation_sync_message"  to ("因为技术限制，每次旋转屏幕都必须手动重启启用 Notipal 插件，请暂时避免在使用 Notipal 时旋转屏幕。"
                                     to "Due to technical limitations, you must manually restart the Notipal plugin after every screen rotation. Please avoid rotating the screen while using Notipal for now."),

        "multi_select"           to ("选择多项" to "Select Multiple"),

        "long_screenshot"        to ("长截图" to "Stitch"),
        "long_screenshot_active" to ("继续长截图" to "Continue Stitch"),
        "add_to_history"         to ("添加到队列" to "Add to Queue"),
        "added_to_doc_screenshots" to ("已添加到文档截图" to "Added to Doc Screenshots"),
        "send_to_other_devices"  to ("发送到其他设备" to "Send to Other Devices"),
        "insert_next"            to ("下次插入" to "Insert Next"),
        "multi"                  to ("多" to "Multi"),

        "image_insert_failed" to ("图片插入失败，原图已保留，请重试" to "Image insertion failed. The original was kept. Please retry."),
        "screenshot_delete_failed" to ("部分截图删除失败，请重试" to "Some screenshots could not be deleted. Please retry."),
        "screencap_failed"       to ("截图失败\n\n请手动按 电源+音量下 截图，\n然后重新打开插件。"
                                     to "Could not capture screenshot.\n\nPlease press Power + Volume Down\nto take a screenshot manually,\nthen reopen the plugin."),
        "screenshot_save_failed" to ("截图保存失败，请检查文件权限后重试"
                                     to "Could not save the screenshot. Check file permissions and retry."),
        "mosaic_insert_failed" to ("无法插入 Mosaic 卡片，请检查文件权限后重试"
                                    to "Could not insert the Mosaic card. Check file permissions and retry."),
        "mosaic_note_importing" to ("正在读取整本笔记，完成后在 Mosaic 中打开"
                                    to "Reading the whole note. It opens in Mosaic when done."),
        "mosaic_note_import_failed" to ("无法导入笔记到 Mosaic，请稍后重试"
                                    to "Could not import the note into Mosaic. Try again later."),
        "screenshot_panel_failed" to ("无法打开截图面板，请检查悬浮窗权限后重试"
                                      to "Could not open the screenshot panel. Check overlay permission and retry."),


        "no_wifi"                to ("未连接 WiFi" to "WiFi is not connected"),
        "peer_default_badge"     to ("默认" to "Default"),
        "tab_received"           to ("已接收" to "Received"),
        "tab_browse"             to ("浏览文件" to "Browse"),
        "tab_queue"              to ("待插入" to "Queue"),
        "tab_history"            to ("历史截图" to "History"),
        "config_tab_tools"       to ("工具栏" to "Toolbar"),
        "config_tab_connections" to ("连接" to "Connections"),
        "config_tab_about"       to ("关于与反馈" to "About"),
        "config_sec_localsend"   to ("LocalSend 发送" to "LocalSend"),
        "config_sec_airrelay"    to ("AI 中继" to "AI Relay"),
        "about_version"          to ("版本 %s" to "Version %s"),
        "about_tagline"          to ("笔记与文档的多功能工具集" to "A versatile toolkit for notes and documents"),
        "about_discord_title"    to ("加入 Discord 社区" to "Join our Discord"),
        "about_discord_desc"     to ("用手机扫码加入，交流用法、反馈问题，第一时间获取更新。"
                                     to "Scan with your phone to join: share tips, report issues and get updates first."),
        "about_feedback_title"   to ("邮件反馈问题" to "Send feedback by email"),
        "about_feedback_desc"    to ("用手机扫码写邮件，会自动附上版本和设备信息。"
                                     to "Scan with your phone to write an email; version and device info are filled in."),
        "about_feedback_subject" to ("反馈" to "feedback"),
        "about_feedback_body"    to ("问题描述：" to "What happened:"),
        "config_default_peer"    to ("默认发送设备" to "Default send device"),
        "config_default_peer_none" to ("未绑定（在发送面板长按设备可绑定）" to "Not bound (long-press a device in the send panel)"),
        "config_default_peer_unbind" to ("解绑" to "Unbind"),
        "config_airrelay_phone"  to ("AIRelay 手机" to "AIRelay phone"),
        "config_airrelay_none"   to ("未绑定（发现 RikkaHub 时会询问）" to "Not paired (you will be asked when RikkaHub is found)"),
        "config_airrelay_unbind" to ("解除绑定" to "Unpair"),
        "config_airrelay_auto_open" to ("回答时自动打开详情" to "Auto-open detail on reply"),
        "btn_cancel"             to ("取消" to "Cancel"),
        "btn_confirm"            to ("确定" to "OK"),
        "text_recv_closed"       to ("检测到文本发送，请先打开文本接收悬浮窗。" to "Text send detected. Please open the text receive bubble first."),
        "text_recv_doc_unsupported" to ("检测到文本发送，文档中不支持文本接收。" to "Text send detected. Text receiving is not available in documents."),
        "text_recv_panel_unsupported" to ("检测到文本发送，插入图片 / 文档截图时不支持文本接收。" to "Text send detected. Text receiving is not available while inserting images or doc screenshots."),

        "config_shared_tools"    to ("共有工具" to "Shared Tools"),
        "config_shared_hint"     to ("以下按钮会同时出现在笔记和文档工具栏中，长按可拖动排序。" to "These tools appear in both toolbars; long-press to reorder."),
        "config_note_tools"      to ("笔记专属工具" to "Note-only Tools"),
        "config_note_hint"       to ("这些按钮只出现在笔记工具栏中。点击图标变浅表示不显示，再点恢复；长按可拖动排序。" to "These tools appear only in the note toolbar. Tap to fade a tool so it stays hidden; tap again to show it. Long-press to reorder."),
        "config_doc_tools"       to ("文档专属工具" to "Document-only Tools"),
        "config_doc_hint"        to ("这些按钮只出现在文档工具栏中，长按可拖动排序。" to "These tools appear only in the document toolbar; long-press to reorder."),
        "config_sort_hint"       to ("点击移除，长按拖动排序。" to "Tap to remove; long-press and drag to reorder."),
        "config_min_tools"       to ("至少保留 5 个工具" to "Keep at least 5 tools"),
        "config_removed_tools"   to ("已从工具栏移除" to "Removed from Toolbar"),
        "config_removed_hint"    to ("点击添加。" to "Tap to add back."),
        "config_restore_defaults" to ("恢复默认" to "Restore Defaults"),
        "config_empty"           to ("工具列表为空" to "No tools added"),
        "config_empty_hint"      to ("请从下方添加工具" to "Choose a tool below"),
        "config_tool_image"      to ("插入图片" to "Insert Image"),
        "config_tool_doc"        to ("文档截图" to "Doc Screenshot"),
        "config_tool_text"       to ("文本接收" to "Text Receive"),
        "config_tool_smart_lasso" to ("智能套索" to "Smart Lasso"),
        "ai_mode_choose" to ("选择处理方式" to "Selection mode"),
        "ai_mode_back" to ("返回" to "Back"),
        "capture_current_page_retry" to ("请在当前页重新圈选后发送" to "Select the current page region again to send"),
        "smart_lasso_hint" to ("等待加载后" to "Wait for loading to finish"),
        "config_tool_link"       to ("链接文档" to "Insert Link"),
        "config_tool_goto_note"  to ("前往笔记" to "Go to Note"),
        "config_tool_ai_relay"   to ("AI中继" to "AI Relay"),
        "config_tool_ink_palette" to ("笔迹调色板" to "Ink Palette"),
        "config_tool_clipboard"  to ("剪贴板" to "Clipboard"),
        "config_tool_collapse"   to ("收纳工具栏" to "Collapse Toolbar"),

        "palette_presets"        to ("笔槽" to "Presets"),
        "palette_color"          to ("颜色" to "Color"),
        "palette_thickness"      to ("粗细" to "Thickness"),
        "palette_pen_type"       to ("笔型" to "Pen Type"),
        "palette_apply"          to ("应用" to "Apply"),
        "palette_add_col"        to ("＋ 增加一列" to "＋ Add Column"),
        "palette_remove_col"     to ("－ 减少一列" to "－ Remove Column"),
        "palette_delete_confirm" to ("确定重置 %d 个未锁定笔槽？已锁定的笔槽将保留。" to "Reset %d unlocked pen slots? Locked slots will be kept."),
        "palette_lock_slot"      to ("锁定" to "Lock"),
        "palette_unlock_slot"    to ("解锁" to "Unlock"),
        "palette_slot_locked"    to ("笔槽已锁定" to "Pen slot locked"),
        "palette_select_slot_to_lock" to ("先选择要锁定的笔槽" to "Select a pen slot to lock"),
        "palette_open_settings"  to ("设置" to "Settings"),
        "palette_reset"          to ("重置" to "Reset"),
        "palette_tier_thin"      to ("细" to "Thin"),
        "palette_tier_medium"    to ("中" to "Medium"),
        "palette_tier_thick"     to ("粗" to "Thick"),
        "palette_marker_black"   to ("马克·黑" to "Mk·Black"),
        "palette_marker_gray"    to ("马克·灰" to "Mk·Gray"),
        "palette_marker_light_gray" to ("浅灰马克笔" to "Light gray marker"),
        "palette_marker_mid_gray" to ("灰色马克笔" to "Gray marker"),
        "palette_marker_dark_gray" to ("深灰马克笔" to "Dark gray marker"),
        "palette_mosaic_retry" to ("请重新选择 Mosaic 字迹后应用" to "Select Mosaic strokes again and apply"),
        "palette_marker_white"   to ("马克·白" to "Mk·White"),
        "palette_marker_note2"   to ("荧光笔颜色随笔型固定" to "Marker color is fixed by pen type"),
        "palette_header_title"   to ("笔槽设置" to "Pen Slot Settings"),
        "palette_bubble_hint"    to ("先圈选笔迹，再点下方笔槽一键换笔" to "Lasso strokes, then tap a slot to restyle them"),
        "palette_bubble_empty"   to ("还没有笔槽：点工具栏的「笔迹调色板」添加" to "No slots yet: tap Ink Palette on the toolbar to add"),
        "palette_header_new"     to ("新建笔" to "New Pen"),

        "palette_snapshot_header"          to ("还原点" to "Restore Points"),
        "palette_snapshot_create"          to ("创建" to "Create"),
        "palette_snapshot_empty"           to ("暂无还原点，点「创建」保存当前页快照" to "No restore points. Tap Create to snapshot this page"),
        "palette_snapshot_restore_confirm" to ("将该还原点粘贴到当前页？" to "Paste this restore point onto the current page?"),
        "palette_snapshot_delete_confirm"  to ("删除该还原点？" to "Delete this restore point?"),

        "pen_needle"             to ("针管笔" to "Needle"),
        "pen_ball"               to ("墨水笔" to "Ink Pen"),
        "pen_calligraphy"        to ("书法笔" to "Brush"),
        "pen_marker"             to ("马克笔" to "Marker"),
        "color_black"            to ("黑色" to "Black"),
        "color_dark_gray"        to ("深灰" to "Dark Gray"),
        "color_light_gray"       to ("浅灰" to "Light Gray"),
        "color_ghost"            to ("白色" to "White"),
        "color_blue"             to ("蓝" to "Blue"),
        "color_red"              to ("红" to "Red"),
        "color_pink"             to ("粉" to "Pink"),
        "color_orange"           to ("橙" to "Orange"),
        "color_green"            to ("绿" to "Green"),
        "color_cyan"             to ("青" to "Cyan"),
        "color_lime"             to ("柠" to "Lime"),
        "color_purple"           to ("紫" to "Purple"),
        "horizontal"             to ("横向" to "Horizontal"),
        "vertical"               to ("纵向" to "Vertical"),
        "filter"                 to ("滤镜" to "Filter"),
        "filter_original"        to ("原图" to "Original"),
        "filter_enhance"         to ("文档增强" to "Enhance"),
        "filter_text_bw"         to ("黑白文本" to "B&W Text"),
        "density"                to ("浓度" to "Density"),

        "perm_required"          to ("需要权限" to "Permission Required"),
        "perm_desc_internet"     to ("Notipal 需要访问局域网以提供 LocalSend 发送与接收"
                                     to "Notipal needs LAN access for LocalSend send and receive"),
        "perm_desc_file_write"   to ("Notipal 需要写入存储以保存剪藏与配置"
                                     to "Notipal needs storage write access to save clips and settings"),
        "perm_desc_file_delete"  to ("Notipal 需要删除存储中的临时导出文件"
                                     to "Notipal needs storage delete access for temporary export files"),
        "perm_desc_file_read"    to ("Notipal 需要读取笔记与文档以提供快捷工具"
                                     to "Notipal needs read access to notes and documents for the toolbar"),

        "relay_edit"             to ("编辑" to "Edit"),
        "relay_readd"            to ("重新加入" to "Re-add"),
        "relay_delete"           to ("删除" to "Delete"),
        "relay_clear"            to ("清除" to "Clear"),
        "relay_insert"           to ("插入" to "Insert"),
        "relay_selected_items"   to ("%d 项" to "%d items"),
        "relay_selected_rows"    to ("%d 行" to "%d rows"),
        "relay_empty"            to ("暂无 AIRelay 消息" to "No AIRelay messages"),
        "relay_sent_to_note"     to ("已写入笔记" to "Sent to note"),
        "relay_source_ai"        to ("AI" to "AI"),
        "relay_source_manual"    to ("手动" to "Manual"),
        "relay_source_dictation" to ("听写" to "Dictation"),
        "relay_msg_unavailable"  to ("消息不可用" to "Message unavailable"),
        "relay_no_message"       to ("没有消息" to "No message"),
        "relay_select"           to ("选择" to "Select"),
        "relay_replace_handwriting" to ("替换手写" to "Replace handwriting"),
        "relay_image_alt"        to ("[图片: %s]" to "[Image: %s]"),

        "stitch_mode_strip"      to ("长图" to "Long"),
        "stitch_mode_grid"       to ("拼图" to "Grid"),
        "stitch_add_page"        to ("继续截图" to "Add Page"),
        "stitch_auto_align"      to ("自动对齐" to "Auto Align"),
        "stitch_reverse"         to ("倒序" to "Reverse"),
        "stitch_remove_last"     to ("删除末张" to "Remove Last"),
        "stitch_next"            to ("下一步" to "Next"),
        "stitch_seam"            to ("接缝 %d" to "Seam %d"),
        "stitch_overlap"         to ("重叠 %d px" to "Overlap %d px"),
        "stitch_aligning"        to ("正在对齐…" to "Aligning…"),
        "stitch_cols"            to ("列数" to "Columns"),
        "stitch_order_row"       to ("按行" to "By Row"),
        "stitch_order_col"       to ("按列" to "By Column"),
    )

    fun t(key: String): String {
        val pair = STRINGS[key] ?: return key
        return if (isZh) pair.first else pair.second
    }

    fun t(key: String, vararg args: Any): String {
        val pair = STRINGS[key] ?: return key
        val template = if (isZh) pair.first else pair.second
        return String.format(template, *args)
    }

    fun itemCount(count: Int): String {
        return if (isZh) "共${count}项" else "$count items"
    }
}
