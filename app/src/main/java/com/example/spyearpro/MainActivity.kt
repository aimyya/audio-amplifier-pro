package com.example.spyearpro

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : Activity() {
    private val sampleRate = 16000
    private val blockSize = 512
    @Volatile private var running = false
    @Volatile private var gain = 2.0
    @Volatile private var waveletEnabled = true
    @Volatile private var noiseGateEnabled = true
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var suppressor: NoiseSuppressor? = null
    private lateinit var status: TextView
    private lateinit var meter: SpectrumView
    private lateinit var startButton: Button
    private lateinit var waveletCheck: CheckBox
    private lateinit var gateCheck: CheckBox
    private var previousInput = 0.0
    private var previousOutput = 0.0
    private var noiseFloor = 0.006

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 20)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "AUDIO AMPLIFIER PRO"
            textSize = 23f
            setTextColor(Color.rgb(20, 35, 55))
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Stopped • Phone microphone"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 14)
        }
        meter = SpectrumView(this)
        val gainLabel = TextView(this).apply { text = "Amplification: 2.0×"; textSize = 16f }
        val gainSlider = SeekBar(this).apply {
            max = 75
            progress = 15
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    gain = 0.5 + progress * 0.1
                    gainLabel.text = "Amplification: %.1f×".format(gain)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        waveletCheck = CheckBox(this).apply {
            text = "Wavelet denoising (Haar)"
            isChecked = true
            setOnCheckedChangeListener { _, checked -> waveletEnabled = checked }
        }
        gateCheck = CheckBox(this).apply {
            text = "Adaptive noise gate"
            isChecked = true
            setOnCheckedChangeListener { _, checked -> noiseGateEnabled = checked }
        }
        val note = TextView(this).apply {
            text = "Use only for sounds you are permitted to hear. Keep this screen open; monitoring stops when you press Stop."
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, 10, 0, 10)
        }
        startButton = Button(this).apply {
            text = "START LISTENING"
            setOnClickListener {
                if (running) stopAudio() else {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startAudio()
                    else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                }
            }
        }
        layout.addView(title)
        layout.addView(status)
        layout.addView(meter, LinearLayout.LayoutParams(-1, 150))
        layout.addView(gainLabel)
        layout.addView(gainSlider)
        layout.addView(waveletCheck)
        layout.addView(gateCheck)
        layout.addView(note)
        layout.addView(startButton, LinearLayout.LayoutParams(-1, -2))
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startAudio()
        else status.text = "Microphone permission denied"
    }

    private fun startAudio() {
        if (running) return
        val minIn = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val minOut = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minIn <= 0 || minOut <= 0) {
            status.text = "This device does not support the selected audio mode"
            return
        }
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minIn, blockSize * 4)
            )
            player = AudioTrack.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minOut, blockSize * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (NoiseSuppressor.isAvailable()) {
                suppressor = NoiseSuppressor.create(recorder!!.audioSessionId)
                suppressor?.enabled = true
            }
            recorder!!.startRecording()
            player!!.play()
            running = true
            startButton.text = "STOP LISTENING"
            status.text = "LIVE • Phone microphone • Keep headphones connected"
            thread(name = "AudioMonitor") { audioLoop() }
        } catch (e: Exception) {
            status.text = "Could not start audio: ${e.message ?: "device error"}"
            stopAudio()
        }
    }

    private fun audioLoop() {
        val input = ShortArray(blockSize)
        while (running) {
            val count = recorder?.read(input, 0, input.size) ?: -1
            if (count <= 0) continue
            val samples = DoubleArray(count) { input[it] / 32768.0 }
            if (waveletEnabled && count == blockSize) haarDenoise(samples)
            val output = ShortArray(count)
            var sum = 0.0
            for (i in 0 until count) {
                // Gentle high-pass/DC removal
                val x = samples[i]
                val hp = 0.96 * (previousOutput + x - previousInput)
                previousInput = x
                previousOutput = hp
                val absLevel = abs(hp)
                noiseFloor = if (absLevel < noiseFloor * 2.5) noiseFloor * 0.995 + absLevel * 0.005 else noiseFloor * 0.999 + absLevel * 0.001
                var y = hp * gain
                if (noiseGateEnabled && absLevel < max(0.002, noiseFloor * 1.35)) y *= 0.25
                y = y.coerceIn(-0.98, 0.98)
                output[i] = (y * 32767).roundToInt().toShort()
                sum += y * y
            }
            player?.write(output, 0, count)
            val rms = sqrt(sum / max(1, count))
            runOnUiThread { meter.setLevel(rms) }
        }
    }

    // Haar wavelet soft-threshold denoising on a power-of-two audio block.
    private fun haarDenoise(data: DoubleArray) {
        val n = data.size
        var length = n
        val work = data.copyOf()
        val temp = DoubleArray(n)
        val details = ArrayList<DoubleArray>()
        while (length >= 2) {
            val half = length / 2
            val detail = DoubleArray(half)
            for (i in 0 until half) {
                val a = work[2 * i]
                val b = work[2 * i + 1]
                temp[i] = (a + b) / sqrt(2.0)
                detail[i] = (a - b) / sqrt(2.0)
            }
            for (i in 0 until half) work[i] = temp[i]
            details.add(detail)
            length = half
        }
        val finest = details.firstOrNull() ?: return
        val sorted = finest.map { abs(it) }.sorted()
        val median = sorted[sorted.size / 2]
        val sigma = median / 0.6745
        val threshold = sigma * sqrt(2.0 * ln(n.toDouble()))
        for (level in details.indices) {
            val d = details[level]
            for (i in d.indices) {
                val v = d[i]
                d[i] = sign(v) * max(0.0, abs(v) - threshold)
            }
        }
        var currentLength = 1
        for (level in details.indices.reversed()) {
            val d = details[level]
            val next = DoubleArray(currentLength * 2)
            for (i in 0 until currentLength) {
                val approx = work[i]
                val detail = d[i]
                next[2 * i] = (approx + detail) / sqrt(2.0)
                next[2 * i + 1] = (approx - detail) / sqrt(2.0)
            }
            for (i in next.indices) work[i] = next[i]
            currentLength *= 2
        }
        for (i in data.indices) data[i] = work[i]
    }

    private fun stopAudio() {
        running = false
        try { recorder?.stop() } catch (_: Exception) {}
        try { player?.pause(); player?.flush(); player?.stop() } catch (_: Exception) {}
        recorder?.release()
        player?.release()
        suppressor?.release()
        recorder = null
        player = null
        suppressor = null
        if (::startButton.isInitialized) startButton.text = "START LISTENING"
        if (::status.isInitialized) status.text = "Stopped • Phone microphone"
    }

    override fun onStop() {
        super.onStop()
        stopAudio()
    }

    override fun onDestroy() {
        stopAudio()
        super.onDestroy()
    }
}

class SpectrumView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile private var level = 0.0
    fun setLevel(value: Double) {
        level = value.coerceIn(0.0, 1.0)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(12, 24, 42))
        val bars = 32
        val gap = 5f
        val barWidth = (width - (bars + 1) * gap) / bars
        val scaled = (level * 12).coerceIn(0.02, 1.0)
        for (i in 0 until bars) {
            val shape = (0.25 + 0.75 * abs(sin(i * 0.41 + level * 20))) * scaled
            val h = (height * shape).toFloat()
            paint.color = Color.rgb(40, 190, 170)
            val left = gap + i * (barWidth + gap)
            canvas.drawRoundRect(left, height - h, left + barWidth, height.toFloat() - 4, 5f, 5f, paint)
        }
        paint.color = Color.WHITE
        paint.textSize = 24f
        canvas.drawText("LIVE INPUT LEVEL", 16f, 30f, paint)
    }
}
