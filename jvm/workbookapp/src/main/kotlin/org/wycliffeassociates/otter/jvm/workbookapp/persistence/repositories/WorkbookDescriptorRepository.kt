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
package org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories

import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import org.slf4j.LoggerFactory
import org.wycliffeassociates.otter.common.data.primitives.Collection
import org.wycliffeassociates.otter.common.data.workbook.WorkbookDescriptor
import org.wycliffeassociates.otter.common.domain.resourcecontainer.SourceAudioAccessor
import org.wycliffeassociates.otter.common.persistence.repositories.ICollectionRepository
import org.wycliffeassociates.otter.common.persistence.repositories.IContentRepository
import org.wycliffeassociates.otter.common.persistence.repositories.IWorkbookDescriptorRepository
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.database.AppDatabase
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.WorkbookDescriptorEntity
import javax.inject.Inject

class WorkbookDescriptorRepository @Inject constructor(
    database: AppDatabase,
    private val collectionRepository: ICollectionRepository,
    private val contentRepository: IContentRepository
) : IWorkbookDescriptorRepository {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val workbookDescriptorDao = database.workbookDescriptorDao
    private val workbookTypeDao = database.workbookTypeDao

    override fun getById(id: Int): Maybe<WorkbookDescriptor> {
        return Maybe
            .fromCallable {
                workbookDescriptorDao.fetchById(id)
            }
            .map {
                buildWorkbookDescriptor(it)
            }
            .subscribeOn(Schedulers.io())
    }

    override fun getAll(): Single<List<WorkbookDescriptor>> {
        return Single
            .fromCallable {
                workbookDescriptorDao.fetchAll()
                    .map {
                        buildWorkbookDescriptor(it)
                    }
            }
            .subscribeOn(Schedulers.io())
            .doOnError {
                logger.error("Error getting workbook descriptors.", it)
            }
    }

    override fun delete(list: List<WorkbookDescriptor>): Completable {
        return Completable
            .fromAction {
                list
                    .map(::mapToEntity)
                    .forEach {
                        workbookDescriptorDao.delete(it)
                    }
            }
            .subscribeOn(Schedulers.io())
            .doOnError {
                logger.error("Error deleting workbook descriptors.", it)
            }
    }

    private fun buildWorkbookDescriptor(entity: WorkbookDescriptorEntity): WorkbookDescriptor {
        val targetCollection = collectionRepository.getProject(entity.targetFk).blockingGet()
        val sourceCollection = collectionRepository.getProject(entity.sourceFk).blockingGet()
        val progress = getProgress(targetCollection)
        val hasSourceAudio = SourceAudioAccessor.hasSourceAudio(
            sourceCollection.resourceContainer!!,
            sourceCollection.slug
        )
        val mode = workbookTypeDao.fetchById(entity.typeFk)!!

        return WorkbookDescriptor(
            entity.id,
            sourceCollection,
            targetCollection,
            mode,
            progress,
            hasSourceAudio
        )
    }

    private fun getProgress(collection: Collection): Double {
        val chapters = collectionRepository.getChildren(collection)
            .flattenAsObservable { it }
            .flatMapSingle { chapter ->
                contentRepository.getCollectionMetaContent(chapter)
            }
            .blockingIterable().toList()

        return chapters.count { it.selectedTake != null }.toDouble() / chapters.size
    }

    private fun mapToEntity(obj: WorkbookDescriptor): WorkbookDescriptorEntity {
        return WorkbookDescriptorEntity(
            obj.id,
            obj.sourceCollection.id,
            obj.targetCollection.id,
            workbookTypeDao.fetchId(obj.mode)
        )
    }
}