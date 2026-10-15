/*
 * Copyright (c) Kuba Szczodrzyński 2019-10-11.
 */

package eu.mikus.edziennik.data.api.edziennik.mobidziennik.data.web

import org.jsoup.Jsoup
import eu.mikus.edziennik.data.api.edziennik.mobidziennik.DataMobidziennik
import eu.mikus.edziennik.data.api.edziennik.mobidziennik.ENDPOINT_MOBIDZIENNIK_WEB_NOTICES
import eu.mikus.edziennik.data.api.edziennik.mobidziennik.data.MobidziennikWeb
import eu.mikus.edziennik.data.db.entity.Metadata
import eu.mikus.edziennik.data.db.entity.Notice
import eu.mikus.edziennik.data.db.entity.SYNC_ALWAYS
import eu.mikus.edziennik.data.db.enums.MetadataType
import eu.mikus.edziennik.ext.fixWhiteSpaces
import eu.mikus.edziennik.utils.Utils.crc16
import eu.mikus.edziennik.utils.models.Date

class MobidziennikWebNotices(override val data: DataMobidziennik,
                             override val lastSync: Long?,
                             val onSuccess: (endpointId: Int) -> Unit
) : MobidziennikWeb(data, lastSync) {
    companion object {
        private const val TAG = "MobidziennikWebNotices"

        // /mobile/zachowanie renders each note as a block of <p><b>Label:</b> value</p>.
        // One regex captures a whole note (type + content + category + written-date).
        private val NOTE = Regex(
                "<b>Uwaga:</b>\\s*(.+?)\\s*</p>" +
                        "\\s*<p><b>Treść:</b>\\s*(.*?)</p>" +
                        "\\s*<p><b>Kategoria:</b>\\s*(.*?)</p>" +
                        "\\s*<p><b>Wpisano:</b>\\s*(?:[\\w.]+,\\s*)?([0-9]+)\\s+(\\w+)\\s+([0-9]{4})",
                RegexOption.DOT_MATCHES_ALL
        )

        private val MONTHS = mapOf(
                "stycznia" to 1, "lutego" to 2, "marca" to 3, "kwietnia" to 4,
                "maja" to 5, "czerwca" to 6, "lipca" to 7, "sierpnia" to 8,
                "września" to 9, "października" to 10, "listopada" to 11, "grudnia" to 12
        )

        // School year: semester 1 = Sep..Jan, semester 2 = Feb..Aug.
        private fun semesterFor(month: Int) = if (month in 9..12 || month == 1) 1 else 2
    }

    init {
        webGet(TAG, "/mobile/zachowanie") { text ->
            MobidziennikLuckyNumberExtractor(data, text)

            for (m in NOTE.findAll(text)) {
                val type = m.groupValues[1].trim().fixWhiteSpaces()
                val content = Jsoup.parse(m.groupValues[2]).text().fixWhiteSpaces()
                val category = m.groupValues[3].trim().fixWhiteSpaces()
                val day = m.groupValues[4].toIntOrNull() ?: continue
                val month = MONTHS[m.groupValues[5]] ?: continue
                val year = m.groupValues[6].toIntOrNull() ?: continue

                val noticeType = when (type.lowercase()) {
                    "pozytywna" -> Notice.TYPE_POSITIVE
                    "negatywna", "uwaga" -> Notice.TYPE_NEGATIVE
                    else -> Notice.TYPE_NEUTRAL
                }

                val noteText = buildString {
                    append(content)
                    if (category.isNotEmpty()) append("\nKategoria: $category")
                }.ifBlank { type }

                val addedDate = Date(year, month, day).inMillis
                val id = crc16("$year-$month-$day|$type|$content".toByteArray()).toLong()

                data.noticeList.add(
                        Notice(
                                profileId = profileId,
                                id = id,
                                type = noticeType,
                                semester = semesterFor(month),
                                text = noteText,
                                category = category.ifBlank { null },
                                points = null,
                                teacherId = -1,
                                addedDate = addedDate
                        ))
                data.metadataList.add(
                        Metadata(
                                profileId,
                                MetadataType.NOTICE,
                                id,
                                profile?.empty ?: false,
                                profile?.empty ?: false
                        ))
            }

            data.setSyncNext(ENDPOINT_MOBIDZIENNIK_WEB_NOTICES, SYNC_ALWAYS)
            onSuccess(ENDPOINT_MOBIDZIENNIK_WEB_NOTICES)
        }
    }
}
