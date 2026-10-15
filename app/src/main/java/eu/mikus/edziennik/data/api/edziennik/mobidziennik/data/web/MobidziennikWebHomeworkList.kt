/*
 * Copyright (c) Kuba Szczodrzyński 2019-10-11.
 */

package eu.mikus.edziennik.data.api.edziennik.mobidziennik.data.web

import org.jsoup.Jsoup
import eu.mikus.edziennik.data.api.edziennik.mobidziennik.DataMobidziennik
import eu.mikus.edziennik.data.api.edziennik.mobidziennik.ENDPOINT_MOBIDZIENNIK_WEB_HOMEWORK
import eu.mikus.edziennik.data.api.edziennik.mobidziennik.data.MobidziennikWeb
import eu.mikus.edziennik.data.db.entity.Event
import eu.mikus.edziennik.data.db.entity.Metadata
import eu.mikus.edziennik.data.db.entity.SYNC_ALWAYS
import eu.mikus.edziennik.data.db.enums.MetadataType
import eu.mikus.edziennik.ext.fixWhiteSpaces
import eu.mikus.edziennik.utils.models.Date

/**
 * Homework list from /mobile/zadaniadomowe.
 *
 * The old homework list came from the /api/zrzutbazy dump, which is decommissioned.
 * This scraper reads the upcoming-homework page directly. Each item is a
 * <tr data-typ="zadanie" data-id="N"> header row followed by a
 * <tr class="rowRolling"> content row (Termin / Przedmiot / description).
 */
class MobidziennikWebHomeworkList(override val data: DataMobidziennik,
                                  override val lastSync: Long?,
                                  val onSuccess: (endpointId: Int) -> Unit
) : MobidziennikWeb(data, lastSync) {
    companion object {
        private const val TAG = "MobidziennikWebHomeworkList"

        // A homework item: header row + the following content row.
        private val ITEM = Regex(
                "<tr data-typ=\"zadanie\" data-id=\"(\\d+)\">(.*?)</tr>\\s*<tr class=\"rowRolling\">(.*?)</tr>",
                RegexOption.DOT_MATCHES_ALL
        )

        private val DATE = Regex("<b>Termin:</b>\\s*(?:[\\w.]+,\\s*)?([0-9]+)\\s+(\\w+)\\s+([0-9]{4})")
        private val SUBJECT = Regex("<b>Przedmiot:</b>\\s*(.*?)</p>", RegexOption.DOT_MATCHES_ALL)

        private val MONTHS = mapOf(
                "stycznia" to 1, "lutego" to 2, "marca" to 3, "kwietnia" to 4,
                "maja" to 5, "czerwca" to 6, "lipca" to 7, "sierpnia" to 8,
                "września" to 9, "października" to 10, "listopada" to 11, "grudnia" to 12
        )
    }

    init {
        webGet(TAG, "/mobile/zadaniadomowe") { text ->
            MobidziennikLuckyNumberExtractor(data, text)
            val clean = text.replace("&nbsp;", " ")

            for (m in ITEM.findAll(clean)) {
                val hwId = m.groupValues[1].toLongOrNull() ?: continue
                val body = m.groupValues[3]

                val d = DATE.find(body) ?: continue
                val day = d.groupValues[1].toIntOrNull() ?: continue
                val month = MONTHS[d.groupValues[2]] ?: continue
                val year = d.groupValues[3].toIntOrNull() ?: continue

                val subjectMatch = SUBJECT.find(body)
                val subjectName = Jsoup.parse(subjectMatch?.groupValues?.get(1) ?: "").text().fixWhiteSpaces()
                        .ifBlank { "Zadanie" }
                val subject = data.getSubject(null, subjectName)

                // Description lives in the page-zad-cnt block.
                val contentEl = Jsoup.parse(body).select("div.page-zad-cnt").first()
                val description = contentEl?.text()?.fixWhiteSpaces() ?: ""

                val topic = buildString {
                    append(subjectName)
                    if (description.isNotEmpty()) append(": $description")
                }

                val eventObject = Event(
                        profileId = profileId,
                        id = hwId,
                        date = Date(year, month, day), time = null,
                        topic = topic,
                        color = null,
                        type = Event.TYPE_HOMEWORK,
                        teacherId = -1,
                        subjectId = subject.id,
                        teamId = data.teamClass?.id ?: -1
                )

                data.eventList.add(eventObject)
                data.metadataList.add(
                        Metadata(
                                profileId,
                                MetadataType.EVENT,
                                eventObject.id,
                                profile?.empty ?: false,
                                profile?.empty ?: false
                        ))
            }

            data.setSyncNext(ENDPOINT_MOBIDZIENNIK_WEB_HOMEWORK, SYNC_ALWAYS)
            onSuccess(ENDPOINT_MOBIDZIENNIK_WEB_HOMEWORK)
        }
    }
}
