package com.example.rokidphone.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.rokidphone.R

/**
 * Navigation destinations for the app
 * Using string-based routes compatible with Navigation Compose 2.7.x
 */
object NavRoutes {
    const val HOME = "home"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val GALLERY = "gallery"
    const val CONVERSATION_HISTORY = "conversation_history"
    const val CONVERSATION_DETAIL = "conversation/{conversationId}"
    const val LOG_VIEWER = "log_viewer"
    const val LLM_PARAMETERS = "llm_parameters"
    const val RECORDINGS = "recordings"
    const val RECORDING_DETAIL = "recording/{recordingId}"
    
    fun conversationDetail(conversationId: String) = "conversation/$conversationId"
    fun recordingDetail(recordingId: String) = "recording/$recordingId"
}

/**
 * Bottom navigation items configuration
 */
enum class BottomNavDestination(
    val route: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val labelResId: Int
) {
    HOME(
        route = NavRoutes.HOME,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
        labelResId = R.string.nav_home
    ),
    GALLERY(
        route = NavRoutes.GALLERY,
        selectedIcon = Icons.Filled.PhotoLibrary,
        unselectedIcon = Icons.Outlined.PhotoLibrary,
        labelResId = R.string.nav_gallery
    ),
    CHAT(
        route = NavRoutes.CHAT,
        selectedIcon = Icons.AutoMirrored.Filled.Chat,
        unselectedIcon = Icons.AutoMirrored.Outlined.Chat,
        labelResId = R.string.nav_chat
    ),
    SETTINGS(
        route = NavRoutes.SETTINGS,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        labelResId = R.string.nav_settings
    )
}
