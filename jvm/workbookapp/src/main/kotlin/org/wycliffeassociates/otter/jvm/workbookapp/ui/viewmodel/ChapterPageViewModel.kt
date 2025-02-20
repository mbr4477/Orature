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
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import javafx.beans.binding.Bindings
import javafx.beans.binding.StringBinding
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import org.slf4j.LoggerFactory
import org.wycliffeassociates.otter.common.domain.audio.OratureAudioFile
import org.wycliffeassociates.otter.common.data.workbook.AssociatedAudio
import org.wycliffeassociates.otter.common.data.workbook.Chapter
import org.wycliffeassociates.otter.common.data.workbook.Take
import org.wycliffeassociates.otter.common.device.IAudioPlayer
import org.wycliffeassociates.otter.common.data.audio.VerseMarker
import org.wycliffeassociates.otter.common.domain.content.*
import org.wycliffeassociates.otter.common.persistence.repositories.IAppPreferencesRepository
import org.wycliffeassociates.otter.common.persistence.repositories.PluginType
import org.wycliffeassociates.otter.jvm.workbookapp.di.IDependencyGraphProvider
import org.wycliffeassociates.otter.jvm.workbookapp.plugin.PluginClosedEvent
import org.wycliffeassociates.otter.jvm.workbookapp.plugin.PluginOpenedEvent
import org.wycliffeassociates.otter.jvm.workbookapp.ui.NavigationMediator
import org.wycliffeassociates.otter.jvm.workbookapp.ui.model.CardData
import org.wycliffeassociates.otter.jvm.workbookapp.ui.model.TakeModel
import org.wycliffeassociates.otter.jvm.workbookapp.ui.screens.RecordScripturePage
import tornadofx.*
import java.io.File
import java.util.concurrent.Callable
import javax.inject.Inject
import org.wycliffeassociates.otter.common.persistence.IDirectoryProvider


class ChapterPageViewModel : ViewModel() {

    private val logger = LoggerFactory.getLogger(ChapterPageViewModel::class.java)

    val workbookDataStore: WorkbookDataStore by inject()
    val audioDataStore: AudioDataStore by inject()
    val appPreferencesStore: AppPreferencesStore by inject()
    val audioPluginViewModel: AudioPluginViewModel by inject()

    @Inject
    lateinit var resetChunks: ResetChunks

    @Inject
    lateinit var directoryProvider: IDirectoryProvider

    @Inject
    lateinit var concatenateAudio: ConcatenateAudio

    @Inject
    lateinit var appPreferencesRepo: IAppPreferencesRepository

    @Inject
    lateinit var createChunks: CreateChunks

    // List of content to display on the screen
    // Boolean tracks whether the content has takes associated with it
    private val allContent: ObservableList<CardData> = FXCollections.observableArrayList()
    val filteredContent: ObservableList<CardData> = FXCollections.observableArrayList()

    private var loading: Boolean by property(false)
    val loadingProperty = getProperty(ChapterPageViewModel::loading)

    val canCompileProperty = SimpleBooleanProperty()
    val isCompilingProperty = SimpleBooleanProperty()
    val selectedChapterTakeProperty = SimpleObjectProperty<Take>()

    /**
     * WorkChunk is a first chunk to work with when you Begin Translation
     * or a next chunk with no takes when you Continue Translation
     */
    val workChunkProperty = SimpleObjectProperty<CardData>()
    val noTakesProperty = SimpleBooleanProperty()

    val chapterCardProperty = SimpleObjectProperty<CardData>()
    val contextProperty = SimpleObjectProperty(PluginType.RECORDER)

    val sourceTextZoomRateProperty = SimpleIntegerProperty()

    val showExportProgressDialogProperty = SimpleBooleanProperty(false)

    val snackBarObservable: PublishSubject<String> = PublishSubject.create()

    private val disposables = CompositeDisposable()
    private val navigator: NavigationMediator by inject()

    init {
        (app as IDependencyGraphProvider).dependencyGraph.inject(this)

        audioPluginViewModel.pluginNameProperty.bind(pluginNameBinding())

        filteredContent.onChange {
            checkCanCompile()
        }

        sourceTextZoomRateProperty.bindBidirectional(appPreferencesStore.sourceTextZoomRateProperty)
    }

