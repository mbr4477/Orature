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
package org.wycliffeassociates.otter.jvm.workbookapp.ui.screens

import com.github.thomasnield.rxkotlinfx.toLazyBinding
import com.jfoenix.controls.JFXSnackbar
import com.jfoenix.controls.JFXSnackbarLayout
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleBooleanProperty
import javafx.scene.Parent
import javafx.scene.control.ScrollPane
import javafx.scene.input.DragEvent
import javafx.scene.input.Dragboard
import javafx.scene.input.TransferMode
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import javafx.scene.layout.RowConstraints
import javafx.scene.layout.VBox
import javafx.util.Duration
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign.MaterialDesign
import org.slf4j.LoggerFactory
import org.wycliffeassociates.otter.common.persistence.repositories.PluginType
import org.wycliffeassociates.otter.jvm.controls.Shortcut
import org.wycliffeassociates.otter.jvm.controls.breadcrumbs.BreadCrumb
import org.wycliffeassociates.otter.jvm.controls.card.ListViewPlaceHolder
import org.wycliffeassociates.otter.jvm.controls.card.NewRecordingCard
import org.wycliffeassociates.otter.jvm.controls.card.ScriptureTakeCardCell
import org.wycliffeassociates.otter.jvm.controls.card.events.DeleteTakeEvent
import org.wycliffeassociates.otter.jvm.controls.card.events.TakeEvent
import org.wycliffeassociates.otter.jvm.controls.dialog.PluginOpenedPage
import org.wycliffeassociates.otter.jvm.controls.dialog.confirmdialog
import org.wycliffeassociates.otter.jvm.controls.event.NavigationRequestEvent
import org.wycliffeassociates.otter.jvm.controls.listview.NoSelectionModel
import org.wycliffeassociates.otter.jvm.controls.media.SourceContent
import org.wycliffeassociates.otter.jvm.controls.styles.tryImportStylesheet
import org.wycliffeassociates.otter.jvm.utils.onChangeWithDisposer
import org.wycliffeassociates.otter.jvm.workbookapp.SnackbarHandler
import org.wycliffeassociates.otter.jvm.workbookapp.plugin.PluginClosedEvent
import org.wycliffeassociates.otter.jvm.workbookapp.plugin.PluginOpenedEvent
import org.wycliffeassociates.otter.jvm.workbookapp.ui.NavigationMediator
import org.wycliffeassociates.otter.jvm.workbookapp.ui.viewmodel.*
import tornadofx.*
import java.text.MessageFormat
import java.util.*

private const val TAKES_ROW_HEIGHT = 197.0

class RecordScripturePage : View() {
    private val logger = LoggerFactory.getLogger(RecordScripturePage::class.java)

    private val recordScriptureViewModel: RecordScriptureViewModel by inject()
    private val settingsViewModel: SettingsViewModel by inject()
    private val workbookDataStore: WorkbookDataStore by inject()
    private val audioDataStore: AudioDataStore by inject()
    private val navigator: NavigationMediator by inject()
    private val audioPluginViewModel: AudioPluginViewModel by inject()

    private val mainContainer = GridPane()
    private val fileDragTarget = VBox()
    private val pluginOpenedPage: PluginOpenedPage

    private val isDraggingFileProperty = SimpleBooleanProperty(false)

    private val sourceContent =
        SourceContent().apply {
            sourceTextProperty.bind(workbookDataStore.sourceTextBinding())
            sourceAudioPlayerProperty.bind(audioDataStore.sourceAudioPlayerProperty)
            licenseProperty.bind(workbookDataStore.sourceLicenseProperty)
            isMinimizableProperty.set(false)
            highlightedChunk.bind(recordScriptureViewModel.highlightedChunkProperty)

            audioNotAvailableTextProperty.set(messages["audioNotAvailable"])
            textNotAvailableTextProperty.set(messages["textNotAvailable"])
            playSourceLabelProperty.set(messages["playSource"])
            pauseSourceLabelProperty.set(messages["pauseSource"])

            contentTitleProperty.bind(workbookDataStore.activeTitleBinding())
            orientationProperty.bind(settingsViewModel.orientationProperty)
            sourceOrientationProperty.bind(settingsViewModel.sourceOrientationProperty)

            sourceSpeedRateProperty.bind(
                workbookDataStore.activeWorkbookProperty.select {
                    it.translation.sourceRate.toLazyBinding()
                }
            )

            targetSpeedRateProperty.bind(
                workbookDataStore.activeWorkbookProperty.select {
                    it.translation.targetRate.toLazyBinding()
                }
            )
        }

