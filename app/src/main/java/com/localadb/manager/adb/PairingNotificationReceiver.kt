package com.localadb.manager.adb

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.localadb.manager.R

/**
 * Receptor de difusión para procesar el código de emparejamiento introducido
 * desde la notificación interactiva de Android sin cerrar la ventana de Ajustes.
 */
class PairingNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "adb_pairing_channel"
        const val NOTIFICATION_ID = 1001
        const val KEY_TEXT_REPLY = "key_pairing_code"
        const val EXTRA_PAIRING_PORT = "extra_pairing_port"
        private const val TAG = "PairingReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val rawInput = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim() ?: return

        var port = intent.getIntExtra(EXTRA_PAIRING_PORT, -1)
        var code = rawInput

        // Soporte flexible: si el usuario escribe "PUERTO CÓDIGO" (ej: "41235 123456") o "41235:123456"
        if (rawInput.contains(" ") || rawInput.contains(":") || rawInput.contains(",")) {
            val parts = rawInput.split(Regex("[\\s:,]+"))
            if (parts.size >= 2) {
                val parsedPort = parts[0].toIntOrNull()
                val parsedCode = parts[1].trim()
                if (parsedPort != null && parsedPort in 1024..65535) {
                    port = parsedPort
                    code = parsedCode
                }
            }
        }

        // Si el intent no traía puerto válido, recurrimos al puerto mDNS capturado en tiempo real
        if (port == -1 || port !in 1024..65535) {
            port = AdbMdnsManager.latestPairingPort ?: -1
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (port !in 1024..65535) {
            Log.e(TAG, "No se pudo determinar el puerto de emparejamiento (port=$port).")
            mostrarNotificacionResultado(
                context,
                manager,
                "Fallo en el emparejamiento",
                "No se detectó el puerto de vinculación. Escribe 'PUERTO CÓDIGO' en la notificación (ej: 41235 123456)."
            )
            return
        }

        Log.i(TAG, "Iniciando emparejamiento en puerto: $port con código: $code")

        val adbManager = AdbConnectionManager.getInstance(context)
        Thread {
            val exito = adbManager.pairDevice("127.0.0.1", port, code)
            if (exito) {
                Log.i(TAG, "Emparejamiento exitoso en puerto $port")
                mostrarNotificacionResultado(
                    context,
                    manager,
                    context.getString(R.string.notification_pairing_success),
                    "¡Dispositivo vinculado con éxito! Ya puedes usar Local ADB Manager."
                )
            } else {
                Log.e(TAG, "Fallo al emparejar en puerto $port con código $code")
                mostrarNotificacionResultado(
                    context,
                    manager,
                    "Fallo en el emparejamiento",
                    "El demonio ADB rechazó la vinculación. Asegúrate de tener abierta la ventana de Ajustes y que el código no haya caducado."
                )
            }
        }.start()
    }

    private fun mostrarNotificacionResultado(context: Context, manager: NotificationManager, titulo: String, texto: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}