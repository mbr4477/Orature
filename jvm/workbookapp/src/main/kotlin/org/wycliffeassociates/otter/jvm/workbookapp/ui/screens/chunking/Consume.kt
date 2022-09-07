/**
 * Copyright (C) 2020-2022 Wycliffe Associates
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
package org.wycliffeassociates.otter.jvm.workbookapp.ui.screens.chunking

import com.github.thomasnield.rxkotlinfx.observeOnFx
import com.sun.glass.ui.Screen
import com.sun.javafx.util.Utils
import io.reactivex.rxkotlin.addTo
import java.text.MessageFormat
import javafx.beans.binding.Bindings
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign.MaterialDesign
import org.slf4j.LoggerFactory
import org.wycliffeassociates.otter.jvm.controls.model.SECONDS_ON_SCREEN
import org.wycliffeassociates.otter.jvm.controls.styles.tryImportStylesheet
import org.wycliffeassociates.otter.jvm.controls.waveform.ScrollingWaveform
import org.wycliffeassociates.otter.jvm.workbookapp.ui.viewmodel.ChunkingViewModel
import org.wycliffeassociates.otter.jvm.workbookapp.ui.viewmodel.ChunkingWizardPage
import org.wycliffeassociates.otter.jvm.workbookapp.ui.viewmodel.SettingsViewModel
import tornadofx.*

class Consume : Fragment() {
    private val logger = LoggerFactory.getLogger(Consume::class.java)

    val playIcon = FontIcon(MaterialDesign.MDI_PLAY)
    val pauseIcon = FontIcon(MaterialDesign.MDI_PAUSE)

    val vm: ChunkingViewModel by inject()
    val settingsViewModel: SettingsViewModel by inject()

    override fun onDock() {
        super.onDock()
        logger.info("Consume docked")
        tryImportStylesheet(resources.get("/css/scrolling-waveform.css"))
        tryImportStylesheet(resources.get("/css/consume-page.css"))

        vm.subscribeOnWaveformImages = ::subscribeOnWaveformImages
        vm.onDockConsume()
    }

    override fun onUndock() {
        super.onUndock()
        vm.onUndockConsume()
    }

    private fun subscribeOnWaveformImages() {
        vm.waveform
            .observeOnFx()
            .subscribe {
                (root.center as ScrollingWaveform).addWaveformImage(it)
            }
            .addTo(vm.compositeDisposable)
    }

    override val root = borderpane {
        center = ScrollingWaveform().apply {
            addClass("consume__scrolling-waveform")

            themeProperty.bind(settingsViewModel.appColorMode)
            positionProperty.bind(vm.positionProperty)

            setOnWaveformClicked { vm.pause() }
            setOnWaveformDragReleased { deltaPos ->
                val deltaFrames = pixelsToFrames(deltaPos)
                val curFrames = vm.getLocationInFrames()
                val duration = vm.getDurationInFrames()
                val final = Utils.clamp(0, curFrames - deltaFrames, duration)
                vm.seek(final)
            }

            setOnToggleMedia(vm::mediaToggle)
            setOnRewind(vm::rewind)
            setOnFastForward(vm::fastForward)

            vm.consumeImageCleanup = ::freeImages
        }
        bottom = hbox {
            styleClass.addAll("consume__bottom")
            button {
                graphicProperty().bind(
                    Bindings.createObjectBinding(
                        {
                            when (vm.isPlayingProperty.value) {
                                true -> pauseIcon
                                false -> playIcon
                            }
                        },
                        vm.isPlayingProperty
                    )
                )
                styleClass.addAll("btn", "btn--cta", "consume__btn")
                action {
                    vm.mediaToggle()
                }
            }
        }
    }
}

fun pixelsToFrames(pixels: Double): Int {
    val framesOnScreen = SECONDS_ON_SCREEN * 44100
    val framesInPixel = framesOnScreen / Screen.getMainScreen().platformWidth
    return (pixels * framesInPixel).toInt()
}
