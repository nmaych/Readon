package com.readon.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readon.reader.ui.theme.LocalReadonExtras

/**
 * A generated book cover.
 *
 * There are no cover images in a plain-text library, and fetching one per book
 * would mean an image loader the offline dependency budget does not have. So
 * each book gets a deterministic gradient derived from its title, with the
 * title set on it: two books never look alike, and the same book always looks
 * the same. That stability is the point -- a cover that changed between launches
 * would make the shelf unreadable at a glance.
 */
@Composable
fun BookCover(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    progress: Float? = null,
) {
    val extras = LocalReadonExtras.current
    val palette = extras.coverPalette
    // A deterministic pair per title: two books never share a cover, and the
    // same book keeps its cover across launches, which is what makes the shelf
    // scannable. floorMod keeps the index positive for any hashCode.
    val pair = if (palette.isEmpty()) {
        MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.secondary
    } else {
        palette[Math.floorMod(title.hashCode(), palette.size)]
    }
    // A second, title-derived angle keeps two books with adjacent hues from
    // producing the same diagonal.
    val angle = ((title.hashCode() ushr 8) % 4) * 45f

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(pair.first, pair.second),
                    start = gradientStart(angle),
                    end = gradientEnd(angle),
                )
            ),
    ) {
        // A translucent spine down the left edge, echoing the app icon.
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = 0.dp)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .width(6.dp)
                    .background(Color.Black.copy(alpha = 0.14f))
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Column {
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress != null && progress > 0.001f) {
                    Spacer(Modifier.height(6.dp))
                    ProgressTrack(progress)
                }
            }
        }

        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.34f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = badge,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ProgressTrack(progress: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.3f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxSize()
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White)
        )
    }
}

private fun gradientStart(angle: Float) = when (angle.toInt()) {
    45 -> androidx.compose.ui.geometry.Offset(0f, 0f)
    90 -> androidx.compose.ui.geometry.Offset(0.5f, 0f)
    135 -> androidx.compose.ui.geometry.Offset(1f, 0f)
    else -> androidx.compose.ui.geometry.Offset(0f, 0.5f)
}

private fun gradientEnd(angle: Float) = when (angle.toInt()) {
    45 -> androidx.compose.ui.geometry.Offset(1f, 1f)
    90 -> androidx.compose.ui.geometry.Offset(0.5f, 1f)
    135 -> androidx.compose.ui.geometry.Offset(0f, 1f)
    else -> androidx.compose.ui.geometry.Offset(1f, 0.5f)
}

/** A centred, friendly empty state. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** A small pill used to label a book's format or collection. */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Key/value row used through the settings screen. */
@Composable
fun LabeledRow(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}
