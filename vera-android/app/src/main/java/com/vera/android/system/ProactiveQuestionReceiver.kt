package com.vera.android.system

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vera.android.data.api.VeraApi
import com.vera.android.data.prefs.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ProactiveQuestionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val questionId = intent.getStringExtra(EXTRA_QUESTION_ID) ?: return
        val answer = intent.getStringExtra(EXTRA_ANSWER) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)

        // Dismiss the notification
        context.getSystemService(NotificationManager::class.java).cancel(notifId)

        val token = SecurePrefs(context).sessionToken ?: return
        val api = VeraApi()
        CoroutineScope(Dispatchers.IO).launch {
            api.submitLearningAnswer(token, questionId, answer)
        }
    }

    companion object {
        const val EXTRA_QUESTION_ID = "question_id"
        const val EXTRA_ANSWER = "answer"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val CHANNEL_ID = "vera_learning"
    }
}
