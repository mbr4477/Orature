package org.wycliffeassociates.otter.common.data.model

import java.io.File
import java.time.ZonedDateTime

data class Take(
        var filename: String,
        var path: File,
        var number: Int,
        var timestamp: ZonedDateTime,
        var played: Boolean,
        var markers: List<Marker>,
        var id: Int = 0
)