    fun dock() {
        chapterCardProperty.set(CardData(workbookDataStore.chapter))
        workbookDataStore.activeChapterProperty.value?.let { chapter ->
            updateLastSelectedChapter(chapter.sort)
            loadChapterContents(chapter).subscribe()
            val chap = CardData(chapter)
            chapterCardProperty.set(chap)
            subscribeSelectedTakePropertyToRelay(chapter.audio)
        }
        appPreferencesRepo.sourceTextZoomRate().subscribe { rate ->
            sourceTextZoomRateProperty.set(rate)
        }.let { disposables.add(it) }
        checkCanCompile()
    }

    fun undock() {
        selectedChapterTakeProperty.set(null)
        audioDataStore.selectedChapterPlayerProperty.set(null)

        filteredContent.clear()
        allContent.clear()
        disposables.clear()
    }

    fun onCardSelection(cardData: CardData) {
        cardData.chapterSource?.let {
            workbookDataStore.activeChapterProperty.set(it)
        }
        // Chunk will be null if the chapter recording is opened. This needs to happen to update the recordable to
        // use the chapter recordable.
        workbookDataStore.activeChunkProperty.set(cardData.chunkSource)
    }

    fun openPlayers() {
        audioDataStore.openPlayers()
    }

    fun closePlayers() {
        audioDataStore.closePlayers()
    }

    fun checkCanCompile() {
        val hasUnselected = filteredContent.any { chunk ->
            chunk.chunkSource?.audio?.selected?.value?.value == null
        }.or(filteredContent.isEmpty())

        canCompileProperty.set(hasUnselected.not())
    }

    fun setWorkChunk() {
        if (filteredContent.isEmpty()) {
            return
        }

        val hasTakes = filteredContent.any { chunk ->
            chunk.chunkSource?.audio?.getAllTakes()
                ?.any { it.deletedTimestamp.value?.value == null } ?: false
        }

        if (hasTakes) {
            val notSelected = filteredContent
                .firstOrNull { chunk ->
                    chunk.chunkSource?.audio?.selected?.value?.value == null
                } ?: filteredContent.last()
            noTakesProperty.set(false)
            workChunkProperty.set(notSelected)
        } else {
            noTakesProperty.set(true)
            workChunkProperty.set(filteredContent.first())
        }
    }

    fun setSelectedChapterTake(take: Take? = null) {
        selectedChapterTakeProperty.set(take)
        openPlayers()
    }

    fun recordChapter() {
        chapterCardProperty.value?.chapterSource?.let { rec ->
            contextProperty.set(PluginType.RECORDER)
            val workbook = workbookDataStore.workbook
            val updateOnSuccess = workbook.projectFilesAccessor.updateSelectedTakesFile(workbook)

            rec.audio.getNewTakeNumber()
                .flatMapMaybe { takeNumber ->
                    workbookDataStore.activeTakeNumberProperty.set(takeNumber)
                    audioPluginViewModel.getPlugin(PluginType.RECORDER)
                }
                .flatMapSingle { plugin ->
                    fire(PluginOpenedEvent(PluginType.RECORDER, plugin.isNativePlugin()))
                    audioPluginViewModel.record(rec)
                }
                .observeOnFx()
                .doOnError { e ->
                    logger.error("Error in recording a new take", e)
                }
                .onErrorReturn { PluginActions.Result.NO_PLUGIN }
                .subscribe { result: PluginActions.Result ->
                    fire(PluginClosedEvent(PluginType.RECORDER))
                    when (result) {
                        PluginActions.Result.NO_PLUGIN -> snackBarObservable.onNext(messages["noRecorder"])
                        PluginActions.Result.SUCCESS -> {
                            updateOnSuccess.subscribe()
                        }

                        PluginActions.Result.NO_AUDIO -> {
                            /* no-op */
                        }
                    }
                }
        } ?: throw IllegalStateException("Recordable is null")
    }

