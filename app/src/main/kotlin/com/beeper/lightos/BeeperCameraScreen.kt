package com.beeper.lightos

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
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
import com.thelightphone.sdk.shared.getOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.room
import net.folivo.trixnity.client.room.message.image
import net.folivo.trixnity.client.room.message.video
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.utils.toByteArrayFlow

class BeeperCameraViewModel(
    private val client: MatrixClient,
    private val roomId: String,
) : LightViewModel<Unit>() {

    sealed interface State {
        data object Preview : State
        data class Captured(val bitmap: android.graphics.Bitmap) : State
        data object Recording : State
        data class CapturedVideo(
            val file: java.io.File,
            val durationMs: Long,
            val width: Int?,
            val height: Int?,
            val preview: android.graphics.Bitmap?,
        ) : State
        data object Sending : State
        data object Sent : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Preview)
    val state: StateFlow<State> = _state.asStateFlow()

    fun onCaptured(bitmap: android.graphics.Bitmap) {
        _state.value = State.Captured(bitmap)
    }

    fun onCaptureError(message: String) {
        _state.value = State.Error(message)
    }

    fun retake() {
        (_state.value as? State.CapturedVideo)?.file?.delete()
        _state.value = State.Preview
    }

    fun onRecordingStarted() {
        _state.value = State.Recording
    }

    /** Reads what the recording turned out to be, so the screen can show it before sending. */
    fun onRecorded(file: java.io.File) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                fun meta(key: Int) = retriever.extractMetadata(key)
                val duration = meta(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val rawWidth = meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val rawHeight = meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                // The stored frame size ignores rotation; a portrait recording needs the swap.
                val rotated = meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()?.let { it == 90 || it == 270 } == true
                _state.value = State.CapturedVideo(
                    file = file,
                    durationMs = duration,
                    width = if (rotated) rawHeight else rawWidth,
                    height = if (rotated) rawWidth else rawHeight,
                    preview = retriever.getFrameAtTime(0),
                )
            } catch (e: Exception) {
                android.util.Log.e("BeeperCamera", "Could not read the recording", e)
                file.delete()
                _state.value = State.Error("Recording failed")
            } finally {
                retriever.release()
            }
        }
    }

    fun sendVideo() {
        val current = _state.value
        if (current !is State.CapturedVideo) return
        _state.value = State.Sending
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = current.file.readBytes()
                val thumbnail = current.preview?.let { frame ->
                    val scale = 480f / maxOf(frame.width, frame.height)
                    val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(
                        frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true,
                    ) else frame
                    val out = java.io.ByteArrayOutputStream()
                    small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                    small to out.toByteArray()
                }

                client.room.sendMessage(RoomId(roomId)) {
                    video(
                        body = "video.mp4",
                        video = bytes.toByteArrayFlow(),
                        fileName = "video.mp4",
                        type = io.ktor.http.ContentType.Video.MP4,
                        size = bytes.size.toLong(),
                        height = current.height,
                        width = current.width,
                        duration = current.durationMs,
                        thumbnail = thumbnail?.second?.toByteArrayFlow(),
                        thumbnailInfo = thumbnail?.let { (small, jpeg) ->
                            net.folivo.trixnity.core.model.events.m.room.ThumbnailInfo(
                                height = small.height,
                                width = small.width,
                                mimeType = "image/jpeg",
                                size = jpeg.size.toLong(),
                            )
                        },
                    )
                }
                current.file.delete()
                _state.value = State.Sent
            } catch (e: Exception) {
                android.util.Log.e("BeeperCamera", "Failed to send video", e)
                _state.value = State.Error("Sending failed")
            }
        }
    }

    fun send() {
        val current = _state.value
        if (current !is State.Captured) return
        _state.value = State.Sending
        viewModelScope.launch {
            try {
                // Keep uploads modest: WhatsApp-size, not 50 MP.
                val bitmap = current.bitmap
                val maxEdge = 1600
                val scale = maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height)
                val scaled = if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt(),
                        (bitmap.height * scale).toInt(),
                        true,
                    )
                } else bitmap
                val stream = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
                val bytes = stream.toByteArray()

                client.room.sendMessage(RoomId(roomId)) {
                    image(
                        body = "photo.jpg",
                        image = bytes.toByteArrayFlow(),
                        fileName = "photo.jpg",
                        type = io.ktor.http.ContentType.Image.JPEG,
                        size = bytes.size.toLong(),
                        width = scaled.width,
                        height = scaled.height,
                    )
                }
                _state.value = State.Sent
            } catch (e: Exception) {
                android.util.Log.e("BeeperCamera", "Failed to send photo", e)
                _state.value = State.Error("Sending failed")
            }
        }
    }
}

