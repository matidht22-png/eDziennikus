/*
 * Copyright (c) Kuba Szczodrzyński 2019-10-5.
 */

package eu.mikus.edziennik.data.api.edziennik.mobidziennik.login

import im.wangchao.mhttp.Request
import im.wangchao.mhttp.Response
import im.wangchao.mhttp.callback.TextCallbackHandler
import eu.mikus.edziennik.data.api.*
import eu.mikus.edziennik.data.api.edziennik.mobidziennik.DataMobidziennik
import eu.mikus.edziennik.data.api.models.ApiError
import eu.mikus.edziennik.ext.isNotNullNorEmpty
import eu.mikus.edziennik.utils.Utils.d

class MobidziennikLoginWeb(val data: DataMobidziennik, val onSuccess: () -> Unit) {
    companion object {
        private const val TAG = "MobidziennikLoginWeb"
    }

    init { run {
        if (data.isWebLoginValid()) {
            onSuccess()
        }
        else {
            if (data.loginServerName.isNotNullNorEmpty() && data.loginUsername.isNotNullNorEmpty() && data.loginPassword.isNotNullNorEmpty()) {
                data.app.cookieJar.clear("${data.loginServerName}.mobidziennik.pl")
                loginWithCredentials()
            }
            else {
                data.error(ApiError(TAG, ERROR_LOGIN_DATA_MISSING))
            }
        }
    }}

    private fun loginWithCredentials() {
        d(TAG, "Request: Mobidziennik/Login/Web - https://${data.loginServerName}.mobidziennik.pl/dziennik")

        val callback = object : TextCallbackHandler() {
            override fun onSuccess(text: String?, response: Response?) {
                // The /dziennik endpoint redirects to /dziennik/wyborwersji on success.
                // mhttp follows the redirect, so we receive the wyborwersji page HTML.
                // Success is confirmed by the presence of a session cookie (32-char hex key).
                val cookies = data.app.cookieJar.getAll("${data.loginServerName}.mobidziennik.pl")
                val cookie = cookies.entries.firstOrNull { it.key.length >= 32 && it.key != "SERVERID" }
                val sessionKey = cookie?.key
                val sessionId = cookie?.value

                if (sessionId == null) {
                    // No session cookie set — login failed or was rate-limited
                    when {
                        text?.contains("Nie jestes zalogowany") == true -> ERROR_LOGIN_MOBIDZIENNIK_WEB_INVALID_LOGIN
                        text?.contains("ifun") == true -> ERROR_LOGIN_MOBIDZIENNIK_WEB_INVALID_DEVICE
                        text?.contains("stare haslo") == true -> ERROR_LOGIN_MOBIDZIENNIK_WEB_OLD_PASSWORD
                        text?.contains("Archiwum") == true -> ERROR_LOGIN_MOBIDZIENNIK_WEB_ARCHIVED
                        text?.contains("Trwają prace techniczne") == true -> ERROR_LOGIN_MOBIDZIENNIK_WEB_MAINTENANCE
                        text?.contains("Uuuups... nieprawidłowy adres") == true -> ERROR_LOGIN_MOBIDZIENNIK_WEB_INVALID_ADDRESS
                        text?.contains("przerwa techniczna") == true -> ERROR_LOGIN_MOBIDZIENNIK_WEB_MAINTENANCE
                        else -> ERROR_LOGIN_MOBIDZIENNIK_WEB_OTHER
                    }.let { errorCode ->
                        data.error(ApiError(TAG, errorCode)
                                .withApiResponse(text?.take(200))
                                .withResponse(response))
                        return
                    }
                }

                data.webSessionKey = sessionKey
                data.webSessionValue = sessionId
                data.webServerId = data.app.cookieJar.get("${data.loginServerName}.mobidziennik.pl", "SERVERID")
                data.webSessionIdExpiryTime = System.currentTimeMillis() / 1000 + 45 * 60 /* 45min */
                onSuccess()
            }

            override fun onFailure(response: Response?, throwable: Throwable?) {
                data.error(ApiError(TAG, ERROR_REQUEST_FAILURE)
                        .withResponse(response)
                        .withThrowable(throwable))
            }
        }

        Request.builder()
                .url("https://${data.loginServerName}.mobidziennik.pl/dziennik")
                .userAgent(MOBIDZIENNIK_USER_AGENT)
                .contentType("application/x-www-form-urlencoded; charset=UTF-8")
                .addParameter("login", data.loginUsername)
                .addParameter("haslo", data.loginPassword)
                .post()
                .callback(callback)
                .build()
                .enqueue()
    }
}