    private val breadCrumb = BreadCrumb().apply {
        titleProperty.bind(
            workbookDataStore.activeChunkProperty.stringBinding { chunk ->
                chunk?.let {
                    MessageFormat.format(
                        messages["chunkTitle"],
                        messages[chunk.label],
                        chunk.title
                    )
                } ?: messages["chapter"]
            }
        )
        iconProperty.set(FontIcon(MaterialDesign.MDI_BOOKMARK_OUTLINE))
        setOnAction {
            fire(NavigationRequestEvent(this@RecordScripturePage))
        }
    }

    override val root = anchorpane {
        addButtonEventHandlers()
        createSnackBar()

        add(mainContainer
            .apply {
                anchorpaneConstraints {
                    leftAnchor = 0.0
                    rightAnchor = 0.0
                    bottomAnchor = 0.0
                    topAnchor = 0.0
                }
            }
        )
        add(fileDragTarget
            .apply {
                anchorpaneConstraints {
                    leftAnchor = 0.0
                    rightAnchor = 0.0
                    bottomAnchor = 0.0
                    topAnchor = 0.0
                }
            }
        )
    }

    init {
        tryImportStylesheet(resources["/css/record-scripture.css"])
        tryImportStylesheet(resources["/css/takecard.css"])
        tryImportStylesheet(resources["/css/scripturetakecard.css"])
        tryImportStylesheet(resources["/css/add-plugin-dialog.css"])

        isDraggingFileProperty.onChange {
            if (it) recordScriptureViewModel.stopPlayers()
        }

        pluginOpenedPage = createPluginOpenedPage()
        workspace.subscribe<PluginOpenedEvent> { pluginInfo ->
            if (!pluginInfo.isNative) {
                workspace.dock(pluginOpenedPage)
                recordScriptureViewModel.openSourceAudioPlayer()
                recordScriptureViewModel.openTargetAudioPlayer()
            }
        }

        fileDragTarget.setOnDragOver {
            if (it.gestureSource != fileDragTarget && it.dragboard.hasFiles()) {
                it.acceptTransferModes(*TransferMode.ANY)
            }
            it.consume()
        }

        fileDragTarget.setOnDragDropped {
            val db: Dragboard = it.dragboard
            var success = false
            if (db.hasFiles()) {
                recordScriptureViewModel.importTakes(db.files)
                success = true
            }
            it.isDropCompleted = success
            it.consume()
        }

        mainContainer.apply {
            addEventHandler(DragEvent.DRAG_ENTERED) {
                if (it.dragboard.hasFiles()) {
                    isDraggingFileProperty.value = true
                }
            }

            addClass("card--main-container")

            val rightPane = ScrollPane().apply {
                isFitToWidth = true
                isFitToHeight = true

                vbox {
                    addClass("record-scripture__right")

                    hbox {
                        addClass("record-scripture__navigate-buttons")

                        // previous verse button
                        button(messages["previousVerse"]) {
                            addClass("btn", "btn--secondary")
                            tooltip(text)
                            graphic = FontIcon(MaterialDesign.MDI_ARROW_LEFT).apply {
                                scaleXProperty().bind(settingsViewModel.orientationScaleProperty)
                            }
                            action {
                                recordScriptureViewModel.previousChunk()
                            }
                            enableWhen(recordScriptureViewModel.hasPreviousChunk)
                            visibleWhen(recordScriptureViewModel.isChunk)
                            managedWhen(visibleProperty())
                        }

                        // previous chapter button
                        button(messages["previousChapter"]) {
                            addClass("btn", "btn--secondary")
                            tooltip(text)
                            graphic = FontIcon(MaterialDesign.MDI_ARROW_LEFT).apply {
                                scaleXProperty().bind(settingsViewModel.orientationScaleProperty)
                            }
                            action {
                                recordScriptureViewModel.previousChapter()
                            }
                            enableWhen(recordScriptureViewModel.hasPreviousChapter)
                            visibleWhen(recordScriptureViewModel.isChunk.not())
                            managedWhen(visibleProperty())
                        }

                        vbox {
                            addClass("record-scripture__book-info")
                            hgrow = Priority.ALWAYS
                            label {
                                addClass("record-scripture__book-title")
                                textProperty().bind(workbookDataStore.activeChapterTitleBinding())
                            }
                            label {
                                addClass("record-scripture__chunk-title")
                                textProperty().bind(workbookDataStore.activeChunkTitleBinding())
                                visibleProperty().bind(workbookDataStore.activeChunkTitleBinding().isNotNull)
                                managedProperty().bind(visibleProperty())
                            }
                        }

                        // next verse button
                        button(messages["nextVerse"]) {
                            addClass("btn", "btn--secondary")
                            tooltip(text)
                            graphic = FontIcon(MaterialDesign.MDI_ARROW_RIGHT).apply {
                                scaleXProperty().bind(settingsViewModel.orientationScaleProperty)
                            }
                            action {
                                recordScriptureViewModel.nextChunk()
                            }
                            enableWhen(recordScriptureViewModel.hasNextChunk)
                            visibleWhen(recordScriptureViewModel.isChunk)
                            managedWhen(visibleProperty())
                        }

                        // next chapter button
                        button(messages["nextChapter"]) {
                            addClass("btn", "btn--secondary")
                            tooltip(text)
                            graphic = FontIcon(MaterialDesign.MDI_ARROW_RIGHT).apply {
                                scaleXProperty().bind(settingsViewModel.orientationScaleProperty)
                            }
                            action {
                                recordScriptureViewModel.nextChapter()
                            }
                            enableWhen(recordScriptureViewModel.hasNextChapter)
                            visibleWhen(recordScriptureViewModel.isChunk.not())
                            managedWhen(visibleProperty())
                        }
                    }

                    add(
                        NewRecordingCard(
                            FX.messages["record"],
                            recordScriptureViewModel::recordNewTake
                        ).apply {
                            shortcut(Shortcut.RECORD.value, action)
                        }
                    )

                    listview(recordScriptureViewModel.takeCardViews) {
                        addClass("record-scripture__take-list")
                        vgrow = Priority.ALWAYS

                        setCellFactory { ScriptureTakeCardCell() }

                        minHeightProperty().bind(Bindings.size(items).multiply(TAKES_ROW_HEIGHT))
                        placeholder = ListViewPlaceHolder()

                        selectionModelProperty().set(NoSelectionModel())
                    }
                }
            }

            add(sourceContent, 0, 0)
            add(rightPane, 1, 0)

            val sourceContentColumn = ColumnConstraints()
            sourceContentColumn.percentWidth = 40.0

            val rightPaneColumn = ColumnConstraints()
            rightPaneColumn.percentWidth = 60.0

            val row = RowConstraints()
            row.vgrow = Priority.ALWAYS

            columnConstraints.addAll(sourceContentColumn, rightPaneColumn)
            rowConstraints.add(row)
        }
        fileDragTarget.apply {
            visibleProperty().bind(isDraggingFileProperty)
            isDraggingFileProperty.onChange {
                toggleClass("card--container-dragover", it)
            }
            addEventHandler(DragEvent.DRAG_EXITED) {
                isDraggingFileProperty.value = false
            }
        }
    }

