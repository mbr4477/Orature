package org.wycliffeassociates.otter.jvm.workbookapp.ui.narration

import javafx.animation.AnimationTimer
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.collections.ObservableList
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.control.ScrollBar
import org.slf4j.LoggerFactory
import org.wycliffeassociates.otter.common.data.audio.VerseMarker
import org.wycliffeassociates.otter.jvm.controls.customizeScrollbarSkin
import org.wycliffeassociates.otter.jvm.controls.event.AppCloseRequestEvent
import org.wycliffeassociates.otter.jvm.controls.model.framesToPixels
import org.wycliffeassociates.otter.jvm.controls.model.pixelsToFrames
import org.wycliffeassociates.otter.jvm.controls.styles.tryImportStylesheet
import org.wycliffeassociates.otter.jvm.controls.waveform.Drawable
import org.wycliffeassociates.otter.jvm.workbookapp.ui.narration.markers.NarrationMarkerChangedEvent
import org.wycliffeassociates.otter.jvm.workbookapp.ui.narration.markers.VerseMarkerControl
import org.wycliffeassociates.otter.jvm.workbookapp.ui.narration.markers.verse_markers_layer
import org.wycliffeassociates.otter.jvm.workbookapp.ui.narration.waveform.WaveformLayer
import org.wycliffeassociates.otter.jvm.workbookapp.ui.narration.waveform.narration_waveform
import tornadofx.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AudioWorkspaceView : View() {
    private val userIsDraggingProperty = SimpleBooleanProperty(false)

    private val logger = LoggerFactory.getLogger(AudioWorkspaceView::class.java)

    private val viewModel: AudioWorkspaceViewModel by inject()

    private lateinit var narrationWaveformLayer: WaveformLayer

    val canvasInflatedProperty = SimpleBooleanProperty(false)

    private val drawables = mutableListOf<Drawable>()

    val jobQueue = LinkedBlockingQueue<Runnable>()
    val executor = ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, jobQueue)

    val finishedFrame = AtomicBoolean(true)

    val markerNodes = observableListOf<VerseMarkerControl>()

    val runnable = Runnable {
        if (finishedFrame.compareAndExchange(true, false)) {
            try {
                if (canvasInflatedProperty.value) {
                    viewModel.drawWaveform(
                        narrationWaveformLayer.getWaveformContext(),
                        narrationWaveformLayer.getWaveformCanvas(),
                        markerNodes
                    )
                    viewModel.drawVolumeBar(
                        narrationWaveformLayer.getVolumeBarContext(),
                        narrationWaveformLayer.getVolumeCanvas()
                    )
                }
                runLater { updateScrollPosition() }
            } catch (e: Exception) {
                logger.error("Exception in render loop", e)
            } finally {
                finishedFrame.set(true)
            }
        }
    }

    fun updateScrollPosition() {
        if (scrollBar.isDisabled) {
            val loc = viewModel.audioPositionProperty.value
            val percent = loc / scrollBar.width
            scrollBar.valueProperty().set(percent)
        }
    }

    val at = object : AnimationTimer() {
        override fun handle(now: Long) {
            executor.submit(runnable)
        }
    }

    val scrollBar = ScrollBar().apply {
        runLater {
            customizeScrollbarSkin()
        }

        disableWhen {
            viewModel.isRecordingProperty.or(viewModel.isPlayingProperty)
        }

        valueProperty().onChange {
            if (!isDisabled) {
                viewModel.seekPercent(it / width)
            }
        }

        maxProperty().bind(widthProperty())
    }

    init {
        tryImportStylesheet("/css/verse-markers-layer.css")
    }

    override val root = stackpane {
        subscribe<AppCloseRequestEvent> {
            at.stop()
            jobQueue.clear()
            executor.shutdownNow()
        }


        borderpane {
            center = stackpane {
                narration_waveform {
                    narrationWaveformLayer = this

                    canvasInflatedProperty.bind(
                        widthProperty()
                            .greaterThan(0)
                            .and(heightProperty().greaterThan(0))
                    )
                }
                verse_markers_layer {
                    verseMarkersControls.bind(markerNodes) { it }
                    setOnLayerScroll { delta ->
                        val pos = viewModel.audioPositionProperty.value
                        val seekTo = pos + delta
                        viewModel.seekTo(seekTo)
                    }
                }
            }
            bottom = scrollBar
        }
    }


    override fun onDock() {
        super.onDock()
        viewModel.onDock()
        markerNodes.bind(viewModel.recordedVerses) { marker ->
            VerseMarkerControl().apply {
                verseProperty.set(marker)
                verseIndexProperty.set(viewModel.recordedVerses.indexOf(marker))
                labelProperty.set(marker.label)
                isRecordingProperty.bind(viewModel.isRecordingProperty)
            }
        }

        at.start()
    }

    override fun onUndock() {
        super.onUndock()
        viewModel.onUndock()
        at.stop()
    }
}

class AudioWorkspaceViewModel : ViewModel() {
    private val logger = LoggerFactory.getLogger(AudioWorkspaceViewModel::class.java)

    private val narrationViewModel: NarrationViewModel by inject()

    val isRecordingProperty = SimpleBooleanProperty()
    val isPlayingProperty = SimpleBooleanProperty()
    var recordedVerses = observableListOf<VerseMarker>()

    val audioPositionProperty = SimpleIntegerProperty()
    val totalAudioSizeProperty = SimpleIntegerProperty()

    fun drawWaveform(context: GraphicsContext, canvas: Canvas, markerNodes: ObservableList<VerseMarkerControl>) {
        narrationViewModel.drawWaveform(context, canvas, markerNodes)
    }

    fun drawVolumeBar(context: GraphicsContext, canvas: Canvas) {
        narrationViewModel.drawVolumebar(context, canvas)
    }

    fun onDock() {
        isRecordingProperty.bind(narrationViewModel.isRecordingProperty)
        isPlayingProperty.bind(narrationViewModel.isPlayingProperty)
        totalAudioSizeProperty.bind(narrationViewModel.totalAudioSizeProperty)
        audioPositionProperty.bind(narrationViewModel.audioPositionProperty)
        recordedVerses.bind(narrationViewModel.recordedVerses) { it }

    }

    fun onUndock() {
        isRecordingProperty.unbind()
    }

    fun seekPercent(percent: Double) {
        narrationViewModel.seekPercent(percent)
    }

    fun seekTo(frame: Int) {
        narrationViewModel.seekTo(frame)
    }
}