package org.wycliffeassociates.otter.common.domain.project.importer

import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import org.slf4j.LoggerFactory
import org.wycliffeassociates.otter.common.collections.OtterTree
import org.wycliffeassociates.otter.common.data.primitives.CollectionOrContent
import org.wycliffeassociates.otter.common.domain.resourcecontainer.ImportException
import org.wycliffeassociates.otter.common.domain.resourcecontainer.ImportResult
import org.wycliffeassociates.otter.common.domain.resourcecontainer.OtterResourceContainerConfig
import org.wycliffeassociates.otter.common.domain.resourcecontainer.castOrFindImportException
import org.wycliffeassociates.otter.common.domain.resourcecontainer.project.IProjectReader
import org.wycliffeassociates.otter.common.domain.resourcecontainer.project.IZipEntryTreeBuilder
import org.wycliffeassociates.otter.common.domain.resourcecontainer.project.VersificationTreeBuilder
import org.wycliffeassociates.otter.common.domain.resourcecontainer.toCollection
import org.wycliffeassociates.otter.common.persistence.IDirectoryProvider
import org.wycliffeassociates.otter.common.persistence.repositories.IResourceContainerRepository
import org.wycliffeassociates.otter.common.persistence.repositories.IResourceMetadataRepository
import org.wycliffeassociates.otter.common.persistence.repositories.IVersificationRepository
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File
import java.io.IOException
import javax.inject.Inject

class NewSourceImporter @Inject constructor(
    private val directoryProvider: IDirectoryProvider,
    private val resourceContainerRepository: IResourceContainerRepository,
    resourceMetadataRepository: IResourceMetadataRepository,
    private val versificationRepository: IVersificationRepository,
    private val zipEntryTreeBuilder: IZipEntryTreeBuilder
) : RCImporter(directoryProvider, resourceMetadataRepository) {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    override fun import(
        file: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions?
    ): Single<ImportResult> {
        return importContainer(file, callback)
    }

    private fun importContainer(
        file: File,
        callback: ProjectImporterCallback?
    ): Single<ImportResult> {
        return Single.create<ImportResult> { emitter ->
            logger.info("Importing RC...")
            val fileToImport = prepareFileToImport(file)

            val container = try {
                ResourceContainer.load(fileToImport, OtterResourceContainerConfig())
            } catch (e: Exception) {
                logger.error("Error loading rc in importFromInternalDir, file: $fileToImport", e)
                cleanUp(fileToImport, ImportResult.LOAD_RC_ERROR).subscribe(emitter::onSuccess)
                return@create
            }

            val tree = try {
                IProjectReader.constructContainerTree(container, zipEntryTreeBuilder)
            } catch (e: ImportException) {
                logger.error("Error constructing container tree, file: $fileToImport", e)
                logger.error("Container had format: ${container.manifest.dublinCore.format}")
                container.close()
                cleanUp(fileToImport, e.result).subscribe(emitter::onSuccess)
                return@create
            }
            callback?.onNotifyProgress(
                localizeKey = "loadingSomething", message = "${file.name}"
            )

            val preallocationTree = OtterTree<CollectionOrContent>(container.toCollection())
            val versificationTree = VersificationTreeBuilder(versificationRepository)
                .build(container)
                ?.apply {
                    for (node in this) {
                        preallocationTree.addChild(node)
                    }
                }

            if (versificationTree != null) {
                importTree(container, preallocationTree, fileToImport)
                    .flatMap {
                        updateContentFromTextContent(container, tree)
                    }
                    .subscribe(emitter::onSuccess)
            } else { // No versification found, just import the tree from the parsed text
                importTree(container, tree, fileToImport)
                    .subscribe(emitter::onSuccess)
            }
        }.onErrorReturn { e ->
            logger.error("Error in importContainer, file: $file", e)
            e.castOrFindImportException()?.result ?: throw e
        }.subscribeOn(Schedulers.io())
    }

    private fun prepareFileToImport(file: File): File {
        var exists = false
        val internalDir = getInternalDirectory(file) ?: throw ImportException(ImportResult.LOAD_RC_ERROR)
        if (internalDir.exists()) {
            val rcFileExists = file.isFile && internalDir.contains(file.name)
            val rcDirExists = file.isDirectory && internalDir.listFiles().isNotEmpty()
            if (rcFileExists || rcDirExists) {
                exists = true
            }
        }
        return if (exists) {
            file
        } else {
            copyToInternalDirectory(file, internalDir)
        }
    }

    private fun getInternalDirectory(file: File): File? {
        // Load the external container to get the metadata we need to figure out where to copy to
        val extContainer = try {
            ResourceContainer.load(file, OtterResourceContainerConfig())
        } catch (e: Exception) {
            // Could be checked or unchecked exception from RC library
            logger.error("Error in getInternalDirectory, file: $file", e)
            return null
        }
        return directoryProvider.getSourceContainerDirectory(extContainer)
    }

    private fun cleanUp(container: File, result: ImportResult): Single<ImportResult> = Single.fromCallable {
        container.deleteRecursively()
        return@fromCallable result
    }

    private fun importTree(
        container: ResourceContainer,
        tree: OtterTree<CollectionOrContent>,
        fileToLoad: File
    ): Single<ImportResult> {
        return resourceContainerRepository
            .importResourceContainer(container, tree, container.manifest.dublinCore.language.identifier)
            .doOnEvent { result, err ->
                if (err != null) {
                    logger.error("Error in importFromInternalDirectory importing rc, file: $fileToLoad", err)
                }
                if (result != ImportResult.SUCCESS || err != null) fileToLoad.deleteRecursively()
            }
    }

    private fun updateContentFromTextContent(
        container: ResourceContainer,
        tree: OtterTree<CollectionOrContent>
    ): Single<ImportResult> {
        return resourceContainerRepository
            .updateContent(
                container,
                tree
            )
    }

    private fun copyToInternalDirectory(file: File, destinationDirectory: File): File {
        return if (file.isDirectory) {
            copyRecursivelyToInternalDirectory(file, destinationDirectory)
        } else {
            copyFileToInternalDirectory(file, destinationDirectory)
        }
    }

    private fun copyRecursivelyToInternalDirectory(filepath: File, destinationDirectory: File): File {
        // Copy the resource container into the correct directory
        if (filepath.absoluteFile != destinationDirectory) {
            val success = filepath.copyRecursively(destinationDirectory, true)
            if (!success) {
                throw IOException("Could not copy resource container ${filepath.name} to resource container directory")
            }
        }
        return destinationDirectory
    }

    private fun File.contains(name: String): Boolean {
        if (!this.isDirectory) {
            throw Exception("Cannot call contains on non-directory file")
        }
        return this.listFiles().map { it.name }.contains(name)
    }

    private fun copyFileToInternalDirectory(filepath: File, destinationDirectory: File): File {
        // Copy the resource container zip file into the correct directory
        val destinationFile = File(destinationDirectory, filepath.name)
        if (filepath.absoluteFile != destinationFile) {
            filepath.copyTo(destinationFile, true)
            val success = destinationDirectory.contains(filepath.name)
            if (!success) {
                throw IOException("Could not copy resource container ${filepath.name} to resource container directory")
            }
        }
        return destinationFile
    }
}