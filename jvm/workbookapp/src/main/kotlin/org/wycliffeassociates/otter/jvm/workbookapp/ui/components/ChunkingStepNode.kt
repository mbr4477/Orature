package org.wycliffeassociates.otter.jvm.workbookapp.ui.components

import javafx.beans.binding.ObjectBinding
import javafx.beans.property.BooleanProperty
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.event.EventTarget
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.material.Material
import org.kordamp.ikonli.materialdesign.MaterialDesign
import org.wycliffeassociates.otter.jvm.utils.bindSingleChild
import org.wycliffeassociates.otter.jvm.utils.onChangeAndDoNow
import org.wycliffeassociates.otter.jvm.workbookapp.ui.model.ChunkingStep
import tornadofx.*
import tornadofx.FX.Companion.messages

class ChunkingStepNode(
    step: ChunkingStep,
    selectedStepProperty: ObjectProperty<ChunkingStep>,
    reachableStepProperty: ObjectProperty<ChunkingStep>,
    hideCompletedProperty: BooleanProperty,
    isCollapsedProperty: BooleanProperty,
    content: Node? = null
) : VBox() {
    private val mainSectionProperty = SimpleObjectProperty<Node>(null)
    private val unavailableProperty = reachableStepProperty.booleanBinding {
        it?.let { reachable ->
            reachable.ordinal < step.ordinal
        } ?: true
    }
    private val isSelectedProperty = booleanBinding(selectedStepProperty) {
        step == selectedStepProperty.value
    }
    private val completedProperty = booleanBinding(selectedStepProperty) {
        step.ordinal < selectedStepProperty.value.ordinal
    }

    init {
        addClass("chunking-step")
        isFocusTraversable = true
        visibleWhen {
            booleanBinding(hideCompletedProperty, selectedStepProperty) {
                hideCompletedProperty.value == false || step.ordinal >= selectedStepProperty.value.ordinal
            }
        }
        managedWhen(visibleProperty())
        disableWhen(unavailableProperty)
        completedProperty.onChangeAndDoNow { toggleClass("completed", it == true) }

        stackpane {
            hbox {
                addClass("chunking-step__header-section")
                visibleWhen { isCollapsedProperty.not() }
                managedWhen(visibleProperty())

                label(messages[step.titleKey]) {
                    addClass("chunking-step__title", "normal-text")
                    graphicProperty().bind(createGraphicBinding(step))
                }
                region { hgrow = Priority.ALWAYS }
            }
            hbox {
                addClass("chunking-step__header-section")
                label {
                    addClass("chunking-step__title")
                    graphicProperty().bind(createGraphicBinding(step))
                    visibleWhen { isCollapsedProperty }
                    managedWhen(visibleProperty())
                }
            }
        }

        hbox {
            /* expands when step is selected (similar to titled pane & accordion) */
            addClass("chunking-step__content-section")
            bindSingleChild(mainSectionProperty)

            visibleWhen { isSelectedProperty.and(isCollapsedProperty.not()) }
            managedWhen(visibleProperty())
            mainSectionProperty.bind(
                isSelectedProperty.objectBinding {
                    this@ChunkingStepNode.togglePseudoClass("selected", it == true)
                    if (it == true) content else null
                }
            )
        }

        setOnMouseClicked {
            selectedStepProperty.set(step)
            requestFocus()
        }

        this.addEventFilter(KeyEvent.KEY_PRESSED) {
            if (it.code == KeyCode.ENTER || it.code == KeyCode.SPACE) {
                selectedStepProperty.set(step)
            }
        }
    }

    private fun createGraphicBinding(step: ChunkingStep) : ObjectBinding<Node?> {
        return objectBinding(unavailableProperty, isSelectedProperty, completedProperty) {
            when {
                unavailableProperty.value -> FontIcon(MaterialDesign.MDI_LOCK).apply { addClass("icon") }
                completedProperty.value -> FontIcon(MaterialDesign.MDI_CHECK_CIRCLE).apply { addClass("complete-icon") }
                else -> getStepperIcon(step)
            }
        }
    }

    private fun getStepperIcon(step: ChunkingStep) = when (step) {
        ChunkingStep.CONSUME_AND_VERBALIZE -> FontIcon(Material.HEARING).apply { addClass("icon") }
        ChunkingStep.CHUNKING -> FontIcon(MaterialDesign.MDI_CONTENT_CUT).apply { addClass("icon") }
        ChunkingStep.BLIND_DRAFT -> FontIcon(MaterialDesign.MDI_HEADSET).apply { addClass("icon") }
        ChunkingStep.PEER_EDIT -> FontIcon(MaterialDesign.MDI_ACCOUNT_MULTIPLE).apply { addClass("icon") }
        ChunkingStep.KEYWORD_CHECK -> FontIcon(Material.BORDER_COLOR).apply { addClass("icon") }
        ChunkingStep.VERSE_CHECK -> FontIcon(Material.MENU_BOOK).apply { addClass("icon") }
        else -> null
    }
}

fun EventTarget.chunkingStep(
    step: ChunkingStep,
    selectedStepProperty: ObjectProperty<ChunkingStep>,
    reachableStepProperty: ObjectProperty<ChunkingStep>,
    hideCompletedProperty: BooleanProperty,
    isCollapsedProperty: BooleanProperty,
    content: Node? = null,
    op: ChunkingStepNode.() -> Unit = {}
) = ChunkingStepNode(step, selectedStepProperty, reachableStepProperty, hideCompletedProperty, isCollapsedProperty, content).attachTo(
    this,
    op
)