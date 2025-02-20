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
package org.wycliffeassociates.otter.jvm.workbookapp.ui.viewmodel

import com.github.thomasnield.rxkotlinfx.observeOnFx
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleListProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import org.wycliffeassociates.otter.common.data.primitives.CheckingStatus
import org.wycliffeassociates.otter.common.data.primitives.ProjectMode
import org.wycliffeassociates.otter.common.data.workbook.Chapter
import org.wycliffeassociates.otter.common.data.workbook.Chunk
import org.wycliffeassociates.otter.common.domain.collections.CreateProject
import org.wycliffeassociates.otter.jvm.controls.model.ChapterGridItemData
import org.wycliffeassociates.otter.jvm.workbookapp.ui.model.ChunkViewData
import org.wycliffeassociates.otter.jvm.controls.model.ChunkingStep
import org.wycliffeassociates.otter.jvm.workbookapp.di.IDependencyGraphProvider
import org.wycliffeassociates.otter.jvm.workbookapp.ui.NavigationMediator
import tornadofx.*
import javax.inject.Inject

class TranslationViewModel2 : ViewModel() {

    @Inject
    lateinit var creationUseCase: CreateProject

    val workbookDataStore: WorkbookDataStore by inject()
    val audioDataStore: AudioDataStore by inject()
    private val navigator: NavigationMediator by inject()

    val bookTitleProperty = workbookDataStore.activeWorkbookProperty.stringBinding {
        it?.target?.title
    }
    val canUndoProperty = SimpleBooleanProperty(false)
    val canRedoProperty = SimpleBooleanProperty(false)
    val isFirstChapterProperty = SimpleBooleanProperty(false)
    val isLastChapterProperty = SimpleBooleanProperty(false)
    val showAudioMissingViewProperty = SimpleBooleanProperty(false)
    val selectedStepProperty = SimpleObjectProperty<ChunkingStep>(null)
    val reachableStepProperty = SimpleObjectProperty<ChunkingStep>(ChunkingStep.CHUNKING)
    val sourceTextProperty = SimpleStringProperty()
    val sourceInfoProperty = workbookDataStore.sourceInfoProperty
    val sourceLicenseProperty = workbookDataStore.sourceLicenseProperty
    val currentMarkerProperty = SimpleIntegerProperty(-1)
    val chapterList = observableListOf<ChapterGridItemData>()
    val chunkList = observableListOf<ChunkViewData>()
    val chunkListProperty = SimpleListProperty<ChunkViewData>(chunkList)
    val selectedChunkBinding = workbookDataStore.activeChunkProperty.integerBinding { it?.sort ?: -1 }
    val loadingStepProperty = SimpleBooleanProperty(false)

    private val compositeDisposable = CompositeDisposable()

    init {
        (app as IDependencyGraphProvider).dependencyGraph.inject(this)
    }

    fun dockPage() {
        val recentChapter = workbookDataStore.workbookRecentChapterMap.getOrDefault(
            workbookDataStore.workbook.hashCode(),
            1
        )
        navigateChapter(recentChapter)
    }

    fun undockPage() {
        selectedStepProperty.set(null)
        workbookDataStore.activeChapterProperty.set(null)
        compositeDisposable.clear()
        audioDataStore.closePlayers()
        resetUndoRedo()
    }

    fun nextChapter() {
        navigateChapter(workbookDataStore.chapter.sort + 1)
    }

    fun previousChapter() {
        navigateChapter(workbookDataStore.chapter.sort - 1)
    }

    fun navigateChapter(chapter: Int) {
        selectedStepProperty.set(null)
        showAudioMissingViewProperty.set(false)

        workbookDataStore.workbook.target
            .chapters
            .filter { it.sort == chapter }
            .singleElement()
            .observeOnFx()
            .subscribe {
                loadChapter(it)
            }

        updateChapterSelector(chapter)
    }

    fun navigateStep(target: ChunkingStep) {
        if (!loadingStepProperty.value) {
            loadingStepProperty.set(true)
            runLater {
                selectedStepProperty.set(target)
            }
            resetUndoRedo()
        }
    }

    fun selectChunk(chunkNumber: Int) {
        resetUndoRedo()
        val chunk = workbookDataStore.chapter.chunks.value?.find { it.sort == chunkNumber } ?: return
        workbookDataStore.activeChunkProperty.set(chunk)

        audioDataStore.stopPlayers()
        audioDataStore.closePlayers()

        updateSourceText()
            .andThen {
                audioDataStore.updateSourceAudio()
                audioDataStore.openSourceAudioPlayer()
                it.onComplete()
            }
            .subscribeOn(Schedulers.io())
            .subscribe()
    }

