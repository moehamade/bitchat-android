package com.bitchat.android.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.contains
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet

/**
 * Shows entries marked with [sheet] as a bottom sheet over the destinations
 * beneath them, which stay composed.
 *
 * The destination-type rule puts a leaf here: one list or action set, no
 * sub-screens, dismissed by tapping away. Anything with internal navigation, a
 * stacked destination or an Activity result is a full-screen route instead.
 */
class SheetSceneStrategy : SceneStrategy<NavKey> {

    override fun SceneStrategyScope<NavKey>.calculateScene(
        entries: List<NavEntry<NavKey>>,
    ): Scene<NavKey>? {
        val top = entries.lastOrNull() ?: return null
        if (!top.metadata.contains(SheetKey)) return null
        val beneath = entries.dropLast(1)
        return SheetScene(
            key = top.contentKey,
            previousEntries = beneath,
            overlaidEntries = beneath,
            entry = top,
            onBack = onBack,
        )
    }

    companion object {
        /** Entry metadata that shows the entry as a sheet. */
        fun sheet(): Map<String, Any> = metadata { put(SheetKey, Unit) }

        private object SheetKey : NavMetadataKey<Unit>
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private data class SheetScene(
    override val key: Any,
    override val previousEntries: List<NavEntry<NavKey>>,
    override val overlaidEntries: List<NavEntry<NavKey>>,
    private val entry: NavEntry<NavKey>,
    private val onBack: () -> Unit,
) : OverlayScene<NavKey> {

    override val entries: List<NavEntry<NavKey>> = listOf(entry)

    // Outside the constructor, so it is not part of equality. NavDisplay keeps
    // the scene instance it first composed for a key and removes that one, so
    // the state set here is the state onRemove sees.
    private var sheetState: SheetState? = null

    override val content: @Composable () -> Unit = {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        SideEffect { sheetState = state }
        // A swipe, a scrim tap, Back and the sheet's own close button all end in
        // onDismissRequest, and the sheet's window keeps taking Back presses
        // while it animates out. onBack pops, and finishes the app when there is
        // nothing left to pop, so a second press during the animation must not
        // reach it: pop once per sheet.
        var dismissed by remember { mutableStateOf(false) }
        // The sheet is its own window. Content that observes lifecycle, such as
        // a resume effect, follows the sheet rather than the Activity.
        val lifecycleOwner = rememberLifecycleOwner()
        BitchatBottomSheet(
            sheetState = state,
            onDismissRequest = {
                if (!dismissed) {
                    dismissed = true
                    onBack()
                }
            },
        ) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                entry.Content()
            }
        }
    }

    /**
     * Slides the sheet down when it is popped from outside, such as the stack
     * being reset, rather than letting it vanish mid-frame. A sheet the user
     * dismissed has already hidden, and hiding it again does nothing.
     */
    override suspend fun onRemove() {
        runCatching { sheetState?.hide() }
    }
}
