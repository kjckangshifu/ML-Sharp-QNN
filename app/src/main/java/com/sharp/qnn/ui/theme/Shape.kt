package com.sharp.qnn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * MD3 形状 token 体系。
 * MD3 shape token system.
 *
 * 完整覆盖 MD3 规范的 corner 级别:
 * Covers all MD3 spec corner levels:
 * no rounding
 * Snackbars, small elements
 * text fields, menus
 * cards
 * FABs, navigation drawers
 * dialogs, bottom sheets
 * buttons, chips, badges (fully rounded)
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
