package com.msp1974.vacompanion.players

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.msp1974.vacompanion.device.DeviceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
@UnstableApi
class AlarmService : Service() {

    @Inject
    lateinit var deviceManager: DeviceManager

    private val config get() = deviceManager.config

    private lateinit var audioManager: AudioManager
    private var mediaPlayer: ExoPlayer? = null
    private var focusRequest: AudioFocusRequestCompat? = null
    private var hasAudioFocus = false
    private var fadeJob: Job? = null
    private var fadeVolume = 1f
    private var fadeStartVolume = 1f
    private var fadeDurationMs = 0L
    private var fadeElapsedMs = 0L
    private var fadeLastUpdateMs = 0L
    private var audioFocusVolumeMultiplier = 1f
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    companion object {
        private const val FADE_UPDATE_INTERVAL_MS = 250L

        var sInstance: AlarmService? = null
    }

    override fun onCreate() {
        super.onCreate()
        sInstance = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mediaPlayer == null) {
            mediaPlayer = createPlayer()
        }

        val uri = intent?.getStringExtra("uri") ?: ""
        play(uri.toUri())
        return START_NOT_STICKY
    }

    private fun createPlayer(): ExoPlayer {
        val player = ExoPlayer.Builder(this).build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_ALARM)
            .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
            .build()
        player.setAudioAttributes(audioAttributes, false)
        return player
    }

    fun play(uri: Uri) {
        Timber.i("Alarm started: $uri")
        val player = mediaPlayer ?: return

        try {
            val mediaUri = if (uri.toString().isNotBlank()) {
                uri
            } else {
                "asset:///alarm/default.mp3".toUri()
            }
            cancelFadeIn()
            player.setMediaItem(MediaItem.fromUri(mediaUri))
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.prepare()
            startFadeIn()
            requestAudioFocus()
            player.play()
        } catch (e: Exception) {
            Timber.e("Error playing alarm: $e")
        }
    }

    fun pause() {
        pauseFadeIn()
        mediaPlayer?.pause()
    }

    fun resume() {
        audioFocusVolumeMultiplier = 1f
        resumeFadeIn()
        applyPlayerVolume()

        mediaPlayer?.let { player ->
            if (!player.isPlaying && requestAudioFocus()) {
                player.play()
            }
        }
    }

    fun stop() {
        cancelFadeIn()
        mediaPlayer?.let { player ->
            try {
                player.stop()
                player.release()
            } catch (e: Exception) {
                Timber.e("Error stopping/releasing player: $e")
            } finally {
                mediaPlayer = null
            }
        }
    }

    private fun startFadeIn() {
        val durationMinutes = config.alarmFadeDurationMinutes.coerceIn(0, 30)
        val startVolume = config.alarmFadeStartVolumePercent.coerceIn(0, 100) / 100f

        if (durationMinutes == 0 || startVolume >= 1f) {
            fadeVolume = 1f
            applyPlayerVolume()
            return
        }

        fadeStartVolume = startVolume
        fadeDurationMs = durationMinutes.toLong() * 60_000L
        fadeElapsedMs = 0L
        fadeVolume = startVolume
        applyPlayerVolume()
        launchFadeIn()
        Timber.i(
            "Alarm fading from %d%% to full volume over %d minute(s)",
            config.alarmFadeStartVolumePercent,
            durationMinutes,
        )
    }

    private fun launchFadeIn() {
        if (fadeDurationMs <= 0L || fadeElapsedMs >= fadeDurationMs) return

        fadeLastUpdateMs = SystemClock.elapsedRealtime()
        fadeJob = serviceScope.launch {
            while (isActive) {
                updateFadeProgress()
                if (fadeElapsedMs >= fadeDurationMs) break
                delay(FADE_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun updateFadeProgress() {
        val nowMs = SystemClock.elapsedRealtime()
        val elapsedSinceUpdateMs = (nowMs - fadeLastUpdateMs).coerceAtLeast(0L)
        fadeLastUpdateMs = nowMs
        fadeElapsedMs = (fadeElapsedMs + elapsedSinceUpdateMs).coerceAtMost(fadeDurationMs)
        val progress = fadeElapsedMs.toFloat() / fadeDurationMs
        fadeVolume = fadeStartVolume + ((1f - fadeStartVolume) * progress)
        applyPlayerVolume()
    }

    private fun pauseFadeIn() {
        if (fadeJob?.isActive == true) {
            updateFadeProgress()
            fadeJob?.cancel()
            fadeJob = null
        }
    }

    private fun resumeFadeIn() {
        if (fadeJob?.isActive != true) {
            launchFadeIn()
        }
    }

    private fun cancelFadeIn() {
        fadeJob?.cancel()
        fadeJob = null
        fadeDurationMs = 0L
        fadeElapsedMs = 0L
    }

    private fun applyPlayerVolume() {
        mediaPlayer?.volume = (fadeVolume * audioFocusVolumeMultiplier).coerceIn(0f, 1f)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        val audioAttributes = mediaPlayer?.audioAttributes ?: return false
        focusRequest = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { focusChange ->
                Timber.d("Alarm focus change: $focusChange")
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        hasAudioFocus = true
                        resume()
                    }

                    AudioManager.AUDIOFOCUS_LOSS -> {
                        hasAudioFocus = false
                        stopSelf()
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        hasAudioFocus = false
                        pause()
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        audioFocusVolumeMultiplier = 0.2f
                        applyPlayerVolume()
                    }
                }
            }
            .build()

        val result = AudioManagerCompat.requestAudioFocus(audioManager, focusRequest!!)

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Timber.d("Alarm requestAudioFocus: $result")
        return hasAudioFocus
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun abandonAudioFocus() {
        focusRequest?.let { AudioManagerCompat.abandonAudioFocusRequest(audioManager, it) }
        focusRequest = null
        hasAudioFocus = false
        Timber.d("Alarm abandonAudioFocus")
    }

    override fun onDestroy() {
        stop()
        serviceScope.cancel()
        abandonAudioFocus()
        sInstance = null
        super.onDestroy()
        Timber.i("Alarm player stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
