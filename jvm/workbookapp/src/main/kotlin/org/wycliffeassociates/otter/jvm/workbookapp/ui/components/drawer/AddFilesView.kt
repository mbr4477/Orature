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
package org.wycliffeassociates.otter.jvm.workbookapp.ui.components.drawer

import com.github.thomasnield.rxkotlinfx.observeOnFx
import com.jfoenix.controls.JFXSnackbar
import com.jfoenix.controls.JFXSnackbarLayout
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.scene.control.Button
import javafx.scene.input.DragEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.TransferMode
import javafx.scene.layout.Priority
import javafx.stage.FileChooser
import javafx.util.Duration
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign.MaterialDesign
import org.slf4j.LoggerFactory
import org.wycliffeassociates.otter.common.data.OratureFileFormat
import org.wycliffeassociates.otter.jvm.controls.customizeScrollbarSkin
import org.wycliffeassociates.otter.jvm.controls.dialog.OtterDialog
import org.wycliffeassociates.otter.jvm.controls.dialog.ProgressDialog
import org.wycliffeassociates.otter.jvm.controls.dialog.confirmdialog
import org.wycliffeassociates.otter.jvm.controls.styles.tryImportStylesheet
import org.wycliffeassociates.otter.jvm.workbookapp.SnackbarHandler
import org.wycliffeassociates.otter.jvm.workbookapp.ui.viewmodel.ImportProjectViewModel
import org.wycliffeassociates.otter.jvm.workbookapp.ui.viewmodel.SettingsViewModel
import tornadofx.*
import java.io.File
import java.text.MessageFormat

class AddFilesView : View() {
    private val logger = LoggerFactory.getLogger(AddFilesView::class.java)

    private val viewModel: ImportProjectViewModel by inject()
    private val settingsViewModel: SettingsViewModel by inject()

    private lateinit var closeButton: Button

    override val root = vbox {
        addClass("app-drawer__content")

        DrawerTraversalEngine(this)

        scrollpane {
            addClass("app-drawer__scroll-pane")
            fitToParentHeight()

            vbox {
                isFitToWidth = true
                isFitToHeight = true

                addClass("app-drawer-container")

                hbox {
                    label(messages["importFiles"]).apply {
                        addClass("app-drawer__title")
                    }
                    region { hgrow = Priority.ALWAYS }
                    button {
                        addClass("btn", "btn--secondary")
                        graphic = FontIcon(MaterialDesign.MDI_CLOSE)
                        tooltip(messages["close"])
                        action { collapse() }
                        closeButton = this
                    }
                }

                vbox {
                    addClass("app-drawer__section")
                    label(messages["dragAndDrop"]).apply {
                        addClass("app-drawer__subtitle")
                    }

                    textflow {
                        text(messages["dragAndDropDescription"]).apply {
                            addClass("app-drawer__text")
                        }
                        hyperlink("audio.bibleineverylanguage.org").apply {
                            addClass("wa-text--hyperlink", "app-drawer__text--link")
                            tooltip {
                                text = "audio.bibleineverylanguage.org/gl"
                            }
                            action {
                                hostServices.showDocument("https://audio.bibleineverylanguage.org/gl")
                            }
                        }
                    }
                }

                vbox {
                    addClass("app-drawer__drag-drop-area")

                    vgrow = Priority.ALWAYS

                    label {
                        addClass("app-drawer__drag-drop-area__icon")
                        graphic = FontIcon(MaterialDesign.MDI_FILE_MULTIPLE)
                    }

                    label(messages["dragToImport"]) {
                        fitToParentWidth()
                        addClass("app-drawer__text--centered")
                    }

                    button(messages["browseFiles"]) {
                        addClass(
                            "btn",
                            "btn--primary"
                        )
                        tooltip {
                            textProperty().bind(this@button.textProperty())
                        }
                        graphic = FontIcon(MaterialDesign.MDI_OPEN_IN_NEW)
                        action {
                            chooseFile(
                                FX.messages["importResourceFromZip"],
                                arrayOf(
                                    FileChooser.ExtensionFilter(
                                        messages["oratureFileTypes"],
                                        *OratureFileFormat.extensionList.map { "*.$it" }.toTypedArray()
                                    )
                                ),
                                mode = FileChooserMode.Single,
                                owner = currentWindow
                            ).firstOrNull()?.let { importFile(it) }
                        }
                    }

                    onDragOver = onDragOverHandler()
                    onDragDropped = onDragDroppedHandler()
                }
            }
        }

        setOnKeyReleased {
            if (it.code == KeyCode.ESCAPE) collapse()
        }

        runLater { customizeScrollbarSkin() }
    }

    init {
        tryImportStylesheet(resources["/css/app-drawer.css"])
        tryImportStylesheet(resources["/css/confirm-dialog.css"])
        tryImportStylesheet(resources["/css/import-export-dialogs.css"])
        tryImportStylesheet(resources["/css/card-radio-btn.css"])

        initSuccessDialog()
        initErrorDialog()
        createSnackBar()

        subscribe<DrawerEvent<UIComponent>> {
            if (it.action == DrawerEventAction.OPEN) {
                focusCloseButton()
            }
        }
    }

