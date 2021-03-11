package org.wycliffeassociates.otter.common.domain.resourcecontainer.projectimportexport

import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import org.slf4j.LoggerFactory
import org.wycliffeassociates.otter.common.data.primitives.ResourceMetadata
import org.wycliffeassociates.otter.common.data.workbook.Workbook
import org.wycliffeassociates.otter.common.domain.resourcecontainer.project.ProjectFilesAccessor
import org.wycliffeassociates.otter.common.persistence.IDirectoryProvider
import org.wycliffeassociates.otter.common.persistence.repositories.IWorkbookRepository
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class ProjectExporter @Inject constructor(
    private val directoryProvider: IDirectoryProvider,
    private val workbookRepository: IWorkbookRepository
) {
    private val log = LoggerFactory.getLogger(this.javaClass)

    fun export(
        directory: File,
        projectMetadataToExport: ResourceMetadata,
        workbook: Workbook,
        projectFilesAccessor: ProjectFilesAccessor
    ): Single<ExportResult> {
        return Single
            .fromCallable {
                val projectSourceMetadata = workbook.source.linkedResources
                    .firstOrNull { it.identifier == projectMetadataToExport.identifier }
                    ?: workbook.source.resourceMetadata

                val projectToExportIsBook: Boolean =
                    projectMetadataToExport.identifier == workbook.target.resourceMetadata.identifier

                val zipFilename = makeExportFilename(workbook, projectSourceMetadata)
                val zipFile = directory.resolve(zipFilename)

                projectFilesAccessor.initializeResourceContainerInFile(workbook, zipFile)

                directoryProvider.newFileWriter(zipFile).use { fileWriter ->
                    projectFilesAccessor.copyTakeFiles(
                        fileWriter,
                        workbook,
                        workbookRepository,
                        projectToExportIsBook
                    )

                    val linkedResource = workbook.source.linkedResources
                        .firstOrNull { it.identifier == projectMetadataToExport.identifier }
                    projectFilesAccessor.copySourceFiles(fileWriter, linkedResource)
                    projectFilesAccessor.writeSelectedTakesFile(fileWriter, workbook, projectToExportIsBook)
                }

                return@fromCallable ExportResult.SUCCESS
            }
            .doOnError {
                log.error("Failed to export in-progress project", it)
            }
            .onErrorReturnItem(ExportResult.FAILURE)
            .subscribeOn(Schedulers.io())
    }

    private fun makeExportFilename(workbook: Workbook, metadata: ResourceMetadata): String {
        val lang = workbook.target.language.slug
        val resource = metadata.identifier
        val project = workbook.target.slug
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        return "$lang-$resource-$project-$timestamp.zip"
    }
}