    fun processTakeWithPlugin(pluginType: PluginType) {
        selectedChapterTakeProperty.value?.let { take ->
            val audio = chapterCardProperty.value!!.chapterSource!!.audio
            contextProperty.set(pluginType)
            workbookDataStore.activeTakeNumberProperty.set(take.number)

            audioPluginViewModel
                .getPlugin(pluginType)
                .doOnError { e ->
                    logger.error("Error in processing take with plugin type: $pluginType, ${e.message}")
                }
                .flatMapSingle { plugin ->
                    fire(PluginOpenedEvent(pluginType, plugin.isNativePlugin()))
                    when (pluginType) {
                        PluginType.EDITOR -> audioPluginViewModel.edit(audio, take)
                        PluginType.MARKER -> audioPluginViewModel.mark(audio, take)
                        else -> null
                    }
                }
                .observeOnFx()
                .doOnError { e ->
                    logger.error("Error in processing take with plugin type: $pluginType - $e")
                }
                .onErrorReturn { PluginActions.Result.NO_PLUGIN }
                .subscribe { result: PluginActions.Result ->
                    fire(PluginClosedEvent(pluginType))
                    when (result) {
                        PluginActions.Result.NO_PLUGIN -> snackBarObservable.onNext(messages["noEditor"])
                        else -> {
                            when (pluginType) {
                                PluginType.EDITOR, PluginType.MARKER -> {
                                    /* no-op */
                                }

                                else -> {}
                            }
                        }
                    }
                }
        }
    }

    fun compile() {
        canCompileProperty.value?.let {
            if (!it) return

            isCompilingProperty.set(true)

            val chapter = chapterCardProperty.value!!.chapterSource!!
            val takes = filteredContent.mapNotNull { chunk ->
                chunk.chunkSource?.audio?.selected?.value?.value?.file
            }

            var compiled: File? = null

            // Don't place verse markers if the draft comes from user chunks
            val shouldIncludeMarkers = filteredContent.any { it.chunkSource?.label?.lowercase() == "chunk" }.not()
            concatenateAudio.execute(takes, shouldIncludeMarkers)
                .doOnSuccess {
                    logger.info("Chapter compiled successfully.")
                }
                .flatMapCompletable { file ->
                    compiled = file
                    audioPluginViewModel.import(chapter, file)
                }
                .subscribeOn(Schedulers.io())
                .doOnError { e ->
                    logger.error("Error in compiling chapter: $chapter", e)
                }
                .observeOnFx()
                .doFinally {
                    isCompilingProperty.set(false)
                    compiled?.delete()
                }
                .subscribe()
        }
    }

    fun dialogTitleBinding(): StringBinding {
        return Bindings.createStringBinding(
            Callable {
                String.format(
                    messages["sourceDialogTitle"],
                    workbookDataStore.activeTakeNumberProperty.value,
                    audioPluginViewModel.pluginNameProperty.value
                )
            },
            audioPluginViewModel.pluginNameProperty,
            workbookDataStore.activeTakeNumberProperty
        )
    }

    fun dialogTextBinding(): StringBinding {
        return Bindings.createStringBinding(
            Callable {
                String.format(
                    messages["sourceDialogMessage"],
                    workbookDataStore.activeTakeNumberProperty.value,
                    audioPluginViewModel.pluginNameProperty.value,
                    audioPluginViewModel.pluginNameProperty.value
                )
            },
            audioPluginViewModel.pluginNameProperty,
            workbookDataStore.activeTakeNumberProperty
        )
    }

    fun pluginNameBinding(): StringBinding {
        return Bindings.createStringBinding(
            Callable {
                when (contextProperty.value) {
                    PluginType.RECORDER -> {
                        audioPluginViewModel.selectedRecorderProperty.value?.name
                    }

                    PluginType.EDITOR -> {
                        audioPluginViewModel.selectedEditorProperty.value?.name
                    }

                    PluginType.MARKER -> {
                        audioPluginViewModel.selectedMarkerProperty.value?.name
                    }

                    null -> throw IllegalStateException("Action is not supported!")
                }
            },
            contextProperty,
            audioPluginViewModel.selectedRecorderProperty,
            audioPluginViewModel.selectedEditorProperty,
            audioPluginViewModel.selectedMarkerProperty
        )
    }

    private fun updateLastSelectedChapter(chapterNumber: Int) {
        val workbookHash = workbookDataStore.workbook.hashCode()
        workbookDataStore.workbookRecentChapterMap[workbookHash] = chapterNumber - 1
    }

