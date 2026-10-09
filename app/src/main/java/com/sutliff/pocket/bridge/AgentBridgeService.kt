package com.sutliff.pocket.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sutliff.pocket.MainActivity
import com.sutliff.pocket.R
import com.sutliff.pocket.agent.AgentEvent
import com.sutliff.pocket.agent.AgentSessionManager
import com.sutliff.pocket.data.InferenceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class RunningSession(
    val sessionId: String,
    val title: String,
    val startedAt: Long,
    val status: SessionStatus = SessionStatus.Running
)

enum class SessionStatus { Running, Completed, Failed }

class AgentBridgeService : Service() {

    private val binder = LocalBinder()
    private var scope: CoroutineScope? = null
    private val activeSessions = ConcurrentHashMap<String, RunningSession>()
    private val sessionCounter = AtomicInteger(0)
    private val notificationId = 1001
    private val channelId = "agent_sessions"
    private val TAG = "AgentBridgeService"

    inner class LocalBinder : Binder() {
        fun getService(): AgentBridgeService = this@AgentBridgeService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(notificationId, buildNotification())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Log.d(TAG, "AgentBridgeService created")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun startSession(sessionId: String, title: String = "Session ${sessionCounter.incrementAndGet()}") {
        val session = RunningSession(
            sessionId = sessionId,
            title = title,
            startedAt = System.currentTimeMillis()
        )
        activeSessions[sessionId] = session
        updateNotification()
        Log.d(TAG, "Session started: $sessionId ($title)")
    }

    fun updateSessionStatus(sessionId: String, status: SessionStatus) {
        activeSessions[sessionId]?.let { session ->
            activeSessions[sessionId] = session.copy(status = status)
            updateNotification()
        }
    }

    fun completeSession(sessionId: String) {
        activeSessions.remove(sessionId)
        updateNotification()
        Log.d(TAG, "Session completed: $sessionId")
    }

    fun getActiveSessions(): List<RunningSession> = activeSessions.values.toList()

    fun getActiveSessionCount(): Int = activeSessions.size

    fun runAgentSession(sessionId: String, prompt: String, onEvent: (AgentEvent) -> Unit) {
        startSession(sessionId, prompt.take(50))
        scope?.launch {
            try {
                val sessionManager = AgentSessionManager(applicationContext)
                sessionManager.run(sessionId, prompt) { event ->
                    onEvent(event)
                    when (event) {
                        is AgentEvent.MessageComplete -> {
                            if (event.message.role == "assistant" && event.message.toolCalls == null) {
                                updateSessionStatus(sessionId, SessionStatus.Completed)
                            }
                        }
                        is AgentEvent.Error -> {
                            updateSessionStatus(sessionId, SessionStatus.Failed)
                        }
                        else -> {}
                    }
                }
                if (activeSessions[sessionId]?.status == SessionStatus.Running) {
                    completeSession(sessionId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Agent session failed", e)
                updateSessionStatus(sessionId, SessionStatus.Failed)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Agent Sessions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active agent sessions"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification? {
        val count = activeSessions.size
        if (count == 0) return null

        val title = "$count session${if (count != 1) "s" else ""} running"
        val content = activeSessions.values.joinToString(", ") { it.title.take(30) }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotification()
        if (notification != null) {
            nm.notify(notificationId, notification)
        } else {
            nm.cancel(notificationId)
        }
    }

    override fun onDestroy() {
        scope?.cancel()
        activeSessions.clear()
        super.onDestroy()
    }
}