    override fun onDock() {
        super.onDock()
        focusCloseButton()
    }

    private fun initSuccessDialog() {
        val successDialog = confirmdialog {
            titleTextProperty.bind(
                viewModel.importedProjectTitleProperty.stringBinding {
                    it?.let {
                        MessageFormat.format(
                            messages["importProjectTitle"],
                            messages["import"],
                            it
                        )
                    } ?: messages["importResource"]
                }
            )
            messageTextProperty.set(messages["importResourceSuccessMessage"])
            backgroundImageFileProperty.bind(viewModel.importedProjectCoverProperty)
            orientationProperty.set(settingsViewModel.orientationProperty.value)
            themeProperty.set(settingsViewModel.appColorMode.value)

            cancelButtonTextProperty.set(messages["close"])
            onCloseAction { viewModel.showImportSuccessDialogProperty.set(false) }
            onCancelAction { viewModel.showImportSuccessDialogProperty.set(false) }
        }

        viewModel.showImportSuccessDialogProperty.onChange {
            Platform.runLater { if (it) successDialog.open() else successDialog.close() }
        }
    }

    private fun initErrorDialog() {
        val errorDialog = confirmdialog {
            titleTextProperty.bind(
                viewModel.importedProjectTitleProperty.stringBinding {
                    it?.let {
                        MessageFormat.format(
                            messages["importProjectTitle"],
                            messages["import"],
                            it
                        )
                    } ?: messages["importResource"]
                }
            )
            messageTextProperty.bind(viewModel.importErrorMessage.stringBinding {
                it ?: messages["importResourceFailMessage"]
            })
            backgroundImageFileProperty.bind(viewModel.importedProjectCoverProperty)
            orientationProperty.set(settingsViewModel.orientationProperty.value)
            themeProperty.set(settingsViewModel.appColorMode.value)

            cancelButtonTextProperty.set(messages["close"])
            onCloseAction { viewModel.showImportErrorDialogProperty.set(false) }
            onCancelAction { viewModel.showImportErrorDialogProperty.set(false) }
        }

        viewModel.showImportErrorDialogProperty.onChange {
            Platform.runLater { if (it) errorDialog.open() else errorDialog.close() }
        }
    }

    private fun onDragOverHandler(): EventHandler<DragEvent> {
        return EventHandler {
            if (it.gestureSource != this && it.dragboard.hasFiles()) {
                it.acceptTransferModes(TransferMode.COPY)
            }
            it.consume()
        }
    }

    private fun onDragDroppedHandler(): EventHandler<DragEvent> {
        return EventHandler {
            var success = false
            if (it.dragboard.hasFiles()) {
                onDropFile(it.dragboard.files)
                success = true
            }
            it.isDropCompleted = success
            it.consume()
        }
    }

    private fun onDropFile(files: List<File>) {
        if (viewModel.isValidImportFile(files)) {
            logger.info("Drag-drop file to import: ${files.first()}")
            val fileToImport = files.first()
            importFile(fileToImport)
        }
    }

    private fun importFile(file: File) {
        viewModel.setProjectInfo(file)

        val dialog = find<ProgressDialog> {
            orientationProperty.set(settingsViewModel.orientationProperty.value)
            themeProperty.set(settingsViewModel.appColorMode.value)
            cancelMessageProperty.set(null)
            dialogTitleProperty.bind(viewModel.importedProjectTitleProperty.stringBinding {
                it?.let {
                    MessageFormat.format(
                        messages["importProjectTitle"],
                        messages["import"],
                        it
                    )
                } ?: messages["importResource"]
            })

            setOnCloseAction { close() }

            open()
        }

        viewModel.importProject(file)
            .observeOnFx()
            .doFinally {
                dialog.dialogTitleProperty.unbind()
                dialog.percentageProperty.set(0.0)
                dialog.close()
            }
            .subscribe { progressStatus ->
                progressStatus.percent?.let { percent ->
                    dialog.percentageProperty.set(percent)
                }
                if (progressStatus.titleKey != null && progressStatus.titleMessage != null) {
                    val message = MessageFormat.format(messages[progressStatus.titleKey!!], messages[progressStatus.titleMessage!!])
                    dialog.progressMessageProperty.set(message)
                } else if (progressStatus.titleKey != null) {
                    dialog.progressMessageProperty.set(messages[progressStatus.titleKey!!])
                }
            }
    }

    private fun createSnackBar() {
        viewModel
            .snackBarObservable
            .doOnError { e ->
                logger.error("Error in creating add files snackbar", e)
            }
            .subscribe { pluginErrorMessage ->
                SnackbarHandler.enqueue(
                    JFXSnackbar.SnackbarEvent(
                        JFXSnackbarLayout(pluginErrorMessage),
                        Duration.millis(5000.0),
                        null
                    )
                )
            }
    }

    private fun focusCloseButton() {
        runAsync {
            Thread.sleep(500)
            runLater(closeButton::requestFocus)
        }
    }

    private fun collapse() {
        fire(DrawerEvent(this::class, DrawerEventAction.CLOSE))
    }
}
