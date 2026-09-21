package me.woelki.frad.ui.theme

import androidx.compose.ui.graphics.Color

// A green tonal palette tying back to the launcher icon's background (#0B3D2E, see
// res/values/colors.xml) - hand-picked rather than device wallpaper-derived (no dynamic
// color): a privacy-focused, no-account app benefits from one consistent, recognizable
// identity across devices rather than blending into whatever theme the OS picks.

val FradGreenLight = Color(0xFF0B6E4F)
val FradOnGreenLight = Color(0xFFFFFFFF)
val FradGreenContainerLight = Color(0xFFB6F2D4)
val FradOnGreenContainerLight = Color(0xFF002114)
val FradSecondaryLight = Color(0xFF4C6358)
val FradOnSecondaryLight = Color(0xFFFFFFFF)
// Deliberately still green-family (not Material3's baseline purple default) so components that
// use secondary/tertiary roles - FilterChip's selected state, NavigationBarItem's selected
// indicator - stay consistent with the rest of the app instead of reverting to a clashing accent.
val FradSecondaryContainerLight = Color(0xFFCFE8DA)
val FradOnSecondaryContainerLight = Color(0xFF092016)
val FradTertiaryLight = Color(0xFF3B6470)
val FradOnTertiaryLight = Color(0xFFFFFFFF)
val FradTertiaryContainerLight = Color(0xFFBEEAF8)
val FradOnTertiaryContainerLight = Color(0xFF001F26)
val FradBackgroundLight = Color(0xFFF7FBF8)
val FradOnBackgroundLight = Color(0xFF191C1A)
val FradSurfaceVariantLight = Color(0xFFDCE5DE)
val FradOnSurfaceVariantLight = Color(0xFF404944)
val FradOnSurfaceLight = Color(0xFF191C1A)
val FradOutlineLight = Color(0xFF707974)
val FradErrorLight = Color(0xFFBA1A1A)
val FradErrorContainerLight = Color(0xFFFFDAD6)
val FradOnErrorContainerLight = Color(0xFF410002)

val FradGreenDark = Color(0xFF8ED6B4)
val FradOnGreenDark = Color(0xFF00391F)
val FradGreenContainerDark = Color(0xFF00522F)
val FradOnGreenContainerDark = Color(0xFFB6F2D4)
val FradSecondaryDark = Color(0xFFB2CCBE)
val FradOnSecondaryDark = Color(0xFF1E352C)
val FradSecondaryContainerDark = Color(0xFF344B41)
val FradOnSecondaryContainerDark = Color(0xFFCFE8DA)
val FradTertiaryDark = Color(0xFFA3CDDB)
val FradOnTertiaryDark = Color(0xFF063542)
val FradTertiaryContainerDark = Color(0xFF224C58)
val FradOnTertiaryContainerDark = Color(0xFFBEEAF8)
val FradBackgroundDark = Color(0xFF101412)
val FradOnBackgroundDark = Color(0xFFE0E3E0)
val FradSurfaceVariantDark = Color(0xFF3F4944)
val FradOnSurfaceVariantDark = Color(0xFFBFC9C2)
val FradOnSurfaceDark = Color(0xFFE0E3E0)
val FradOutlineDark = Color(0xFF89938D)
val FradErrorDark = Color(0xFFFFB4AB)
val FradOnErrorDark = Color(0xFF690005)
val FradErrorContainerDark = Color(0xFF93000A)
val FradOnErrorContainerDark = Color(0xFFFFDAD6)

/** Distinct background for the local peer's chat bubbles, separate from [FradGreenContainerLight]/
 *  [FradGreenContainerDark] so message bubbles don't visually collide with buttons/chips using
 *  the primary container color. */
val FradBubbleMineLight = Color(0xFFCDEBDD)
val FradBubbleMineDark = Color(0xFF1D4635)
val FradBubbleTheirsLight = Color(0xFFE7ECE8)
val FradBubbleTheirsDark = Color(0xFF2B302D)
