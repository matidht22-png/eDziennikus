/*
 * Copyright (c) Kuba Szczodrzyński 2019-11-13.
 */

package eu.mikus.edziennik.ui.dialogs

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import eu.mikus.edziennik.App
import eu.mikus.edziennik.MainActivity
import eu.mikus.edziennik.R
import eu.mikus.edziennik.data.db.removeProfileData
import eu.mikus.edziennik.ui.dialogs.base.BaseDialog

class ProfileRemoveDialog(
    activity: AppCompatActivity,
    val profileId: Int,
    val profileName: String,
    val keepProfile: Boolean = false,
    val onRemove: (() -> Unit)? = null,
    onShowListener: ((tag: String) -> Unit)? = null,
    onDismissListener: ((tag: String) -> Unit)? = null,
) : BaseDialog<Any>(activity, onShowListener, onDismissListener) {

    override val TAG = "ProfileRemoveDialog"

    override fun getTitleRes() = R.string.profile_menu_remove_confirm
    override fun getMessageFormat() =
        R.string.profile_menu_remove_confirm_text_format to listOf(
            profileName,
            profileName
        )

    override fun isCancelable() = false
    override fun getPositiveButtonText() = R.string.remove
    override fun getNeutralButtonText() = R.string.cancel

    override suspend fun onShow() = Unit

    override suspend fun onPositiveClick(): Boolean {
        withContext(Dispatchers.Default) {
            if (removeProfileData(app.db, profileId, keepProfile) && App.profileId == profileId)
                app.profileLoadLast { }
        }

        if (activity is MainActivity)
            activity.reloadTarget()

        Toast.makeText(activity, R.string.dialog_profile_remove_success, Toast.LENGTH_LONG).show()
        onRemove?.invoke()

        return DISMISS
    }
}
