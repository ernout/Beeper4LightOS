package com.beeper.lightos

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.folivo.trixnity.client.media
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent

/**
 * Plays a video message full screen.
 *
 * The chat only ever drew a thumbnail with a play icon on it; nothing played. The
 * file is downloaded once into the cache (decrypted when the room is encrypted)
 * and handed to MediaPlayer on a TextureView. Tap the picture to pause or resume.
 */
class BeeperVideoScreen(
    sealedActivity: SealedLightActivity,
    private val video: RoomMessageEventContent.FileBased.Video,
) : SimpleLightScreen<Unit>(sealedActivity) {

    private sealed interface Load {
        data object Downloading : Load
        data class Ready(val file: java.io.File) : Load
        data class Failed(val reason: String) : Load
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var load by remember { mutableStateOf<Load>(Load.Downloading) }
        var videoAspect by remember { mutableStateOf<Float?>(null) }
        var isPlaying by remember { mutableStateOf(false) }
        val player = remember { MediaPlayer() }

        LaunchedEffect(video) {
            load = download()
        }

        DisposableEffect(Unit) {
            onDispose {
                try {
                    if (player.isPlaying) player.stop()
                } catch (_: IllegalStateException) {
                }
                player.release()
            }
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack(Unit) },
                    ),
                    center = LightTopBarCenter.Text("Video"),
                    rightButton = null,
                )

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val state = load) {
                        Load.Downloading -> LightText("Loading video…", variant = LightTextVariant.Copy)
                        is Load.Failed -> LightText(
                            text = state.reason,
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.padding(1f.gridUnitsAsDp()),
                        )
                        is Load.Ready -> AndroidView(
                            factory = { viewContext ->
                                TextureView(viewContext).apply {
                                    surfaceTextureListener = playerListener(
                                        player = player,
                                        file = state.file,
                                        onSize = { w, h -> if (w > 0 && h > 0) videoAspect = w.toFloat() / h },
                                        onPlaying = { isPlaying = it },
                                        onError = { load = Load.Failed("Could not play this video.") },
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .let { m -> videoAspect?.let { m.aspectRatio(it) } ?: m }
                                .lightClickable {
                                    try {
                                        if (player.isPlaying) player.pause() else player.start()
                                        isPlaying = player.isPlaying
                                    } catch (e: IllegalStateException) {
                                        Log.w(TAG, "Tap before the player was ready", e)
                                    }
                                },
                        )
                    }
                }

                if (load is Load.Ready) {
                    LightText(
                        text = if (isPlaying) "Tap to pause" else "Tap to play",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 1f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }

    /** Downloads the video into the cache once; later plays reuse the file. */
    private suspend fun download(): Load = withContext(Dispatchers.IO) {
        val client = BeeperRepository.getClient() ?: return@withContext Load.Failed("Not connected.")
        val context = BeeperRepository.appContext ?: return@withContext Load.Failed("Not ready yet.")
        val source = video.file?.url ?: video.url ?: return@withContext Load.Failed("This video has no file.")

        val dir = java.io.File(context.cacheDir, "matrix_videos").apply { mkdirs() }
        val target = java.io.File(dir, "${source.hashCode()}.mp4")
        if (target.length() > 0) return@withContext Load.Ready(target)

        try {
            val media = (video.file?.let { client.media.getEncryptedMedia(it) }
                ?: video.url?.let { client.media.getMedia(it) })
                ?.getOrNull()
                ?: return@withContext Load.Failed("Could not download this video.")

            // Stream to a temporary name so a half-finished download is never played.
            val partial = java.io.File(dir, "${source.hashCode()}.part")
            partial.outputStream().use { out -> media.collect { chunk -> out.write(chunk) } }
            if (!partial.renameTo(target)) return@withContext Load.Failed("Could not save this video.")
            Load.Ready(target)
        } catch (e: Exception) {
            Log.e(TAG, "Video download failed", e)
            Load.Failed("Could not download this video.")
        }
    }

    private fun playerListener(
        player: MediaPlayer,
        file: java.io.File,
        onSize: (Int, Int) -> Unit,
        onPlaying: (Boolean) -> Unit,
        onError: () -> Unit,
    ) = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            try {
                player.setSurface(Surface(texture))
                player.setDataSource(file.absolutePath)
                player.setOnVideoSizeChangedListener { _, w, h -> onSize(w, h) }
                player.setOnPreparedListener {
                    it.start()
                    onPlaying(true)
                }
                player.setOnCompletionListener { onPlaying(false) }
                player.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    onError()
                    true
                }
                player.prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "Could not start playback", e)
                onError()
            }
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    private companion object {
        const val TAG = "BeeperVideo"
    }
}
