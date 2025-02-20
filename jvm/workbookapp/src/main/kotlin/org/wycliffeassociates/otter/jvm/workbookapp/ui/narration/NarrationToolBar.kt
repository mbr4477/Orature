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
package org.wycliffeassociates.otter.jvm.workbookapp.ui.narration

import javafx.beans.binding.Bindings.not
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.ObservableList
import javafx.geometry.Pos
import javafx.scene.layout.Region
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign.MaterialDesign
import org.wycliffeassociates.otter.common.data.workbook.Chapter
import org.slf4j.LoggerFactory
import org.wycliffeassociates.otter.common.data.workbook.Take
import org.wycliffeassociates.otter.common.persistence.repositories.PluginType
import org.wycliffeassociates.otter.jvm.utils.onChangeAndDoNow
import org.wycliffeassociates.otter.jvm.workbookapp.ui.viewmodel.AudioPluginViewModel
import org.wycliffeassociates.otter.jvm.workbookapp.ui.viewmodel.WorkbookDataStore
import tornadofx.*
import java.text.MessageFormat

class NarrationToolBar : View() {
    private val viewModel by inject<NarrationViewModel>()


    override val root = hbox {
        addClass("narration-toolbar", "narration-toolbar__play-controls")
        alignment = Pos.CENTER_LEFT
        button {
            addClass("btn", "btn--secondary")
            addPseudoClass("active")
            tooltip { textProperty().bind(this@button.textProperty()) }


            disableWhen {
                viewModel.isRecordingProperty.or(viewModel.hasVersesProperty.not())
            }

            viewModel.isPlayingProperty.onChangeAndDoNow {
                it?.let { playing ->
                    runLater {
                        if (!playing) {
                            graphic = FontIcon(MaterialDesign.MDI_PLAY)
                            text = messages["playAll"]
                            togglePseudoClass("active", false)
                        } else {
                            graphic = FontIcon(MaterialDesign.MDI_PAUSE)
                            text = messages["pause"]
                            togglePseudoClass("active", true)
                        }
                    }
                }
            }

            setOnAction {
                if (viewModel.isPlayingProperty.value) {
                    viewModel.pausePlayback()
                } else {
                    viewModel.playAll()
                }
            }
        }
        button {
            addClass("btn", "btn--secondary")
            tooltip(messages["previousVerse"])
            graphic = FontIcon(MaterialDesign.MDI_SKIP_PREVIOUS)
            setOnAction {
                viewModel.seekToPrevious()
            }
            disableWhen {
                viewModel.isPlayingProperty.or(viewModel.isRecordingProperty).or(viewModel.hasVersesProperty.not())
            }
        }
        button {
            addClass("btn", "btn--secondary")
            tooltip(messages["nextVerse"])
            graphic = FontIcon(MaterialDesign.MDI_SKIP_NEXT)
            setOnAction {
                viewModel.seekToNext()
            }
            disableWhen {
                viewModel.isPlayingProperty.or(viewModel.isRecordingProperty).or(viewModel.hasVersesProperty.not())
            }
        }
    }
}

class NarrationToolbarViewModel : ViewModel() {
    private val logger = LoggerFactory.getLogger(NarrationHeaderViewModel::class.java)

    private val workbookDataStore by inject<WorkbookDataStore>()
    private val narrationViewModel: NarrationViewModel by inject()
    private val audioPluginViewModel: AudioPluginViewModel by inject()

    val titleProperty = workbookDataStore.activeWorkbookProperty.stringBinding {
        it?.let {
            MessageFormat.format(
                messages["narrationTitle"],
                it.target.title
            )
        } ?: ""
    }

    val chapterTitleProperty = SimpleStringProperty()

    val hasNextChapter = SimpleBooleanProperty()
    val hasPreviousChapter = SimpleBooleanProperty()
    val hasVersesProperty = SimpleBooleanProperty()

    val chapterTakeProperty = SimpleObjectProperty<Take>()
    val hasChapterTakeProperty = chapterTakeProperty.isNotNull

    val hasUndoProperty = SimpleBooleanProperty()
    val hasRedoProperty = SimpleBooleanProperty()

    val pluginContextProperty = SimpleObjectProperty(PluginType.EDITOR)

    private val chapterList: ObservableList<Chapter> = observableListOf()

    init {
        chapterList.bind(narrationViewModel.chapterList) { it }

        chapterTakeProperty.bind(narrationViewModel.chapterTakeProperty)
        chapterTitleProperty.bind(narrationViewModel.chapterTitleProperty)
        hasNextChapter.bind(narrationViewModel.hasNextChapter)
        hasPreviousChapter.bind(narrationViewModel.hasPreviousChapter)

        hasUndoProperty.bind(narrationViewModel.hasUndoProperty)
        hasRedoProperty.bind(narrationViewModel.hasRedoProperty)
        hasVersesProperty.bind(narrationViewModel.hasVersesProperty)
    }
}