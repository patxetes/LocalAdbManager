package com.localadb.manager.adb

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.localadb.manager.R
import java.util.concurrent.Executors

/**
 * Receptor de Eventos del Sistema.
 *
 * Se despierta cuando el usuario pulsa "Enviar" en la notificación de texto.
 */
class PairingNotificationReceiver : BroadcastReceiver() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onReceive(context: Context, intent: Intent) {
        // 1. Extraer el texto escrito por el usuario
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val code = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim()

        // 2. Extraer el puerto que adjuntamos a la notificación
        val port = intent.getIntExtra(EXTRA_PAIRING_PORT, -1)

        if (code.isNullOrEmpty() || port <= 0) {
            showToast(context, "Código o puerto de emparejamiento inválido")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 3. Ejecutar el emparejamiento en hilo secundario (no bloquear la UI)
        executor.execute {
            val adbManager = AdbConnectionManager.getInstance(context)
            val success = adbManager.pairDevice("127.0.0.1", port, code)

            // Descartar la notificación
            notificationManager.cancel(NOTIFICATION_ID)

            // Mostrar el resultado en el hilo principal
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                if (success) {
                    showToast(context, context.getString(R.string.notification_pairing_success))
                } else {
                    showToast(context, context.getString(R.string.notification_pairing_failed, "Revisa el código"))
                }
            }
        }
    }

    private fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val EXTRA_PAIRING_PORT = "extra_pairing_port"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "adb_pairing_channel"
    }
}