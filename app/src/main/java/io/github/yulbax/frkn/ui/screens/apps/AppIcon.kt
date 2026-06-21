package io.github.yulbax.frkn.ui.screens.apps

import android.content.Context
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    return produceState(initialValue = AppIconCache.get(packageName), key1 = packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { AppIconCache.load(context, packageName) }
        }
    }.value
}

private object AppIconCache {
    private const val SIZE_PX = 128
    private const val BYTES_PER_ICON = SIZE_PX * SIZE_PX * 4

    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = BYTES_PER_ICON
    }

    fun get(packageName: String): ImageBitmap? = cache.get(packageName)

    fun load(context: Context, packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = createBitmap(SIZE_PX, SIZE_PX)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, SIZE_PX, SIZE_PX)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }.getOrNull()?.also { cache.put(packageName, it) }
    }
}