    fun loadChunks(chunks: List<Chunk>) {
        val chunkViewData = chunks.map { chunk ->
            val completed = when(selectedStepProperty.value) {
                ChunkingStep.BLIND_DRAFT -> chunk.hasSelectedAudio()
                ChunkingStep.PEER_EDIT -> chunk.checkingStatus().ordinal >= CheckingStatus.PEER_EDIT.ordinal
                ChunkingStep.KEYWORD_CHECK -> chunk.checkingStatus().ordinal >= CheckingStatus.KEYWORD.ordinal
                ChunkingStep.VERSE_CHECK -> chunk.checkingStatus().ordinal >= CheckingStatus.VERSE.ordinal
                else -> false
            }
            ChunkViewData(
                chunk.sort,
                completed,
                selectedChunkBinding
            )
        }
        chunkList.setAll(chunkViewData)

        updateStep()
    }

    fun updateStep(callback: () -> Unit = {}) {
        compositeDisposable.clear()

        workbookDataStore.chapter
            .chunks
            .observeOnFx()
            .subscribe { list ->
                when {
                    list.isEmpty() -> {
                        reachableStepProperty.set(ChunkingStep.CHUNKING)
                    }
                    list.all { it.checkingStatus() == CheckingStatus.VERSE } -> {
                        reachableStepProperty.set(ChunkingStep.FINAL_REVIEW)
                    }
                    list.all { it.checkingStatus().ordinal >= CheckingStatus.KEYWORD.ordinal } -> {
                        reachableStepProperty.set(ChunkingStep.VERSE_CHECK)
                    }
                    list.all { it.checkingStatus().ordinal >= CheckingStatus.PEER_EDIT.ordinal } -> {
                        reachableStepProperty.set(ChunkingStep.KEYWORD_CHECK)
                    }
                    list.all { it.hasSelectedAudio() } -> {
                        reachableStepProperty.set(ChunkingStep.PEER_EDIT)
                    }
                    list.isNotEmpty() -> {
                        reachableStepProperty.set(ChunkingStep.BLIND_DRAFT)
                    }
                }
                callback()
            }.addTo(compositeDisposable)
    }

    fun updateSourceText() : Completable {
        sourceTextProperty.set(null)
        return workbookDataStore.getSourceText()
            .subscribeOn(Schedulers.io())
            .observeOnFx()
            .doOnSuccess {
                sourceTextProperty.set(it)
            }
            .ignoreElement()
    }

    fun goToNarration() {
        showAudioMissingViewProperty.set(false)
        creationUseCase
            .createAllBooks(
                workbookDataStore.workbook.source.language,
                workbookDataStore.workbook.source.language,
                ProjectMode.NARRATION
            )
            .subscribe {
                runLater {
                    navigator.home()
                }
            }
    }

    private fun loadChapter(chapter: Chapter) {
        workbookDataStore.activeChapterProperty.set(chapter)
        resetUndoRedo()
        updateSourceText().subscribe()

        val wb = workbookDataStore.workbook
        wb.target
            .chapters
            .count()
            .observeOnFx()
            .subscribe { count ->
                isFirstChapterProperty.set(chapter.sort == 1)
                isLastChapterProperty.set(chapter.sort.toLong() == count)
            }

        Maybe
            .fromCallable {
                wb.sourceAudioAccessor.getChapter(chapter.sort, wb.target)
            }
            .subscribeOn(Schedulers.io())
            .observeOnFx()
            .doOnSuccess {
                updateStep {
                    if (reachableStepProperty.value == ChunkingStep.CHUNKING) {
                        selectedStepProperty.set(ChunkingStep.CONSUME_AND_VERBALIZE)
                    } else {
                        selectedStepProperty.set(reachableStepProperty.value)
                    }
                }
            }
            .doOnComplete {
                showAudioMissingViewProperty.set(true)
                reachableStepProperty.set(null)
                compositeDisposable.clear()
            }
            .subscribe()
    }

    private fun resetUndoRedo() {
        canUndoProperty.set(false)
        canRedoProperty.set(false)
    }

    private fun updateChapterSelector(chapter: Int) {
        workbookDataStore.workbook.target
            .chapters
            .toList()
            .subscribe { chapters ->
                chapterList.setAll(
                    chapters.map {
                        ChapterGridItemData(
                            it.sort,
                            it.hasSelectedAudio(),
                            chapter == it.sort
                        )
                    }
                )
            }
    }
}