    private fun Parent.addButtonEventHandlers() {
        addEventHandler(DeleteTakeEvent.DELETE_TAKE) {
            recordScriptureViewModel.deleteTake(it.take)
        }
        addEventHandler(TakeEvent.EDIT_TAKE) {
            recordScriptureViewModel.processTakeWithPlugin(it, PluginType.EDITOR)
        }
        addEventHandler(TakeEvent.MARK_TAKE) {
            recordScriptureViewModel.processTakeWithPlugin(it, PluginType.MARKER)
        }
        addEventHandler(TakeEvent.SELECT_TAKE) {
            recordScriptureViewModel.selectTake(it.take).subscribe()
        }
    }

    private fun createSnackBar() {
        recordScriptureViewModel
            .snackBarObservable
            .doOnError { e ->
                logger.error("Error in creating no plugin snackbar", e)
            }
            .subscribe { pluginErrorMessage ->
                SnackbarHandler.enqueue(
                    JFXSnackbar.SnackbarEvent(
                        JFXSnackbarLayout(
                            pluginErrorMessage,
                            messages["addApp"].uppercase(Locale.getDefault())
                        ) {
                            audioPluginViewModel.addPlugin(true, false)
                        },
                        Duration.millis(5000.0),
                        null
                    )
                )
            }
    }

