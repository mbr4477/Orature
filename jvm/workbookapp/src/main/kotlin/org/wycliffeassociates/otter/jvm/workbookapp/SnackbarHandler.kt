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
package org.wycliffeassociates.otter.jvm.workbookapp

import com.jfoenix.controls.JFXSnackbar
import com.jfoenix.controls.JFXSnackbarLayout
import javafx.scene.layout.Pane
import javafx.util.Duration
import org.wycliffeassociates.otter.jvm.controls.model.NotificationViewData
import org.wycliffeassociates.otter.jvm.controls.popup.NotificationSnackBar
import tornadofx.*

const val NOTIFICATION_DURATION_SEC = 5.0

object SnackbarHandler {

    private val snackbar = JFXSnackbar()

    fun setWindowRoot(pane: Pane) {
        snackbar.registerSnackbarContainer(pane)
    }

    fun enqueue(event: JFXSnackbar.SnackbarEvent) {
        if (snackbar.popupContainer != null) {
            snackbar.enqueue(event)
        }
    }

    fun enqueue(customSnackbar: JFXSnackbarLayout) {
        if (snackbar.popupContainer != null) {
            snackbar.enqueue(JFXSnackbar.SnackbarEvent(customSnackbar))
        }
    }

    fun enqueue(message: String) {
        if (snackbar.popupContainer != null) {
            snackbar.enqueue(JFXSnackbar.SnackbarEvent(JFXSnackbarLayout(message)))
        }
    }

    fun enqueue(notification: NotificationViewData) {
        val graphic = NotificationSnackBar(notification).apply {
            setOnDismiss { snackbar.close() }
            setOnMainAction {
                notification.actionCallback()
                snackbar.close()
            }
        }
        snackbar.enqueue(
            JFXSnackbar.SnackbarEvent(
                graphic,
                Duration.seconds(NOTIFICATION_DURATION_SEC)
            )
        )
    }
}
