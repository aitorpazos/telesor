package dev.telesor.camera

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compose component that displays decoded camera frames.
 *
 * Wraps a SurfaceView and exposes the Surface for the H264Decoder to render to.
 * Also usable as a local preview on the provider side.
 */
@Composable
fun CameraPreviewView(
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit = {},
) {
    var surfaceReady by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        surfaceReady = true
                        onSurfaceAvailable(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        // Surface size changed — decoder handles this
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        surfaceReady = false
                        onSurfaceDestroyed()
                    }
                })
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(16.dp)),
    )

    DisposableEffect(Unit) {
        onDispose {
            onSurfaceDestroyed()
        }
    }
}
