package com.example.rokidphone.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material Design 3 Shape configuration
 * 
 * Shape Scale:
 * - Extra Small (4dp): Chips, small buttons, badges
 * - Small (8dp): Text fields, tooltips, snackbars
 * - Medium (12dp): Cards, dialogs, menus
 * - Large (16dp): Bottom sheets, navigation drawers
 * - Extra Large (28dp): Full-screen dialogs, prominent containers
 */
val AppShapes = Shapes(
    // Extra small - Used for chips, small buttons, badges
    extraSmall = RoundedCornerShape(4.dp),
    
    // Small - Used for text fields, tooltips, snackbars
    small = RoundedCornerShape(8.dp),
    
    // Medium - Used for cards, dialogs, menus
    medium = RoundedCornerShape(12.dp),
    
    // Large - Used for bottom sheets, navigation drawers, floating action buttons
    large = RoundedCornerShape(16.dp),
    
    // Extra large - Used for full-screen dialogs, prominent containers
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Additional shape utilities for specific use cases
 */
object AppShapeTokens {
    // Chat bubble shapes
    val MessageBubbleUser = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 4.dp,
        bottomStart = 16.dp,
        bottomEnd = 16.dp
    )
    
    val MessageBubbleAssistant = RoundedCornerShape(
        topStart = 4.dp,
        topEnd = 16.dp,
        bottomStart = 16.dp,
        bottomEnd = 16.dp
    )
    
    // Image container shape
    val ImageContainer = RoundedCornerShape(12.dp)
    
    // Status indicator shapes
    val StatusIndicator = RoundedCornerShape(50) // Pill shape
    
    // Bottom sheet top corners
    val BottomSheetTop = RoundedCornerShape(
        topStart = 28.dp,
        topEnd = 28.dp,
        bottomStart = 0.dp,
        bottomEnd = 0.dp
    )
    
    // Card with top emphasis
    val TopEmphasisCard = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = 8.dp,
        bottomEnd = 8.dp
    )
}