class BeeperCameraScreen(
    sealedActivity: SealedLightActivity,
    private val roomId: String,
) : LightScreen<Unit, BeeperCameraViewModel>(sealedActivity) {

    override val viewModelClass: Class<BeeperCameraViewModel>
        get() = BeeperCameraViewModel::class.java

    override fun createViewModel() =
        BeeperCameraViewModel(BeeperRepository.getClient()!!, roomId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        val permissionLauncher =
            com.thelightphone.sdk.rememberPermissionRequestLauncher(android.Manifest.permission.CAMERA)

        val appContext = BeeperRepository.appContext
        // Android's own answer, not LightOS's bookkeeping: a permission granted over
        // adb is invisible to the server but is what the camera actually goes by.
        val cameraGranted = remember(appContext) {
            appContext?.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val captureExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
        val controller = remember(appContext, cameraGranted) {
            appContext?.takeIf { cameraGranted }?.let {
                androidx.camera.view.LifecycleCameraController(it).apply {
                    setEnabledUseCases(
                        androidx.camera.view.CameraController.IMAGE_CAPTURE or
                            androidx.camera.view.CameraController.VIDEO_CAPTURE
                    )
                    // SD keeps a minute of video a few megabytes - chat-sized, like the photos.
                    videoCaptureQualitySelector = androidx.camera.video.QualitySelector.from(
                        androidx.camera.video.Quality.SD,
                        androidx.camera.video.FallbackStrategy.higherQualityOrLowerThan(
                            androidx.camera.video.Quality.SD
                        ),
                    )
                    cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
                }
            }
        }

        var recording by remember { mutableStateOf<androidx.camera.video.Recording?>(null) }
        var elapsedMs by remember { mutableStateOf(0L) }
        val isRecording = state is BeeperCameraViewModel.State.Recording

        // Tick the counter while recording, and stop at the cap so a forgotten
        // recording cannot grow into an upload nobody wants to wait for.
        androidx.compose.runtime.LaunchedEffect(isRecording) {
            if (!isRecording) return@LaunchedEffect
            val startedAt = android.os.SystemClock.elapsedRealtime()
            while (true) {
                elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt
                if (elapsedMs >= MAX_RECORDING_MS) {
                    recording?.stop()
                    break
                }
                kotlinx.coroutines.delay(250)
            }
        }

        DisposableEffect(controller) {
            controller?.bindToLifecycle(androidx.lifecycle.ProcessLifecycleOwner.get())
            onDispose {
                recording?.stop()
                controller?.unbind()
                captureExecutor.shutdown()
            }
        }

        // Leave the screen once the photo is queued for sending.
        androidx.compose.runtime.LaunchedEffect(state) {
            if (state is BeeperCameraViewModel.State.Sent) {
                goBack(null)
            }
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack(null) },
                        ),
                        center = LightTopBarCenter.Text("Camera"),
                        rightButton = null,
                    )

                    when (val s = state) {
                        is BeeperCameraViewModel.State.Preview,
                        is BeeperCameraViewModel.State.Recording -> {
                            if (!cameraGranted) {
                                Column(modifier = Modifier.padding(1f.gridUnitsAsDp())) {
                                    LightText(
                                        text = "No camera permission, so the preview stays black.",
                                        variant = LightTextVariant.Copy,
                                    )
                                    LightText(
                                        text = "LightOS does not hand CAMERA to tools yet. Until it does:" +
                                            "\nadb shell pm grant me.ironfeet.beeper4lightos " +
                                            "android.permission.CAMERA",
                                        variant = LightTextVariant.Fine,
                                        lighten = true,
                                        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 1f.gridUnitsAsDp())
                                            .lightClickable { permissionLauncher?.launch() },
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        LightText(text = "Ask anyway", variant = LightTextVariant.Copy)
                                    }
                                }
                            } else if (controller == null) {
                                LightText(
                                    text = "Camera unavailable.",
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier.padding(1f.gridUnitsAsDp()),
                                )
                            } else {
                                AndroidView(
                                    factory = { viewContext ->
                                        androidx.camera.view.PreviewView(viewContext).apply {
                                            this.controller = controller
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 1f.gridUnitsAsDp())
                                        .lightClickable {
                                            if (isRecording) return@lightClickable
                                            controller.takePicture(
                                                captureExecutor,
                                                object : androidx.camera.core.ImageCapture.OnImageCapturedCallback() {
                                                    override fun onCaptureSuccess(imageProxy: androidx.camera.core.ImageProxy) {
                                                        try {
                                                            val bitmap = imageProxy.toBitmap()
                                                            val rotation = imageProxy.imageInfo.rotationDegrees
                                                            val rotated = if (rotation != 0) {
                                                                val matrix = android.graphics.Matrix().apply {
                                                                    postRotate(rotation.toFloat())
                                                                }
                                                                android.graphics.Bitmap.createBitmap(
                                                                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true,
                                                                )
                                                            } else bitmap
                                                            viewModel.onCaptured(rotated)
                                                        } catch (e: Exception) {
                                                            android.util.Log.e("BeeperCamera", "Capture processing failed", e)
                                                            viewModel.onCaptureError("Capture failed")
                                                        } finally {
                                                            imageProxy.close()
                                                        }
                                                    }

                                                    override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                                                        android.util.Log.e("BeeperCamera", "Capture failed", exception)
                                                        viewModel.onCaptureError("Capture failed")
                                                    }
                                                },
                                            )
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    LightText(text = "Take Photo", variant = LightTextVariant.Copy, lighten = isRecording)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 1f.gridUnitsAsDp())
                                        .lightClickable {
                                            val active = recording
                                            if (active != null) {
                                                active.stop()
                                            } else {
                                                recording = startRecording(controller, captureExecutor) { recording = null }
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    LightText(
                                        text = if (isRecording) {
                                            "Stop  ${elapsedMs / 60_000}:${"%02d".format(elapsedMs / 1000 % 60)}"
                                        } else "Record Video",
                                        variant = LightTextVariant.Copy,
                                    )
                                }
                            }
                        }

                        is BeeperCameraViewModel.State.Captured -> {
                            Image(
                                bitmap = s.bitmap.asImageBitmap(),
                                contentDescription = "Captured photo",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1f.gridUnitsAsDp()),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                Box(modifier = Modifier.lightClickable { viewModel.retake() }) {
                                    LightText(text = "Retake", variant = LightTextVariant.Copy, lighten = true)
                                }
                                Box(modifier = Modifier.lightClickable { viewModel.send() }) {
                                    LightText(text = "Send", variant = LightTextVariant.Copy)
                                }
                            }
                        }

                        is BeeperCameraViewModel.State.CapturedVideo -> {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                // Play the clip itself, looping, so you see what you are about to
                                // send; the first frame only stands in if playback fails.
                                var playbackFailed by remember(s.file) { mutableStateOf(false) }
                                if (!playbackFailed) {
                                    LocalVideoPlayer(
                                        file = s.file,
                                        loop = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        onError = { playbackFailed = true },
                                    )
                                } else {
                                    s.preview?.let {
                                        Image(
                                            bitmap = it.asImageBitmap(),
                                            contentDescription = "Recorded video",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                            LightText(
                                text = "Video  ${s.durationMs / 60_000}:${"%02d".format(s.durationMs / 1000 % 60)}" +
                                    "  ·  ${"%.1f".format(s.file.length() / 1_048_576f)} MB",
                                variant = LightTextVariant.Fine,
                                lighten = true,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = 0.5f.gridUnitsAsDp()),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1f.gridUnitsAsDp()),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                Box(modifier = Modifier.lightClickable { viewModel.retake() }) {
                                    LightText(text = "Retake", variant = LightTextVariant.Copy, lighten = true)
                                }
                                Box(modifier = Modifier.lightClickable { viewModel.sendVideo() }) {
                                    LightText(text = "Send", variant = LightTextVariant.Copy)
                                }
                            }
                        }

                        is BeeperCameraViewModel.State.Sending,
                        is BeeperCameraViewModel.State.Sent -> {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                LightText(text = "Sending...", variant = LightTextVariant.Copy, lighten = true)
                            }
                        }

                        is BeeperCameraViewModel.State.Error -> {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(1f.gridUnitsAsDp()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                LightText(text = s.message, variant = LightTextVariant.Copy)
                                Box(
                                    modifier = Modifier
                                        .padding(top = 1f.gridUnitsAsDp())
                                        .lightClickable { viewModel.retake() },
                                ) {
                                    LightText(text = "Try Again", variant = LightTextVariant.Copy, lighten = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Records to the cache. With RECORD_AUDIO granted the clip has sound; without it
     * (LightOS does not grant it to tools) it records silently rather than failing.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun startRecording(
        controller: androidx.camera.view.LifecycleCameraController,
        executor: java.util.concurrent.Executor,
        onFinished: () -> Unit,
    ): androidx.camera.video.Recording? {
        val context = BeeperRepository.appContext ?: return null
        val dir = java.io.File(context.cacheDir, "recordings").apply { mkdirs() }
        val file = java.io.File(dir, "video-${System.currentTimeMillis()}.mp4")
        val withSound = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        return try {
            controller.startRecording(
                androidx.camera.video.FileOutputOptions.Builder(file).build(),
                if (withSound) androidx.camera.view.video.AudioConfig.create(true)
                else androidx.camera.view.video.AudioConfig.AUDIO_DISABLED,
                executor,
            ) { event ->
                if (event is androidx.camera.video.VideoRecordEvent.Finalize) {
                    onFinished()
                    if (event.hasError()) {
                        android.util.Log.e("BeeperCamera", "Recording failed: ${event.error}", event.cause)
                        file.delete()
                        viewModel.onCaptureError("Recording failed")
                    } else {
                        viewModel.onRecorded(file)
                    }
                }
            }.also { viewModel.onRecordingStarted() }
        } catch (e: Exception) {
            android.util.Log.e("BeeperCamera", "Could not start recording", e)
            viewModel.onCaptureError("Could not start recording")
            null
        }
    }

    private companion object {
        const val MAX_RECORDING_MS = 60_000L
    }
}
