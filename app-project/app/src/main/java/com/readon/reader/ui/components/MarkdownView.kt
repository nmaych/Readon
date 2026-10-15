package com.readon.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readon.reader.data.Markdown

/**
 * Renders the update notes as Markdown rather than as their own source.
 *
 * The card used to show the first line of the notes cut at 90 characters, so the
 * user deciding whether to install an update was shown `### Added` and half a
 * sentence. The notes are the only explanation of what changed, and they are
 * written in Markdown because they are the same text GitHub publishes -- so they
 * are parsed ([Markdown]) and drawn here.
 *
 * This is a renderer for changelogs, not a CommonMark engine: it covers the
 * constructs a changelog uses and shows anything it does not understand as
 * literal text, which is the failure that loses nothing.
 */
@Composable
fun MarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    secondaryTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    baseSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    onLink: ((String) -> Unit)? = null,
    maxBlocks: Int? = null,
) {
    // Parsed once per text: the notes never change while the card is on screen,
    // and reparsing them on every recomposition would be a full pass over a
    // document that can be several thousand characters.
    val parsed = remember(markdown) { Markdown.parse(markdown) }
    // A changelog section for a release like 1.0.6 is several thousand
    // characters. The card shows a few blocks so the reader can see what kind of
    // change this is, and the whole thing lives behind a tap -- a card that grows
    // to fill the screen pushes the version, the buttons and the rest of the
    // settings page out of reach.
    val truncated = maxBlocks != null && parsed.size > maxBlocks
    val blocks = if (maxBlocks != null) parsed.take(maxBlocks) else parsed

    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(blockGap(blocks[index - 1], block)))
            BlockView(
                block = block,
                textColor = textColor,
                secondaryTextColor = secondaryTextColor,
                accentColor = accentColor,
                linkColor = linkColor,
                baseSize = baseSize,
                onLink = onLink,
            )
        }
        if (truncated) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "……",
                color = secondaryTextColor,
                fontSize = baseSize,
                lineHeight = baseSize * 1.4f,
            )
        }
    }
}

/**
 * The space above a block.
 *
 * Headings get room above them because that is what separates one section of the
 * notes from the last; consecutive list items get almost none, because a list
 * whose rows are spaced like paragraphs reads as a series of unrelated remarks
 * rather than as one list.
 */
private fun blockGap(previous: Markdown.Block, next: Markdown.Block): androidx.compose.ui.unit.Dp = when {
    next is Markdown.Block.Heading -> 14.dp
    previous is Markdown.Block.Heading -> 6.dp
    previous is Markdown.Block.Bullet || previous is Markdown.Block.Numbered -> 3.dp
    previous is Markdown.Block.Divider -> 10.dp
    next is Markdown.Block.Divider -> 10.dp
    else -> 8.dp
}

@Composable
private fun BlockView(
    block: Markdown.Block,
    textColor: Color,
    secondaryTextColor: Color,
    accentColor: Color,
    linkColor: Color,
    baseSize: androidx.compose.ui.unit.TextUnit,
    onLink: ((String) -> Unit)?,
) {
    when (block) {
        is Markdown.Block.Heading -> {
            // A heading's size falls off with its level, but only to a point: the
            // deepest heading in a changelog is `###`, and all three have to be
            // legible at 12sp body text.
            val size = when (block.level) {
                1 -> baseSize * 1.45f
                2 -> baseSize * 1.28f
                3 -> baseSize * 1.12f
                else -> baseSize
            }
            InlineText(
                spans = Markdown.inline(block.text),
                style = TextStyle(
                    color = textColor,
                    fontSize = size,
                    fontWeight = FontWeight.SemiBold,
                ),
                linkColor = linkColor,
                onLink = onLink,
            )
        }

        is Markdown.Block.Paragraph -> InlineText(
            spans = Markdown.inline(block.text),
            style = TextStyle(color = textColor, fontSize = baseSize, lineHeight = baseSize * 1.45f),
            linkColor = linkColor,
            onLink = onLink,
        )

        is Markdown.Block.Bullet -> ListRow(
            marker = "•",
            text = block.text,
            depth = block.depth,
            markerColor = accentColor,
            textColor = textColor,
            baseSize = baseSize,
            linkColor = linkColor,
            onLink = onLink,
        )

        is Markdown.Block.Numbered -> ListRow(
            marker = block.marker,
            text = block.text,
            depth = block.depth,
            markerColor = secondaryTextColor,
            textColor = textColor,
            baseSize = baseSize,
            linkColor = linkColor,
            onLink = onLink,
        )

        is Markdown.Block.Quote -> Row(
            // `IntrinsicSize.Min` so the bar is as tall as the quote, not a fixed
            // height: the changelog's quotes are one line today, but a bar that
            // stops half-way down a two-line quote would look like a rendering
            // bug rather than a decoration.
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor.copy(alpha = 0.5f))
            )
            Spacer(Modifier.width(8.dp))
            InlineText(
                spans = Markdown.inline(block.text),
                style = TextStyle(
                    color = secondaryTextColor,
                    fontSize = baseSize,
                    lineHeight = baseSize * 1.45f,
                ),
                linkColor = linkColor,
                onLink = onLink,
            )
        }

        is Markdown.Block.Code -> CodeBlock(accentColor) {
            Text(
                text = block.text,
                color = textColor,
                fontFamily = FontFamily.Monospace,
                fontSize = baseSize * 0.92f,
                lineHeight = baseSize * 1.4f,
            )
        }

        Markdown.Block.Divider -> HorizontalDivider(color = secondaryTextColor.copy(alpha = 0.25f))
    }
}

