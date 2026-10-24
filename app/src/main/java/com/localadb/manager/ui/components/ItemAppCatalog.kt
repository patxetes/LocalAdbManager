package com.localadb.manager.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localadb.manager.R
import com.localadb.manager.backup.AppInstalada

/**
 * Individual application card item displayed in the package backup catalog list.
 * Shows high-resolution icon, package ID, version, debuggable badge, and contextual backup button.
 */
@Composable
fun ItemAppCatalog(
    app: AppInstalada,
    isBusy: Boolean,
    onDirectApkCopy: () -> Unit,
    onOpenOptions: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val iconBitmap = remember(app.icono) {
                app.icono?.let { convertDrawableToBitmap(it) }
            }

            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap.asImageBitmap(),
                    contentDescription = app.nombreVisible,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.nombreVisible,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = app.paqueteId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "v${app.versionNombre} (${app.versionCodigo})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Visual badge: green for debuggable apps (run-as eligible) vs secondary container for production
                val badgeBgColor = if (app.esDepurable) Color(0xFF2E7D32) else MaterialTheme.colorScheme.secondaryContainer
                val badgeTextColor = if (app.esDepurable) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                val badgeText = if (app.esDepurable) {
                    stringResource(R.string.backup_badge_debuggable)
                } else {
                    stringResource(R.string.backup_badge_standard)
                }

                Box(
                    modifier = Modifier
                        .background(badgeBgColor, shape = RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = badgeTextColor
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action button: "Backup" with option dialog for debug apps vs direct "Copia APK" for release apps
            val buttonText = if (app.esDepurable) {
                stringResource(R.string.btn_backup_action)
            } else {
                stringResource(R.string.btn_backup_apk)
            }

            Button(
                enabled = !isBusy,
                onClick = {
                    if (app.esDepurable) {
                        onOpenOptions()
                    } else {
                        onDirectApkCopy()
                    }
                }
            ) {
                Text(text = buttonText, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Converts an Android Drawable (including AdaptiveDrawable and VectorDrawable) into a renderable Bitmap.
 */
fun convertDrawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}