    private fun createPluginOpenedPage(): PluginOpenedPage {
        // Plugin active cover
        return find<PluginOpenedPage>().apply {
            dialogTitleProperty.bind(recordScriptureViewModel.dialogTitleBinding())
            dialogTextProperty.bind(recordScriptureViewModel.dialogTextBinding())
            playerProperty.bind(audioDataStore.sourceAudioPlayerProperty)
            audioAvailableProperty.bind(audioDataStore.sourceAudioAvailableProperty)
            licenseProperty.bind(workbookDataStore.sourceLicenseProperty)
            sourceTextProperty.bind(workbookDataStore.sourceTextBinding())
            sourceContentTitleProperty.bind(workbookDataStore.activeTitleBinding())
            targetAudioPlayerProperty.bind(audioDataStore.targetAudioProperty.objectBinding { it?.player })
            orientationProperty.bind(settingsViewModel.orientationProperty)
            sourceOrientationProperty.bind(settingsViewModel.sourceOrientationProperty)

            sourceSpeedRateProperty.bind(
                workbookDataStore.activeWorkbookProperty.select {
                    it.translation.sourceRate.toLazyBinding()
                }
            )
            targetSpeedRateProperty.bind(
                workbookDataStore.activeWorkbookProperty.select {
                    it.translation.targetRate.toLazyBinding()
                }
            )
            sourceTextZoomRateProperty.bind(
                recordScriptureViewModel.sourceTextZoomRateProperty
            )
        }
    }

    private fun initializeImportProgressDialog() {
        confirmdialog {
            titleTextProperty.set(messages["importTakesTitle"])
            messageTextProperty.set(messages["importTakesMessage"])

            recordScriptureViewModel.showImportProgressDialogProperty.onChangeWithDisposer {
                if (it == true) open() else close()
            }.let(recordScriptureViewModel.listeners::add)

            progressTitleProperty.set(messages["pleaseWait"])
            showProgressBarProperty.set(true)

            orientationProperty.set(settingsViewModel.orientationProperty.value)
            themeProperty.set(settingsViewModel.appColorMode.value)
        }
    }

    private fun initializeImportSuccessDialog() {
        confirmdialog {
            titleTextProperty.set(messages["importTakesTitle"])
            messageTextProperty.set(messages["importTakesSuccessMessage"])
            cancelButtonTextProperty.set(messages["close"])
            orientationProperty.set(settingsViewModel.orientationProperty.value)
            themeProperty.set(settingsViewModel.appColorMode.value)

            recordScriptureViewModel.showImportSuccessDialogProperty.onChangeWithDisposer {
                if (it == true) open() else close()
            }.let(recordScriptureViewModel.listeners::add)

            onCloseAction { recordScriptureViewModel.showImportSuccessDialogProperty.set(false) }
            onCancelAction { recordScriptureViewModel.showImportSuccessDialogProperty.set(false) }
        }
    }

    private fun initializeImportFailDialog() {
        confirmdialog {
            titleTextProperty.set(messages["importTakesTitle"])
            messageTextProperty.set(messages["importTakesFailMessage"])
            cancelButtonTextProperty.set(messages["close"])
            orientationProperty.set(settingsViewModel.orientationProperty.value)
            themeProperty.set(settingsViewModel.appColorMode.value)

            recordScriptureViewModel.showImportFailDialogProperty.onChangeWithDisposer {
                if (it == true) open() else close()
            }.let(recordScriptureViewModel.listeners::add)

            onCloseAction { recordScriptureViewModel.showImportFailDialogProperty.set(false) }
            onCancelAction { recordScriptureViewModel.showImportFailDialogProperty.set(false) }
        }
    }

    private fun initializeSourceContentListeners() {
        sourceContent.initializeListeners()
        sourceContent.sourceAudioPlayerProperty.onChangeWithDisposer {
            it?.let { player ->
                shortcut(Shortcut.PLAY_SOURCE.value, player::toggle)
            }
        }.let(sourceContent.listeners::add)

        sourceContent.targetAudioPlayerProperty.onChangeWithDisposer {
            it?.let { player ->
                shortcut(Shortcut.PLAY_TARGET.value, player::toggle)
            }
        }.let(sourceContent.listeners::add)
    }

    override fun onUndock() {
        super.onUndock()
        sourceContent.removeListeners()
        recordScriptureViewModel.undock()
    }

    override fun onDock() {
        super.onDock()

        initializeSourceContentListeners()
        recordScriptureViewModel.dock()

        sourceContent.zoomRateProperty.set(recordScriptureViewModel.sourceTextZoomRateProperty.value)
        navigator.dock(this, breadCrumb)

        initializeImportProgressDialog()
        initializeImportFailDialog()
        initializeImportSuccessDialog()
    }
}