/** A fenced code block's own box. */
@Composable
private fun CodeBlock(accent: Color, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        content()
    }
}

@Composable
private fun ListRow(
    marker: String,
    text: String,
    depth: Int,
    markerColor: Color,
    textColor: Color,
    baseSize: androidx.compose.ui.unit.TextUnit,
    linkColor: Color,
    onLink: ((String) -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Indentation is the whole visual grammar of a nested list; without
            // it the changelog's sub-points read as siblings of the point they
            // are explaining.
            .padding(start = (depth * 14).dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = marker,
            color = markerColor,
            fontSize = baseSize,
            lineHeight = baseSize * 1.45f,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(if (marker.length > 1) 20.dp else 12.dp),
        )
        InlineText(
            spans = Markdown.inline(text),
            style = TextStyle(color = textColor, fontSize = baseSize, lineHeight = baseSize * 1.45f),
            linkColor = linkColor,
            onLink = onLink,
        )
    }
}

/**
 * A block's inline content, drawn as one text run.
 *
 * Links are annotations on the same run rather than separate composables, so a
 * link inside a sentence stays in the sentence instead of breaking the line.
 * `ClickableText` is only used when there is actually a link to click: it is a
 * heavier node, and most of the notes have none.
 */
@Composable
private fun InlineText(
    spans: List<Markdown.Span>,
    style: TextStyle,
    linkColor: Color,
    onLink: ((String) -> Unit)?,
) {
    val hasLink = onLink != null && containsLink(spans)
    val annotated = buildAnnotatedString {
        appendSpans(spans, linkColor)
    }

    if (hasLink) {
        ClickableText(
            text = annotated,
            style = style,
            onClick = { offset ->
                annotated.getStringAnnotations(LINK_TAG, offset, offset)
                    .firstOrNull()
                    ?.let { onLink?.invoke(it.item) }
            },
        )
    } else {
        Text(text = annotated, style = style)
    }
}

private fun containsLink(spans: List<Markdown.Span>): Boolean = spans.any {
    when (it) {
        is Markdown.Span.Link -> true
        is Markdown.Span.Styled -> containsLink(it.spans)
        else -> false
    }
}

/**
 * Append [spans] to this builder, decorating as it goes.
 *
 * Styling is applied with `pushStyle`, which nests: the changelog writes
 * `**`Content-Range` 的解析**`, so a code span genuinely sits inside a bold run
 * and both have to apply to the same characters.
 */
private fun AnnotatedString.Builder.appendSpans(spans: List<Markdown.Span>, linkColor: Color) {
    for (span in spans) {
        when (span) {
            is Markdown.Span.Text -> append(span.text)

            is Markdown.Span.Code -> {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = linkColor.copy(alpha = 0.10f),
                    )
                )
                append(span.text)
                pop()
            }

            is Markdown.Span.Styled -> {
                val style = when (span.style) {
                    Markdown.Style.Bold -> SpanStyle(fontWeight = FontWeight.SemiBold)
                    Markdown.Style.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                    Markdown.Style.Strikethrough ->
                        SpanStyle(textDecoration = TextDecoration.LineThrough)
                }
                pushStyle(style)
                appendSpans(span.spans, linkColor)
                pop()
            }

            is Markdown.Span.Link -> {
                val start = length
                pushStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    )
                )
                appendSpans(span.spans, linkColor)
                pop()
                // The annotation is what makes the run clickable; the style above
                // only makes it look like a link.
                addStringAnnotation(LINK_TAG, span.url, start, length)
            }
        }
    }
}

/** The annotation tag that carries a link's URL. */
private const val LINK_TAG = "readon:link"
