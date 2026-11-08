package com.stb.mastercontrol.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming mic live audio over the local network.
 *
 * Runs as a foreground service (type = microphone) so it survives the app
 * going to the background. Captures 16 kHz / mono / 16-bit PCM from the
 * phone's mic with AudioRecord and pushes every chunk as a UDP datagram to
 * the configured PC (`targetIp:5005`). A Python listener on the PC plays the
 * stream live through its speakers.
 */
class MicStreamService : Service() {

    companion object {
        const val ACTION_START = "com.stb.mastercontrol.mic.START"
        const val ACTION_STOP = "com.stb.mastercontrol.mic.STOP"

        const val EXTRA_TARGET_IP = "target_ip"
        const val EXTRA_TARGET_PORT = "target_port"
        /** Empty = stream until explicitly stopped; > 0 = quick burst capture. */
        const val EXTRA_BURST_MS = "burst_ms"

        const val NOTIFY_ID = 222
        const val CHANNEL_ID = "mic_stream"
        const val DEFAULT_PORT = 5005
        const val SAMPLE_RATE = 16000
        /** Bytes per read: 1280 = 640 samples = 40 ms of 16 kHz mono 16-bit PCM. */
        const val CHUNK_BYTES = 1280
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streaming = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var socket: DatagramSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP, null -> {
                MicStreamBus.setStreaming(false)
                stopSelf()
            }
            ACTION_START -> {
                if (!micPermissionGranted()) {
                    MicStreamBus.setStreaming(false)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val ip = intent.getStringExtra(EXTRA_TARGET_IP).orEmpty()
                val port = intent.getIntExtra(EXTRA_TARGET_PORT, DEFAULT_PORT)
                val burstMs = intent.getLongExtra(EXTRA_BURST_MS, 0L)
                val label = if (ip.contains(":")) ip else "$ip:$port"
                MicStreamBus.setStreaming(true, label, burst = burstMs > 0)
                startForegroundTyped("Mic streaming to $label")
                if (streaming.compareAndSet(false, true)) {
                    streamLoop(ip, port, burstMs)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun micPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startForegroundTyped(label: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Mic streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(channel)

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Mic live")
            .setContentText(label)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        val micType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        ServiceCompat.startForeground(this, NOTIFY_ID, notif, micType)
    }

    private fun streamLoop(targetIp: String, targetPort: Int, burstMs: Long) {
        scope.launch {
            // Lock the stream config upfront; bail cleanly on any failure.
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                MicStreamBus.setStreaming(false)
                stopSelf()
                return@launch
            }
            val rec = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4
                )
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                MicStreamBus.setStreaming(false)
                stopSelf()
                return@launch
            }
            recorder = rec
            val sock = runCatching { DatagramSocket() }.getOrNull()
            if (sock == null) {
                MicStreamBus.setStreaming(false)
                stopSelf()
                return@launch
            }
            socket = sock
            val target = InetSocketAddress(targetIp, targetPort)
            val buffer = ByteArray(CHUNK_BYTES)

            runCatching {
                rec.startRecording()
                if (burstMs > 0) {
                    // Short burst: stream for the window then stop.
                    val deadline = System.currentTimeMillis() + burstMs
                    while (streaming.get() && System.currentTimeMillis() < deadline) {
                        pump(rec, sock, target, buffer)
                    }
                } else {
                    while (streaming.get()) {
                        pump(rec, sock, target, buffer)
                    }
                }
            }
            cleanupAudio()
            MicStreamBus.setStreaming(false)
            if (burstMs > 0) stopSelf()
        }
    }

    /** Records one CHUNK_BYTES block and fires it at the PC over UDP. */
    private fun pump(rec: AudioRecord, sock: DatagramSocket, target: InetSocketAddress, buffer: ByteArray) {
        val read = rec.read(buffer, 0, buffer.size)
        if (read > 0) {
            runCatching {
                sock.send(DatagramPacket(buffer, read, target))
            }
        }
    }

    private fun cleanupAudio() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { socket?.close() }
        socket = null
        streaming.set(false)
    }

    override fun onDestroy() {
        streaming.set(false)
        cleanupAudio()
        MicStreamBus.setStreaming(false)
        super.onDestroy()
    }
}
