/**
 * Copyright (C) 2020-2024 Wycliffe Associates
 *
 * This file is part of Orature.
 *
 * Orature is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Orature is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.wycliffeassociates.otter.jvm.device.audio

import com.jakewharton.rxrelay2.PublishRelay
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import org.slf4j.LoggerFactory
import org.wycliffeassociates.otter.common.domain.audio.OratureAudioFile
import org.wycliffeassociates.otter.common.audio.AudioFileReader
import org.wycliffeassociates.otter.common.device.AudioFileReaderProvider
import org.wycliffeassociates.otter.common.device.AudioPlayerEvent
import org.wycliffeassociates.otter.common.device.IAudioPlayer
import org.wycliffeassociates.otter.common.device.IAudioPlayerListener
import org.wycliffeassociates.otter.common.domain.audio.OratureAudioFileReaderProvider

class AudioBufferPlayer(
    private val player: SourceDataLine?,
    private val errorRelay: PublishRelay<AudioError> = PublishRelay.create()
) : IAudioPlayer {

    private val logger = LoggerFactory.getLogger(this::class.java)

    private val monitor = Object()

    override val frameStart: Int
        get() = begin
    override val frameEnd: Int
        get() = end

    private var pause = AtomicBoolean(false)

    private var _startPos = 0
    private var startPosition: Int
        set(pos) {
            _startPos = pos
        }
        get() = _startPos

    private var reader: AudioFileReader? = null

    private lateinit var bytes: ByteArray
    private lateinit var playbackThread: Thread
    private var begin = 0
    private var end = 0

    val processor = AudioProcessor()

    private val listeners = mutableListOf<IAudioPlayerListener>()

    override fun addEventListener(listener: IAudioPlayerListener) {
        listeners.add(listener)
    }

    override fun addEventListener(onEvent: (event: AudioPlayerEvent) -> Unit) {
        listeners.add(
            object : IAudioPlayerListener {
                override fun onEvent(event: AudioPlayerEvent) {
                    onEvent(event)
                }
            }
        )
    }

    override fun load(reader: AudioFileReader) {
        this.reader?.let { close() }
        this.reader = reader.let{ _reader ->
            begin = 0
            end = _reader.totalFrames
            bytes = ByteArray(processor.inputBufferSize * 2)
            listeners.forEach { it.onEvent(AudioPlayerEvent.LOAD) }
            _reader.open()
            _reader
        }
        if (player == null) {
            errorRelay.accept(AudioError(AudioErrorType.PLAYBACK, LineUnavailableException()))
        }
    }

    override fun load(readerProvider: AudioFileReaderProvider) {
        reader?.let { close() }
        reader = readerProvider.getAudioFileReader().let { _reader ->
            begin = 0
            end = _reader.totalFrames
            bytes = ByteArray(processor.inputBufferSize * 2)
            listeners.forEach { it.onEvent(AudioPlayerEvent.LOAD) }
            _reader.open()
            _reader
        }
        if (player == null) {
            errorRelay.accept(AudioError(AudioErrorType.PLAYBACK, LineUnavailableException()))
        }
    }

    override fun load(file: File) {
        reader?.let { close() }
        reader = OratureAudioFile(file).reader().let { _reader ->
            begin = 0
            end = _reader.totalFrames
            bytes = ByteArray(processor.inputBufferSize * 2)
            listeners.forEach { it.onEvent(AudioPlayerEvent.LOAD) }
            _reader.open()
            _reader
        }
        if (player == null) {
            errorRelay.accept(AudioError(AudioErrorType.PLAYBACK, LineUnavailableException()))
        }
    }

    override fun loadSection(reader: AudioFileReader, frameStart: Int, frameEnd: Int) {
        this.reader?.let { close() }
        begin = frameStart
        end = frameEnd
        this.reader = reader.let { _reader ->
            bytes = ByteArray(processor.inputBufferSize * 2)
            listeners.forEach { it.onEvent(AudioPlayerEvent.LOAD) }
            _reader.open()
            _reader
        }
        if (player == null) {
            errorRelay.accept(AudioError(AudioErrorType.PLAYBACK, LineUnavailableException()))
        }
    }

    override fun loadSection(readerProvider: AudioFileReaderProvider, frameStart: Int, frameEnd: Int) {
        reader?.let { close() }
        begin = frameStart
        end = frameEnd
        reader = readerProvider.getAudioFileReader(frameStart, frameEnd).let { _reader ->
            bytes = ByteArray(processor.inputBufferSize * 2)
            listeners.forEach { it.onEvent(AudioPlayerEvent.LOAD) }
            _reader.open()
            _reader
        }
        if (player == null) {
            errorRelay.accept(AudioError(AudioErrorType.PLAYBACK, LineUnavailableException()))
        }
    }

    override fun loadSection(file: File, frameStart: Int, frameEnd: Int) {
        val readerProvider = OratureAudioFileReaderProvider(file)
        loadSection(readerProvider = readerProvider, frameStart, frameEnd)

        reader?.let { close() }
        begin = frameStart
        end = frameEnd
        reader = OratureAudioFile(file).reader(frameStart, frameEnd).let { _reader ->
            bytes = ByteArray(processor.inputBufferSize * 2)
            listeners.forEach { it.onEvent(AudioPlayerEvent.LOAD) }
            _reader.open()
            _reader
        }
        if (player == null) {
            errorRelay.accept(AudioError(AudioErrorType.PLAYBACK, LineUnavailableException()))
        }
    }

    override fun getAudioReader(): AudioFileReader? {
        return reader
    }

    override fun play() {
        reader?.let { _reader ->
            player?.let {
                if (!player.isActive) {
                    listeners.forEach { it.onEvent(AudioPlayerEvent.PLAY) }
                    pause.set(false)
                    if (!_reader.hasRemaining()) _reader.seek(0)
                    startPosition = _reader.framePosition
                    playbackThread = Thread {
                        try {
                            player.open()
                            player.start()
                            while (_reader.hasRemaining() && !pause.get() && !playbackThread.isInterrupted) {
                                synchronized(monitor) {
                                    if (_reader.supportsTimeShifting()) {
                                        if (_reader.framePosition > bytes.size / 2) {
                                            _reader.seek(_reader.framePosition - processor.overlap)
                                        }
                                        _reader.getPcmBuffer(bytes)
                                        val output = processor.process(bytes)
                                        player.write(output, 0, output.size)
                                    } else {
                                        _reader.getPcmBuffer(bytes)
                                        player.write(bytes, 0, bytes.size)
                                    }
                                }
                            }
                            player.drain()
                            if (!pause.get()) {
                                startPosition = _reader.totalFrames
                                listeners.forEach { it.onEvent(AudioPlayerEvent.COMPLETE) }
                                player.close()
                                seek(startPosition)
                            }
                        } catch (e: LineUnavailableException) {
                            errorRelay.accept(AudioError(AudioErrorType.PLAYBACK, e))
                        } catch (e: IllegalArgumentException) {
                            errorRelay.accept(AudioError(AudioErrorType.PLAYBACK, e))
                        }
                    }
                    playbackThread.start()
                }
            } ?: errorRelay.accept(AudioError(AudioErrorType.PLAYBACK, LineUnavailableException()))
        }
    }

    override fun pause() {
        reader?.let { _reader ->
            val stoppedAt = getLocationInFrames()
            startPosition = stoppedAt
            pause.set(true)
            player?.let {
                player.stop()
                player.flush()
                player.close()
            }
            listeners.forEach { it.onEvent(AudioPlayerEvent.PAUSE) }
            _reader.seek(stoppedAt)
        }
    }

    override fun toggle() {
        if (isPlaying()) pause() else play()
    }

    override fun stop() {
        pause()
        seek(0)
        listeners.forEach { it.onEvent(AudioPlayerEvent.STOP) }
    }

    override fun close() {
    }

    override fun release() {
        stop()
        if (reader != null) {
            reader?.release()
            reader = null
        }
    }

    override fun changeRate(rate: Double) {
        synchronized(monitor) {
            val resume = player?.isActive ?: false
            pause()
            processor.updatePlaybackRate(rate)
            if (resume) {
                play()
            }
            bytes = ByteArray(processor.inputBufferSize * 2)
        }
    }

    override fun seek(position: Int) {
        val resume = player?.isActive ?: false
        player?.stop()
        if (::playbackThread.isInitialized) {
            playbackThread.interrupt()
        }
        player?.flush()
        startPosition = position
        reader?.seek(position)
        if (resume) {
            play()
        }
    }

    override fun isPlaying(): Boolean {
        return player?.isRunning ?: false
    }

    override fun getDurationInFrames(): Int {
        return end - begin
    }

    override fun getDurationMs(): Int {
        return ((end - begin) / 44.1).toInt()
    }

    override fun getLocationInFrames(): Int {
        return (startPosition + ((player?.framePosition ?: 0) * processor.playbackRate)).toInt()
    }

    override fun getLocationMs(): Int {
        return (getLocationInFrames() / 44.1).toInt()
    }
}
