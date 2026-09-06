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
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.utils.toByteArrayFlow

class BeeperCameraViewModel(
    private val client: MatrixClient,
    private val roomId: String,
) : LightViewModel<Unit>() {

    sealed interface State {
        data object Preview : State
        data class Captured(val bitmap: android.graphics.Bitmap) : State
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
        _state.value = State.Preview
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
        androidx.compose.runtime.LaunchedEffect(permissionLauncher) {
            val result = com.thelightphone.sdk.checkPermission(android.Manifest.permission.CAMERA)
            val isGranted = result.getOrNull()?.permissionResult ==
                com.thelightphone.sdk.shared.LightServiceMethod.GetPermission.Result.Granted
            if (!isGranted) {
                permissionLauncher?.launch()
            }
        }

        val appContext = BeeperRepository.appContext
        val captureExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
        val controller = remember(appContext) {
            appContext?.let {
                androidx.camera.view.LifecycleCameraController(it).apply {
                    setEnabledUseCases(androidx.camera.view.CameraController.IMAGE_CAPTURE)
                    cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
                }
            }
        }

        DisposableEffect(controller) {
            controller?.bindToLifecycle(androidx.lifecycle.ProcessLifecycleOwner.get())
            onDispose {
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
                        center = LightTopBarCenter.Text("Photo"),
                        rightButton = null,
                    )

                    when (val s = state) {
                        is BeeperCameraViewModel.State.Preview -> {
                            if (controller == null) {
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
                                    LightText(text = "Take Photo", variant = LightTextVariant.Copy)
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
}
