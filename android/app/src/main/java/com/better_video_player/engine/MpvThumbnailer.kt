package com.better_video_player.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.better_video_player.data.Video
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

// Thumbnails for the videos Android can't make one for (formats its decoders don't handle:
// old AVI codecs, WMV...), made by mpv in ThumbnailService's own process, as the desktop app
// makes them in worker processes. A file that crashes the decoder, or hangs it (the process is
// ended after 30 seconds), is remembered as unreadable and the next video gets a fresh process.
// The process is ended too after 30 seconds with nothing to do, so its memory goes.
//
// Kept in files/thumbnails, named by a hash of the video's place, size and modified time, so
// they're made once. Also notes each video's duration, which MediaStore doesn't know for these
// formats either.
class MpvThumbnailer(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val dir = File(context.filesDir, "thumbnails").apply { mkdirs() }
    private val metaFile = File(context.filesDir, "mpv-metadata.json")
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    // By cache key: durations in ms, and the files mpv couldn't read.
    private val _durations = MutableStateFlow<Map<String, Long>>(emptyMap())
    val durations: StateFlow<Map<String, Long>> = _durations
    private val failed = HashSet<String>()

    // The service, while bound (main thread only).
    private var service: Messenger? = null
    private var connection: ServiceConnection? = null
    private var servicePid = 0
    private var connecting: CompletableDeferred<Messenger?>? = null
    // The answer to the request in progress: null when the process died.
    private var pending: CompletableDeferred<Pair<Boolean, Long>?>? = null
    private var idleJob: Job? = null

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            ThumbnailService.HELLO -> {
                servicePid = msg.arg1
                connecting?.complete(service)
            }
            ThumbnailService.DONE -> pending?.complete(
                msg.data.getBoolean(ThumbnailService.KEY_OK) to msg.data.getLong(ThumbnailService.KEY_DURATION)
            )
        }
        true
    })

    init {
        try {
            if (metaFile.exists()) {
                val json = JSONObject(metaFile.readText())
                val found = HashMap<String, Long>()
                for (key in json.keys()) {
                    val o = json.getJSONObject(key)
                    if (o.optBoolean("failed")) failed += key
                    o.optLong("duration", 0).takeIf { it > 0 }?.let { found[key] = it }
                }
                _durations.value = found
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read ${metaFile.path}", e)
        }
    }

    fun keyOf(video: Video): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("${video.key}|${video.size}|${video.modifiedMs}".toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    fun hasFailed(video: Video) = synchronized(failed) { keyOf(video) in failed }

    // The thumbnail file, made now if needed; null if mpv couldn't read the video either.
    suspend fun thumbnail(video: Video): File? {
        val key = keyOf(video)
        val target = File(dir, "$key.jpg")
        if (target.exists()) return target
        if (hasFailed(video)) return null
        return mutex.withLock {
            if (target.exists()) target
            else withContext(Dispatchers.Main) { request(video, key, target) }
        }
    }

    private suspend fun request(video: Video, key: String, target: File): File? {
        idleJob?.cancel()
        val result = connect()?.let { svc ->
            val answer = CompletableDeferred<Pair<Boolean, Long>?>()
            pending = answer
            val msg = Message.obtain(null, ThumbnailService.GRAB).apply {
                replyTo = replies
                data = Bundle().apply {
                    putString(ThumbnailService.KEY_URI, video.uri.toString())
                    putString(ThumbnailService.KEY_TARGET, target.path)
                }
            }
            try {
                svc.send(msg)
                withTimeoutOrNull(GRAB_TIMEOUT_MS) { answer.await() }.also {
                    // Hung on this file (mpv's own 20 s limit didn't help): end the process.
                    if (it == null && !answer.isCompleted) {
                        Log.w(TAG, "Thumbnail process hung on ${video.fileName}; ending it")
                        endProcess()
                    }
                }
            } catch (_: RemoteException) {
                null
            } finally {
                pending = null
            }
        }
        val (ok, duration) = result ?: (false to 0L)
        if (result == null) Log.w(TAG, "Thumbnail process gave out on ${video.fileName}")
        if (!ok) synchronized(failed) { failed += key }
        if (duration > 0) _durations.update { it + (key to duration) }
        scope.launch(Dispatchers.IO) { saveMeta() }
        scheduleRelease()
        return target.takeIf { ok && it.exists() }
    }

    // Starts the service's process if it isn't running.
    private suspend fun connect(): Messenger? {
        service?.let { return it }
        val ready = CompletableDeferred<Messenger?>()
        connecting = ready
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val messenger = Messenger(binder)
                service = messenger
                try {
                    binder.linkToDeath({ main.post { onProcessDied() } }, 0)
                    messenger.send(Message.obtain(null, ThumbnailService.HELLO).apply { replyTo = replies })
                } catch (_: RemoteException) {
                    onProcessDied()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = onProcessDied()
            override fun onBindingDied(name: ComponentName) = onProcessDied()
            override fun onNullBinding(name: ComponentName) {
                connecting?.complete(null)
            }
        }
        connection = conn
        if (!context.bindService(Intent(context, ThumbnailService::class.java), conn, Context.BIND_AUTO_CREATE)) {
            connection = null
            return null
        }
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() }.also {
            connecting = null
            if (it == null) disconnect()
        }
    }

    // The process crashed (or was ended): the request in progress failed; the next one starts
    // a new process.
    private fun onProcessDied() {
        if (service == null && connection == null) return
        disconnect()
        connecting?.complete(null)
        pending?.complete(null)
    }

    private fun disconnect() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
        servicePid = 0
    }

    private fun endProcess() {
        val pid = servicePid
        disconnect()
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        pending?.complete(null)
    }

    // Nothing more to do for a while: end the process, so its memory goes.
    private fun scheduleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch(Dispatchers.Main) {
            delay(IDLE_RELEASE_MS)
            mutex.withLock { if (service != null) endProcess() }
        }
    }

    fun release() {
        main.post { if (service != null) endProcess() }
    }

    @Synchronized
    private fun saveMeta() {
        val json = JSONObject()
        val durations = _durations.value
        val failedNow = synchronized(failed) { failed.toSet() }
        for (key in durations.keys + failedNow) {
            json.put(key, JSONObject().apply {
                durations[key]?.let { put("duration", it) }
                if (key in failedNow) put("failed", true)
            })
        }
        val temp = File(metaFile.path + ".tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(metaFile)) {
            metaFile.delete()
            temp.renameTo(metaFile)
        }
    }

    private companion object {
        const val TAG = "MpvThumbnailer"
        // Longer than the service's own 20 s limit, for a decoder hung past it.
        const val GRAB_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val IDLE_RELEASE_MS = 30_000L
    }
}
