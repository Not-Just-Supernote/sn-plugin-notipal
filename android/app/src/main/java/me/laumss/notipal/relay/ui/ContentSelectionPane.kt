package me.laumss.notipal.relay.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.laumss.notipal.relay.core.MarkdownBlock
import me.laumss.notipal.relay.core.MarkdownDocument
import me.laumss.notipal.relay.core.MarkdownRangeSelection
import me.laumss.notipal.relay.core.MarkdownSelectionState
import me.laumss.notipal.relay.core.SelectionRowInfo
import me.laumss.notipal.relay.core.SelectionRows
import kotlin.math.roundToInt


private val SelectedBg = Color(0xFFCCCCCC)
private val AnchorBg = Color(0xFFDDDDDD)

private const val BASE_FONT = 18
private const val INDICATOR_DP = 32


private val EinkLineBreak = LineBreak(
    strategy = LineBreak.Strategy.HighQuality,
    strictness = LineBreak.Strictness.Strict,
    wordBreak = LineBreak.WordBreak.Default,
)

private fun Modifier.selectionClick(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}


private fun rowIndicator(
    selection: MarkdownSelectionState,
    index: Int,
): String? {
    if (selection.anchorBlockIndex == index) return "▲"
    for (range in selection.ranges) {
        if (index == range.firstBlockIndex) return "▲"
        if (index == range.lastBlockIndex) return "▼"
    }
    return null
}


private data class PaneRow(
    val info: SelectionRowInfo,
    
    val display: String?,
    val fontSize: Int = BASE_FONT,
    val mono: Boolean = false,
    val quote: Boolean = false,
    val indentDp: Int = 0,
    
    val markerPrefix: String? = null,
)


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContentSelectionPane(
    document: MarkdownDocument,
    selection: MarkdownSelectionState,
    onToggleRow: (Int) -> Unit,
    onRowsChanged: (List<SelectionRowInfo>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = MdPalette(ink = Ink, muted = Muted, codeBg = Faint, strong = Ink)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize()) {
        
        val contentWidthDp = maxWidth - 24.dp - INDICATOR_DP.dp

        val rows = remember(document, contentWidthDp) {
            buildRows(document) { text, fontSize, mono, indentDp ->
                val widthPx = with(density) { (contentWidthDp - indentDp.dp).toPx() }
                    .roundToInt().coerceAtLeast(64)
                val style = TextStyle(
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = if (mono) FontFamily.Monospace else null,
                    lineBreak = EinkLineBreak,
                )
                val layout = measurer.measure(
                    AnnotatedString(text), style,
                    constraints = Constraints(maxWidth = widthPx),
                )
                (0 until layout.lineCount).map { i ->
                    layout.getLineStart(i) to layout.getLineEnd(i)
                }
            }
        }

        LaunchedEffect(rows) { onRowsChanged(rows.map { it.info }) }

        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, top = 18.dp, bottom = 18.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    val isSelected = MarkdownRangeSelection.isSelected(selection, index)
                    val isAnchor = selection.anchorBlockIndex == index
                    val indicator = rowIndicator(selection, index)

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    isSelected -> SelectedBg
                                    isAnchor -> AnchorBg
                                    else -> Color.Transparent
                                },
                            )
                            .selectionClick { onToggleRow(index) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (row.display == null) {
                                MarkdownBlockView(
                                    block = document.blocks[row.info.blockIndex],
                                    palette = palette,
                                    baseFontSize = BASE_FONT,
                                    boldBody = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Row(Modifier.padding(start = row.indentDp.dp)) {
                                    if (row.markerPrefix != null) {
                                        Text(
                                            row.markerPrefix,
                                            fontSize = row.fontSize.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = palette.ink,
                                            modifier = Modifier.width(26.dp),
                                        )
                                    } else if (document.blocks[row.info.blockIndex]
                                            is MarkdownBlock.ListItem) {
                                        
                                        Spacer(Modifier.width(26.dp))
                                    }
                                    Text(
                                        row.display,
                                        fontSize = row.fontSize.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = if (row.mono) FontFamily.Monospace else null,
                                        color = if (row.quote) palette.muted else palette.ink,
                                        lineHeight = (row.fontSize * 1.55).sp,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                        
                        Box(
                            Modifier.width(INDICATOR_DP.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (indicator != null) {
                                Text(
                                    indicator,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Ink,
                                )
                            }
                        }
                    }

                    if (index < rows.lastIndex) {
                        val nextSameBlock =
                            rows[index + 1].info.blockIndex == row.info.blockIndex
                        val continuesSelection = isSelected &&
                            MarkdownRangeSelection.isSelected(selection, index + 1)
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .height(if (nextSameBlock) 0.dp else 10.dp)
                                .background(if (continuesSelection) SelectedBg else Color.Transparent),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}


private fun buildRows(
    document: MarkdownDocument,
    measureLines: (text: String, fontSize: Int, mono: Boolean, indentDp: Int) -> List<Pair<Int, Int>>,
): List<PaneRow> {
    val out = mutableListOf<PaneRow>()
    document.blocks.forEachIndexed { bi, block ->
        val atomic = PaneRow(SelectionRowInfo(blockIndex = bi, atomic = true), display = null)
        when (block) {
            is MarkdownBlock.Paragraph, is MarkdownBlock.Quote,
            is MarkdownBlock.ListItem, is MarkdownBlock.CodeFence -> {
                val text = SelectionRows.selectableText(block) ?: run { out.add(atomic); return@forEachIndexed }
                if (text.isBlank()) { out.add(atomic); return@forEachIndexed }
                val mono = block is MarkdownBlock.CodeFence
                val fontSize = if (mono) BASE_FONT - 3 else BASE_FONT
                val quote = block is MarkdownBlock.Quote
                val indentDp = when (block) {
                    is MarkdownBlock.ListItem -> block.indent * 18 + 26
                    is MarkdownBlock.Quote -> 13
                    is MarkdownBlock.CodeFence -> 22
                    else -> 0
                }
                val lines = measureLines(text, fontSize, mono, indentDp)
                if (lines.size <= 1) {
                    
                    out.add(PaneRow(
                        SelectionRowInfo(bi, 0, text.length, atomic = false),
                        display = text,
                        fontSize = fontSize, mono = mono, quote = quote,
                        indentDp = if (block is MarkdownBlock.ListItem) block.indent * 18 else 0,
                        markerPrefix = (block as? MarkdownBlock.ListItem)?.marker,
                    ))
                } else {
                    lines.forEachIndexed { li, (start, end) ->
                        out.add(PaneRow(
                            SelectionRowInfo(bi, start, end, atomic = false),
                            display = text.substring(start, end).trimEnd('\n'),
                            fontSize = fontSize, mono = mono, quote = quote,
                            indentDp = if (block is MarkdownBlock.ListItem) block.indent * 18 else 0,
                            markerPrefix = if (li == 0) (block as? MarkdownBlock.ListItem)?.marker else null,
                        ))
                    }
                }
            }
            else -> out.add(atomic) 
        }
    }
    return out
}
