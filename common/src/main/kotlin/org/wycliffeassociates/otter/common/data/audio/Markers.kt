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
package org.wycliffeassociates.otter.common.data.audio

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.wycliffeassociates.otter.common.audio.AudioCue

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
    defaultImpl = VerseMarker::class
)
@JsonSubTypes(
    JsonSubTypes.Type(value = VerseMarker::class, name = "verse_marker"),
    JsonSubTypes.Type(value = ChapterMarker::class, name = "chapter_marker"),
    JsonSubTypes.Type(value = BookMarker::class, name = "book_marker"),
    JsonSubTypes.Type(value = UnknownMarker::class, name = "unknown_marker")
)
interface AudioMarker {
    /**
     * The marker label which does not contain any namespacing, most often a verse number or verse range
     */
    val label: String
    val location: Int

    /**
     * The marker label with the appropriate namespacing (such as "orature-vm-{number}"
     */
    val formattedLabel: String

    fun toCue(): AudioCue {
        return AudioCue(location, formattedLabel)
    }

    fun clone(): AudioMarker
}

data class UnknownMarker(override val location: Int, override val label: String) : AudioMarker {
    constructor(cue: AudioCue) : this(cue.location, cue.label)

    override val formattedLabel
        get() = label

    override fun toString(): String {
        return formattedLabel
    }

    override fun clone(): UnknownMarker {
        return copy()
    }
}

data class BookMarker(val bookSlug: String, override val location: Int) : AudioMarker {
    override val label: String
        @JsonIgnore
        get() = bookSlug

    override val formattedLabel
        @JsonIgnore
        get() = "orature-book-${label}"

    override fun toString(): String {
        return formattedLabel
    }

    override fun clone(): BookMarker {
        return copy()
    }
}

data class ChapterMarker(val chapterNumber: Int, override val location: Int) : AudioMarker {
    override val label: String
        @JsonIgnore
        get() = "$chapterNumber"

    override val formattedLabel
        @JsonIgnore
        get() = "orature-chapter-${label}"

    override fun toString(): String {
        return formattedLabel
    }

    override fun clone(): ChapterMarker {
        return copy()
    }
}

data class VerseMarker(val start: Int, val end: Int, override val location: Int) : AudioMarker {

    override val label: String
        @JsonIgnore
        get() = if (end != start) "$start-$end" else "$start"

    override val formattedLabel
        @JsonIgnore
        get() = "orature-vm-${label}"

    override fun toString(): String {
        return formattedLabel
    }

    override fun clone(): VerseMarker {
        return copy()
    }
}

data class ChunkMarker(val chunk: Int, override val location: Int) : AudioMarker {
    override val label = "$chunk"
    override val formattedLabel
        get() = "orature-chunk-${label}"

    override fun toString(): String {
        return formattedLabel
    }

    override fun clone(): ChunkMarker {
        return copy()
    }
}