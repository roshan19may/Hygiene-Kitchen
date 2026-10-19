package com.kitchenlens.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import com.kitchenlens.app.util.ImageTools
import kotlinx.coroutines.delay
import androidx.concurrent.futures.await
import java.io.File

enum class CaptureMode { PHOTO, VIDEO }

/**
 * Full-screen in-app camera. Gallery uploads are deliberately not allowed, so
 * every photo/clip is captured live at the time it is logged.
 * Video is recorded WITHOUT audio, limited to [maxSeconds].
 */
@Composable
fun CameraCapture(
    mode: CaptureMode,
    hint: String,
    onCaptured: (file: File, seconds: Int) -> Unit,
    onCancel: () -> Unit,
    minSeconds: Int = 5,
    maxSeconds: Int = 20,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (granted) {
        CameraBody(mode, hint, onCaptured, onCancel, minSeconds, maxSeconds)
    } else {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera permission is needed to record hygiene proof.")
            Spacer(Modifier.size(12.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

private suspend fun cameraProvider(context: Context): ProcessCameraProvider =
    ProcessCameraProvider.getInstance(context).await()

@Composable
private fun CameraBody(
    mode: CaptureMode,
    hint: String,
    onCaptured: (File, Int) -> Unit,
    onCancel: () -> Unit,
    minSeconds: Int,
    maxSeconds: Int,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    // Speed: capture about 2 MP instead of the full 12–50 MP sensor, with the fastest shutter mode.
    // Photos are shrunk to 1280 px anyway, so this only removes waiting (capture, save and decode).
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()
            )
            .setJpegQuality(90)
            .build()
    }
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
                .setTargetVideoEncodingBitRate(1_500_000) // ~4 MB for a 20 s clip
                .build()
        )
    }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Live date-time shown over the camera; photos get it burned in when saved,
    // and clips are shown with a running timestamp wherever they play.
    var clock by remember { mutableStateOf(ImageTools.stampText(System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = ImageTools.stampText(System.currentTimeMillis())
            delay(1000)
        }
    }

    LaunchedEffect(mode) {
        val provider = cameraProvider(context)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        provider.unbindAll()
        val useCase = if (mode == CaptureMode.PHOTO) imageCapture else videoCapture
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, useCase)
    }
    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    fun takePhoto() {
        busy = true
        val file = File(context.cacheDir, "cap_${System.currentTimeMillis()}.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    busy = false
                    onCaptured(file, 0)
                }
                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    error = "Photo failed: ${exception.message}"
                }
            },
        )
    }

    fun startClip() {
        error = null
        seconds = 0
        val file = File(context.cacheDir, "clip_${System.currentTimeMillis()}.mp4")
        recording = videoCapture.output
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Status -> {
                        seconds = (event.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
                        if (seconds >= maxSeconds) recording?.stop()
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        when {
                            event.hasError() -> { error = "Recording failed (code ${event.error}). Try again."; file.delete() }
                            seconds < minSeconds -> { error = "Too short. Record at least $minSeconds seconds."; file.delete() }
                            else -> onCaptured(file, seconds)
                        }
                    }
                    else -> Unit
                }
            }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Column(Modifier.fillMaxWidth().systemBarsPadding().padding(16.dp)) {
            Text(
                clock,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                hint,
                color = Color.White,
                modifier = Modifier
                    .background(Color(0x99000000), RoundedCornerShape(10.dp))
                    .padding(12.dp),
            )
            if (error != null) {
                Spacer(Modifier.size(8.dp))
                Text(
                    error!!,
                    color = Color.White,
                    modifier = Modifier.background(Color(0xCCC0392B), RoundedCornerShape(10.dp)).padding(12.dp),
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (mode == CaptureMode.VIDEO) {
                val isRec = recording != null
                Text(
                    if (isRec) "● REC  0:%02d / 0:%02d".format(seconds, maxSeconds)
                    else "Clip: $minSeconds–$maxSeconds seconds, no sound",
                    color = if (isRec) Color(0xFFFF6B5E) else Color.White,
                    fontWeight = FontWeight.Bold,
                )
                if (isRec) {
                    LinearProgressIndicator(
                        progress = { seconds / maxSeconds.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { recording?.stop(); onCancel() }) { Text("Cancel", color = Color.White) }
                Spacer(Modifier.width(32.dp))
                val isRec = recording != null
                Box(
                    Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .background(
                            if (mode == CaptureMode.VIDEO) Color(0xFFE53935) else Color.White,
                            if (isRec) RoundedCornerShape(8.dp) else CircleShape,
                        )
                        .clickable(enabled = !busy) {
                            when {
                                mode == CaptureMode.PHOTO -> takePhoto()
                                isRec -> recording?.stop()
                                else -> startClip()
                            }
                        },
                )
                Spacer(Modifier.width(32.dp))
                Spacer(Modifier.width(64.dp))
            }
        }
    }
}
