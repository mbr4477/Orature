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
package org.wycliffeassociates.otter.jvm.controls.waveform

import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.geometry.NodeOrientation
import javafx.scene.image.Image
import javafx.scene.layout.StackPane
import javafx.scene.shape.Line
import org.wycliffeassociates.otter.common.data.ColorTheme
import org.wycliffeassociates.otter.jvm.controls.UIVersion
import org.wycliffeassociates.otter.jvm.controls.controllers.ScrollSpeed
import org.wycliffeassociates.otter.jvm.controls.marker.MarkersContainer
import org.wycliffeassociates.otter.common.domain.model.ChunkMarkerModel
import tornadofx.*

class MarkerWaveform : StackPane() {

    val themeProperty = SimpleObjectProperty(ColorTheme.LIGHT)

    val markers = observableListOf<ChunkMarkerModel>()
    val positionProperty = SimpleDoubleProperty(0.0)
    val canMoveMarkerProperty = SimpleBooleanProperty(true)
    val canDeleteMarkerProperty = SimpleBooleanProperty(true)

    private val onPositionChanged = SimpleObjectProperty<(Int, Double) -> Unit> { _, _ -> }
    fun setOnPositionChanged(op: (Int, Double) -> Unit) {
        onPositionChanged.set(op)
    }

    private val onSeekPrevious = SimpleObjectProperty<() -> Unit> {}
    fun setOnSeekPrevious(op: () -> Unit) {
        onSeekPrevious.set(op)
    }

    private val onSeekNext = SimpleObjectProperty<() -> Unit> {}
    fun setOnSeekNext(op: () -> Unit) {
        onSeekNext.set(op)
    }

    private val onSeek = SimpleObjectProperty<(Int) -> Unit>()
    fun setOnSeek(op: (Int) -> Unit) {
        onSeek.set(op)
    }

    private val onPlaceMarker = SimpleObjectProperty<EventHandler<ActionEvent>>()
    fun setOnPlaceMarker(op: () -> Unit) {
        onPlaceMarker.set(EventHandler { op.invoke() })
    }

    private val onLocationRequest = SimpleObjectProperty<() -> Int> { 0 }
    fun setOnLocationRequest(op: () -> Int) {
        onLocationRequest.set(op)
    }

    val onWaveformClicked = SimpleObjectProperty<EventHandler<ActionEvent>>()
    fun setOnWaveformClicked(op: () -> Unit) {
        onWaveformClicked.set((EventHandler { op.invoke() }))
    }

    val onWaveformDragReleased = SimpleObjectProperty<(Double) -> Unit> {}
    fun setOnWaveformDragReleased(op: (Double) -> Unit) {
        onWaveformDragReleased.set(op)
    }

    var onRewind = SimpleObjectProperty<((ScrollSpeed) -> Unit)> {}
    fun setOnRewind(op: (ScrollSpeed) -> Unit){
        onRewind.set(op)
    }

    var onFastForward = SimpleObjectProperty<((ScrollSpeed) -> Unit)> {}
    fun setOnFastForward(op: (ScrollSpeed) -> Unit){
        onFastForward.set(op)
    }

    var onToggleMedia = SimpleObjectProperty<(() -> Unit)> {}
    fun setOnToggleMedia(op: () -> Unit){
        onToggleMedia.set(op)
    }

    var onResumeMedia = SimpleObjectProperty<(() -> Unit)> {}
    fun setOnResumeMedia(op: () -> Unit){
        onResumeMedia.set(op)
    }

    private val waveformFrame: WaveformFrame

    fun freeImages() {
        waveformFrame.freeImages()
    }

    fun addWaveformImage(image: Image) {
        waveformFrame.addImage(image)
    }

    private lateinit var top: MarkersContainer

    fun refreshMarkers() {
        top.refreshMarkers()
    }

    init {
        addClass("marker-waveform")
        minHeight = 120.0

        nodeOrientation = NodeOrientation.LEFT_TO_RIGHT

        vbox {
            addClass("scrolling-waveform-frame__center")
        }

        val topTrack = MarkersContainer().apply {
            top = this
            markers.bind(this@MarkerWaveform.markers, { it })
            canMoveMarkerProperty.bind(this@MarkerWaveform.canMoveMarkerProperty)
            canDeleteMarkerProperty.bind(this@MarkerWaveform.canDeleteMarkerProperty)
            onPositionChangedProperty.bind(onPositionChanged)
            onLocationRequestProperty.bind(onLocationRequest)
        }
        waveformFrame = WaveformFrame(topTrack).apply {
            themeProperty.bind(this@MarkerWaveform.themeProperty)
            framePositionProperty.bind(positionProperty)
            uiVersionProperty.set(UIVersion.THREE)
            onWaveformClickedProperty.bind(onWaveformClicked)
            onWaveformDragReleasedProperty.bind(onWaveformDragReleased)
            onRewindProperty.bind(onRewind)
            onFastForwardProperty.bind(onFastForward)
            onToggleMediaProperty.bind(onToggleMedia)
            onResumeMediaProperty.bind(onResumeMedia)
            onSeekPreviousProperty.bind(onSeekPrevious)
            onSeekNextProperty.bind(onSeekNext)
            onSeekProperty.bind(onSeek)

            focusVisibleProperty().onChange {
                this@MarkerWaveform.togglePseudoClass("active", it)
            }
        }
        add(waveformFrame)
        add(
            Line(0.0, 0.0, 0.0, 0.0).apply {
                isMouseTransparent = true
                isManaged = false
                startXProperty().bind(this@MarkerWaveform.widthProperty().divide(2))
                endXProperty().bind(this@MarkerWaveform.widthProperty().divide(2))
                endYProperty().bind(this@MarkerWaveform.heightProperty())
                styleClass.add("scrolling-waveform__playback-line")
            }
        )
    }

}