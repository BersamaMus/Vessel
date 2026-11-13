package com.better_video_player.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Makes mpv thumbnails in a process of its own (":thumbnails", see the manifest), as the
// desktop app runs its thumbnailer as worker processes: a file that crashes the decoder only
// takes this process down, and MpvThumbnailer, in the app, remembers that file as unreadable.
//
// Asked over a Messenger, one video at a time:
//   HELLO -> HELLO with arg1 = this process's id (so the app can end it)
//   GRAB {uri, target} -> DONE {ok, duration}
class ThumbnailService : Service() {
    private val thread = HandlerThread("thumbnail-requests").apply { start() }
    private var grabber: FrameGrabber? = null

    private val messenger = Messenger(Handler(thread.looper) { msg -> handle(msg); true })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handle(msg: Message) {
        val reply = msg.replyTo ?: return
        val answer = when (msg.what) {
            HELLO -> Message.obtain(null, HELLO, Process.myPid(), 0)
            GRAB -> {
                val data = msg.data
                val uri = Uri.parse(data.getString(KEY_URI))
                val target = File(data.getString(KEY_TARGET)!!)
                val g = grabber ?: FrameGrabber(this).also { grabber = it }
                val (ok, duration) = g.grab(uri, target)
                Message.obtain(null, DONE).apply {
                    this.data = Bundle().apply {
                        putBoolean(KEY_OK, ok)
                        putLong(KEY_DURATION, duration)
                    }
                }
            }
            else -> return
        }
        try {
            reply.send(answer)
        } catch (_: RemoteException) {
            // The app went away; nothing to answer.
        }
    }

    override fun onDestroy() {
        grabber?.release()
        thread.quitSafely()
    }

    companion object {
        const val HELLO = 1
        const val GRAB = 2
        const val DONE = 3
        const val KEY_URI = "uri"
        const val KEY_TARGET = "target"
        const val KEY_OK = "ok"
        const val KEY_DURATION = "duration"
    }
}

// One frame 20% into a video, drawn by mpv at thumbnail size and saved as a JPEG.
//
// This libmpv build has no image encoders (so vo=image can't write files) nor scaling filters,
// so mpv draws the frame with its usual GPU output into a thumbnail-sized ImageReader instead
// of the screen, and it's read back from there. The GPU does the scaling; panscan crops to
// 16:9 as the cards do.
private class FrameGrabber(context: Context) {
    private val context = context.applicationContext
    private val readerThread = HandlerThread("thumbnail-frames").apply { start() }
    private val reader = ImageReader.newInstance(
        WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2,
        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN,
    )
    private val mpv: MPVLib = MPVLib.create(this.context) ?: error("libmpv couldn't start")

    // The grab in progress.
    @Volatile private var grabbing = false
    @Volatile private var frame: Bitmap? = null
    @Volatile private var duration = 0.0
    @Volatile private var ended: CountDownLatch? = null

    init {
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { if (grabbing) frame = toBitmap(it) }
        }, Handler(readerThread.looper))

        // As the desktop's thumbnail workers: no sound or subtitles, decode in software, jump
        // to the nearest keyframe 20% in, and draw that one frame.
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("panscan", "1.0")
        mpv.setOptionString("ao", "null")
        mpv.setOptionString("aid", "no")
        mpv.setOptionString("sid", "no")
        mpv.setOptionString("osd-level", "0")
        mpv.setOptionString("hwdec", "no")
        mpv.setOptionString("start", "20%")
        mpv.setOptionString("hr-seek", "no")
        mpv.setOptionString("frames", "1")
        mpv.setOptionString("idle", "yes")
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("input-default-bindings", "no")
        mpv.setOptionString("config", "no")
        mpv.init()
        mpv.attachSurface(reader.surface)
        mpv.setPropertyString("android-surface-size", "${WIDTH}x$HEIGHT")
        mpv.observeProperty("duration", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.addObserver(object : MPVLib.EventObserver {
            override fun eventProperty(property: String) {}
            override fun eventProperty(property: String, value: Long) {}
            override fun eventProperty(property: String, value: Boolean) {}
            override fun eventProperty(property: String, value: String) {}
            override fun eventProperty(property: String, value: Double) {
                if (property == "duration" && grabbing) duration = value
            }
            override fun event(eventId: Int) {
                if (eventId == MpvEvent.MPV_EVENT_END_FILE) ended?.countDown()
            }
        })
    }

    // Whether a thumbnail was saved, and the video's duration in ms (0 if not known).
    fun grab(uri: Uri, target: File): Pair<Boolean, Long> {
        val url = urlFor(uri) ?: return false to 0L
        frame = null
        duration = 0.0
        val latch = CountDownLatch(1)
        ended = latch
        grabbing = true
        mpv.command(arrayOf("loadfile", url, "replace"))
        if (!latch.await(GRAB_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Gave up on $uri")
            mpv.command(arrayOf("stop"))
        }
        // The last frame drawn can reach the reader just after the file ends.
        Thread.sleep(100)
        grabbing = false
        ended = null
        val ok = frame?.let { save(it, target) } ?: false
        frame = null
        return ok to (duration * 1000).toLong()
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        return if (rowPixels == image.width) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
    }

    private fun save(bitmap: Bitmap, target: File): Boolean = try {
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".tmp")
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        temp.renameTo(target)
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't save a thumbnail", e)
        false
    }

    // mpv reads a content:// file through its descriptor (closed by mpv when done).
    private fun urlFor(uri: Uri): String? = when (uri.scheme) {
        "content" -> try {
            context.contentResolver.openFileDescriptor(uri, "r")?.detachFd()?.let { "fdclose://$it" }
        } catch (e: Exception) {
            null
        }
        "file" -> uri.path
        else -> uri.toString()
    }

    fun release() {
        mpv.detachSurface()
        mpv.destroy()
        reader.close()
        readerThread.quitSafely()
    }

    private companion object {
        const val TAG = "ThumbnailService"
        const val WIDTH = 480
        const val HEIGHT = 270
        const val GRAB_TIMEOUT_MS = 20_000L
    }
}
