package fm.rizx.player.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom **brutalist icon set** (industrial / DOOM-UESC geometry, ref #1): heavy solid fills and thick
 * hard-cornered strokes on a 24×24 grid, `StrokeCap.Butt` + `Miter` joins (no rounding). Rendered via
 * `Icon(imageVector, tint = …)` — the tint recolours the whole vector, so paths are authored in black.
 * These replace Material icons at the high-traffic call sites (transport, nav, back/close/add/star …).
 */
object RizxIcons {

    val Play = icon { fill { moveTo(7f, 4f); lineTo(7f, 20f); lineTo(20f, 12f); close() } }

    val Pause = icon {
        fill {
            moveTo(6f, 4f); horizontalLineTo(10f); verticalLineTo(20f); horizontalLineTo(6f); close()
            moveTo(14f, 4f); horizontalLineTo(18f); verticalLineTo(20f); horizontalLineTo(14f); close()
        }
    }

    val SkipNext = icon {
        fill {
            moveTo(5f, 5f); lineTo(5f, 19f); lineTo(15f, 12f); close()
            moveTo(16f, 5f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(16f); close()
        }
    }

    val SkipPrevious = icon {
        fill {
            moveTo(5f, 5f); horizontalLineTo(8f); verticalLineTo(19f); horizontalLineTo(5f); close()
            moveTo(19f, 5f); lineTo(19f, 19f); lineTo(9f, 12f); close()
        }
    }

    val Add = icon {
        fill {
            moveTo(10.5f, 4f); horizontalLineTo(13.5f); verticalLineTo(10.5f); horizontalLineTo(20f)
            verticalLineTo(13.5f); horizontalLineTo(13.5f); verticalLineTo(20f); horizontalLineTo(10.5f)
            verticalLineTo(13.5f); horizontalLineTo(4f); verticalLineTo(10.5f); horizontalLineTo(10.5f); close()
        }
    }

    val Close = icon { stroke(3f) { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }

    val Check = icon { stroke(3f) { moveTo(4.5f, 13f); lineTo(10f, 18.5f); lineTo(19.5f, 6f) } }

    val ChevronRight = icon { stroke(3f) { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) } }
    val ChevronUp = icon { stroke(3f) { moveTo(5f, 15f); lineTo(12f, 8f); lineTo(19f, 15f) } }
    val ChevronDown = icon { stroke(3f) { moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f) } }

    val Back = icon {
        stroke(2.6f) {
            moveTo(20f, 12f); horizontalLineTo(4.5f)
            moveTo(11f, 5f); lineTo(4.5f, 12f); lineTo(11f, 19f)
        }
    }

    val MoreVert = icon {
        fill {
            moveTo(10.5f, 4f); horizontalLineTo(13.5f); verticalLineTo(7f); horizontalLineTo(10.5f); close()
            moveTo(10.5f, 10.5f); horizontalLineTo(13.5f); verticalLineTo(13.5f); horizontalLineTo(10.5f); close()
            moveTo(10.5f, 17f); horizontalLineTo(13.5f); verticalLineTo(20f); horizontalLineTo(10.5f); close()
        }
    }

    val Home = icon {
        fill {
            moveTo(12f, 3f); lineTo(21f, 11f); horizontalLineTo(18f); verticalLineTo(20f); horizontalLineTo(14f)
            verticalLineTo(14f); horizontalLineTo(10f); verticalLineTo(20f); horizontalLineTo(6f); verticalLineTo(11f)
            horizontalLineTo(3f); close()
        }
    }

    val Search = icon {
        stroke(2.6f) {
            moveTo(4f, 10f); arcToRelative(6f, 6f, 0f, false, true, 12f, 0f)
            arcToRelative(6f, 6f, 0f, false, true, -12f, 0f)
            moveTo(14.5f, 14.5f); lineTo(20f, 20f)
        }
    }

    val Library = icon {
        fill {
            moveTo(5f, 6f); horizontalLineTo(8f); verticalLineTo(19f); horizontalLineTo(5f); close()
            moveTo(10.5f, 4f); horizontalLineTo(13.5f); verticalLineTo(19f); horizontalLineTo(10.5f); close()
            moveTo(16f, 9f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(16f); close()
        }
    }

    val Settings = icon {
        stroke(2f) {
            moveTo(4f, 7f); horizontalLineTo(20f); moveTo(4f, 12f); horizontalLineTo(20f)
            moveTo(4f, 17f); horizontalLineTo(20f)
        }
        fill {
            moveTo(5.1f, 5.1f); horizontalLineTo(8.9f); verticalLineTo(8.9f); horizontalLineTo(5.1f); close()
            moveTo(13.1f, 10.1f); horizontalLineTo(16.9f); verticalLineTo(13.9f); horizontalLineTo(13.1f); close()
            moveTo(8.1f, 15.1f); horizontalLineTo(11.9f); verticalLineTo(18.9f); horizontalLineTo(8.1f); close()
        }
    }

    val Shuffle = icon {
        stroke(2.4f) {
            moveTo(3f, 7f); horizontalLineTo(7f); lineTo(17f, 17f); horizontalLineTo(20f)
            moveTo(3f, 17f); horizontalLineTo(7f); lineTo(17f, 7f); horizontalLineTo(20f)
        }
        fill {
            moveTo(17.5f, 4.5f); lineTo(22f, 7f); lineTo(17.5f, 9.5f); close()
            moveTo(17.5f, 14.5f); lineTo(22f, 17f); lineTo(17.5f, 19.5f); close()
        }
    }

    val Repeat = icon {
        stroke(2.4f) {
            moveTo(7f, 8f); verticalLineTo(6f); horizontalLineTo(17f); verticalLineTo(10f)
            moveTo(17f, 16f); verticalLineTo(18f); horizontalLineTo(7f); verticalLineTo(14f)
        }
        fill {
            moveTo(14.5f, 9.5f); lineTo(17f, 13f); lineTo(19.5f, 9.5f); close()
            moveTo(4.5f, 14.5f); lineTo(7f, 11f); lineTo(9.5f, 14.5f); close()
        }
    }

    /**
     * [Repeat] with a "1" struck through the loop — repeat-one. Distinguishing the two repeat modes
     * needs its own glyph: the chip already spends its only other signal (the accent fill) on saying
     * repeat is *on at all*, so ALL and ONE would otherwise look identical.
     */
    val RepeatOne = icon {
        stroke(2.4f) {
            moveTo(7f, 8f); verticalLineTo(6f); horizontalLineTo(17f); verticalLineTo(10f)
            moveTo(17f, 16f); verticalLineTo(18f); horizontalLineTo(7f); verticalLineTo(14f)
        }
        fill {
            moveTo(14.5f, 9.5f); lineTo(17f, 13f); lineTo(19.5f, 9.5f); close()
            moveTo(4.5f, 14.5f); lineTo(7f, 11f); lineTo(9.5f, 14.5f); close()
            // A slab "1" in the loop's only free space (the arrows own x<9.5 and x>14.5). Kept narrow
            // and clear of both so it reads as a numeral at 22dp instead of a blob.
            moveTo(11.4f, 9.4f); horizontalLineTo(13f); verticalLineTo(14.6f); horizontalLineTo(11.4f); close()
            moveTo(10.2f, 9.4f); horizontalLineTo(11.4f); verticalLineTo(10.8f); horizontalLineTo(10.2f); close()
        }
    }

    val Star = icon {
        fill {
            moveTo(12f, 2f); lineTo(14f, 10f); lineTo(22f, 12f); lineTo(14f, 14f); lineTo(12f, 22f)
            lineTo(10f, 14f); lineTo(2f, 12f); lineTo(10f, 10f); close()
        }
    }

    val StarBorder = icon {
        stroke(2f) {
            moveTo(12f, 2f); lineTo(14f, 10f); lineTo(22f, 12f); lineTo(14f, 14f); lineTo(12f, 22f)
            lineTo(10f, 14f); lineTo(2f, 12f); lineTo(10f, 10f); close()
        }
    }

    val Favorite = icon {
        fill {
            moveTo(12f, 20.5f); lineTo(4f, 12f); verticalLineTo(8f); lineTo(7.5f, 5f); lineTo(12f, 8.5f)
            lineTo(16.5f, 5f); lineTo(20f, 8f); verticalLineTo(12f); close()
        }
    }

    val FavoriteBorder = icon {
        stroke(2f) {
            moveTo(12f, 20.5f); lineTo(4f, 12f); verticalLineTo(8f); lineTo(7.5f, 5f); lineTo(12f, 8.5f)
            lineTo(16.5f, 5f); lineTo(20f, 8f); verticalLineTo(12f); close()
        }
    }

    val PlaylistAdd = icon {
        stroke(2.2f) {
            moveTo(4f, 7f); horizontalLineTo(16f); moveTo(4f, 12f); horizontalLineTo(12f)
            moveTo(4f, 17f); horizontalLineTo(12f)
        }
        fill {
            moveTo(16f, 14f); horizontalLineTo(18f); verticalLineTo(16f); horizontalLineTo(20f)
            verticalLineTo(18f); horizontalLineTo(18f); verticalLineTo(20f); horizontalLineTo(16f)
            verticalLineTo(18f); horizontalLineTo(14f); verticalLineTo(16f); horizontalLineTo(16f); close()
        }
    }

    val QueueMusic = icon {
        stroke(2.2f) {
            moveTo(4f, 7f); horizontalLineTo(18f); moveTo(4f, 12f); horizontalLineTo(13f)
            moveTo(4f, 17f); horizontalLineTo(11f)
        }
        fill { moveTo(15f, 14f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(15f); close() }
    }

    val Lyrics = icon {
        stroke(2.2f) {
            moveTo(5f, 6f); horizontalLineTo(19f); moveTo(5f, 11f); horizontalLineTo(19f)
            moveTo(5f, 16f); horizontalLineTo(13f)
        }
    }

    /**
     * "Cast / nearby devices": a screen outline (top + right + partial bottom) with two broadcast arcs and a
     * filled node in the bottom-left corner — the universal cast glyph, drawn brutalist. Opens the system
     * audio-output switcher.
     */
    val Devices = icon {
        stroke(2.2f) {
            moveTo(4f, 10f); verticalLineTo(6f); horizontalLineTo(20f); verticalLineTo(18f); horizontalLineTo(11f)
            moveTo(4f, 14f); arcToRelative(4f, 4f, 0f, false, true, 4f, 4f)
            moveTo(4f, 10.5f); arcToRelative(7.5f, 7.5f, 0f, false, true, 7.5f, 7.5f)
        }
        fill { moveTo(3.2f, 16.8f); horizontalLineTo(5.4f); verticalLineTo(19f); horizontalLineTo(3.2f); close() }
    }

    /**
     * "Radio / start radio": a filled node with two symmetric wave arcs opening left and right — broadcast
     * emanating from the current song. Kept symmetric (unlike [Devices], whose arcs sit in one corner) so the
     * two never read as the same icon side by side.
     */
    val Radio = icon {
        fill { moveTo(10.5f, 10.5f); horizontalLineTo(13.5f); verticalLineTo(13.5f); horizontalLineTo(10.5f); close() }
        stroke(2.2f) {
            moveTo(8f, 9f); arcToRelative(4.2f, 4.2f, 0f, false, false, 0f, 6f)
            moveTo(16f, 9f); arcToRelative(4.2f, 4.2f, 0f, false, true, 0f, 6f)
            moveTo(5.5f, 6.5f); arcToRelative(7.8f, 7.8f, 0f, false, false, 0f, 11f)
            moveTo(18.5f, 6.5f); arcToRelative(7.8f, 7.8f, 0f, false, true, 0f, 11f)
        }
    }
}

// ---- builders ----------------------------------------------------------------

private fun icon(block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = "RizxIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

private fun ImageVector.Builder.fill(block: PathBuilder.() -> Unit) =
    path(fill = SolidColor(Color.Black), pathBuilder = block)

private fun ImageVector.Builder.stroke(width: Float = 2.5f, block: PathBuilder.() -> Unit) =
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Butt,
        strokeLineJoin = StrokeJoin.Miter,
        pathBuilder = block,
    )
