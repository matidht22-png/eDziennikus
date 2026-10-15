package eu.mikus.edziennik.data.api.edziennik.mobidziennik.firstlogin

import org.greenrobot.eventbus.EventBus
import eu.mikus.edziennik.data.api.edziennik.mobidziennik.DataMobidziennik
import eu.mikus.edziennik.data.api.edziennik.mobidziennik.data.MobidziennikWeb
import eu.mikus.edziennik.data.api.edziennik.mobidziennik.login.MobidziennikLoginWeb
import eu.mikus.edziennik.data.api.events.FirstLoginFinishedEvent
import eu.mikus.edziennik.data.db.entity.Profile
import eu.mikus.edziennik.data.db.enums.LoginType
import eu.mikus.edziennik.ext.fixName
import eu.mikus.edziennik.ext.set
import java.util.regex.Pattern

class MobidziennikFirstLogin(val data: DataMobidziennik, val onSuccess: () -> Unit) {
    companion object {
        private const val TAG = "MobidziennikFirstLogin"

        // The old /api/zrzutbazy "database dump" endpoint was decommissioned by the
        // mobiDziennik servers (it now redirects to forbidden.jpg). Student identity is
        // instead read from the live /dziennik/frekwencja page, which lists the logged-in
        // student(s) as <option value="STUDENT_ID" selected>SURNAME NAME</option>.
        private val STUDENT_OPTION = Pattern.compile(
                "<option\\s+value=\"(\\d+)\"\\s+selected[^>]*>([^<]+)</option>",
                Pattern.CASE_INSENSITIVE
        )
    }

    private val web = MobidziennikWeb(data, null)
    private val profileList = mutableListOf<Profile>()

    init {
        var firstProfileId = data.loginStore.id

        MobidziennikLoginWeb(data) {
            web.webGet(TAG, "/dziennik/frekwencja") { text ->
                val matcher = STUDENT_OPTION.matcher(text)
                while (matcher.find()) {
                    val studentId = matcher.group(1).toInt()
                    // Server renders "SURNAME NAME"; swap to "Name Surname" before fixName.
                    val raw = matcher.group(2).trim()
                    val parts = raw.split(" ")
                    val swapped = if (parts.size > 1) "${parts.last()} ${parts.dropLast(1).joinToString(" ")}" else raw
                    val studentNameLong = swapped.fixName()
                    val surname = studentNameLong.substringAfterLast(' ').ifEmpty { studentNameLong }
                    val givenInitial = studentNameLong.take(1)
                    // Original format: "Surname N." (e.g. "Alechniewicz M.")
                    val studentNameShort = "$surname $givenInitial.".fixName()

                    val profile = Profile(
                            firstProfileId++,
                            data.loginStore.id,
                            LoginType.MOBIDZIENNIK,
                            studentNameLong,
                            data.loginUsername,
                            studentNameLong,
                            studentNameShort,
                            null
                    ).apply {
                        studentData["studentId"] = studentId
                    }
                    profileList.add(profile)
                }

                EventBus.getDefault().postSticky(FirstLoginFinishedEvent(profileList, data.loginStore))
                onSuccess()
            }
        }
    }
}