    private fun loadChapterContents(chapter: Chapter): Observable<CardData> {
        // Remove existing content so the user knows they are outdated
        allContent.clear()
        loading = true
        return chapter.chunks
            .flatMap {
                Observable.fromIterable(it)
            }
            .map { CardData(it) }
            .map {
                buildTakes(it)
                it.player = getPlayer()
                it.onChunkOpen = ::onChunkOpen
                it.onTakeSelected = ::onTakeSelected
                it
            }
            .doOnComplete {
                loading = false
            }
            .observeOnFx()
            .doOnError { e ->
                logger.error("Error in loading chapter contents for chapter: $chapter", e)
            }
            .map { cardData ->
                if (cardData.chunkSource != null) {
                    if (cardData.chunkSource.draftNumber > 0) {
                        if (filteredContent.find { cont -> cardData.sort == cont.sort } == null) {
                            filteredContent.add(cardData)
                        }
                    }
                } else {
                    filteredContent.add(cardData)
                }

                filteredContent.removeIf { card ->
                    card.chunkSource != null && (card.chunkSource.draftNumber < 0 || card.chunkSource.bridged)
                }
                filteredContent.sortBy { it.sort }
                setWorkChunk()
                cardData
            }
            .observeOnFx()
    }

    private fun subscribeSelectedTakePropertyToRelay(audio: AssociatedAudio) {
        audio
            .selected
            .doOnError { e ->
                logger.error("Error in subscribing take to relay for audio: $audio", e)
            }
            .observeOnFx()
            .subscribe { takeHolder ->
                takeHolder.value?.let {
                    logger.info("Setting selected chapter take to ${takeHolder.value?.name}")
                    setSelectedChapterTake(takeHolder.value)
                    audioDataStore.updateSelectedChapterPlayer()
                }
            }
            .let { disposables.add(it) }
    }

    private fun onChunkOpen(chunk: CardData) {
        onCardSelection(chunk)
        navigator.dock<RecordScripturePage>()
    }

    private fun onTakeSelected(chunk: CardData, take: TakeModel) {
        chunk.chunkSource?.audio?.selectTake(take.take)
        val workbook = workbookDataStore.workbook
        workbook.projectFilesAccessor.updateSelectedTakesFile(workbook).subscribe()
        take.take.file.setLastModified(System.currentTimeMillis())
        buildTakes(chunk)
    }

    private fun buildTakes(chunkData: CardData) {
        chunkData.takes.clear()
        chunkData.chunkSource?.let { chunk ->
            val selected = chunk.audio.selected.value?.value
            chunk.audio.takes
                .filter { it.deletedTimestamp.value?.value == null }
                .filter { it.file.exists() }
                .map { take ->
                    setMarker(VerseMarker(chunk.start, chunk.end, 0), take)
                    take.mapToModel(take == selected)
                }.subscribe {
                    chunkData.takes.addAll(it)
                }.let {
                    disposables.add(it)
                }
        }
    }

    private fun setMarker(marker: VerseMarker, take: Take) {
        OratureAudioFile(take.file).apply {
            if (getCues().isEmpty()) {
                addMarker<VerseMarker>(marker)
                update()
            }
        }
    }

    private fun Take.mapToModel(selected: Boolean): TakeModel {
        val audioPlayer = getPlayer()
        return TakeModel(this, selected, false, audioPlayer)
    }

    private fun getPlayer(): IAudioPlayer {
        return (app as IDependencyGraphProvider).dependencyGraph.injectPlayer()
    }

    fun createChunksFromVerses() {
        val wkbk = workbookDataStore.activeWorkbookProperty.value
        val chapter = workbookDataStore.activeChapterProperty.value

        createChunks.createChunksFromVerses(wkbk, chapter, 1)
            .subscribe()
    }

    fun resetChapter() {
        closePlayers()
        filteredContent.clear()
        val chapter = workbookDataStore.activeChapterProperty.value
        resetChunks.resetChapter(workbookDataStore.workbook.projectFilesAccessor, chapter)
            .subscribe()
        audioDataStore.updateSourceAudio()
    }
}
