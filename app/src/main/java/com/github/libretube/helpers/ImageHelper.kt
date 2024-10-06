package com.github.libretube.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toBitmapOrNull
import coil.ImageLoader
import coil.disk.DiskCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.github.libretube.BuildConfig
import com.github.libretube.api.CronetHelper
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.extensions.toAndroidUri
import com.github.libretube.util.DataSaverMode
import com.google.net.cronet.okhttptransport.CronetInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.nio.file.Path

object ImageHelper {
    private lateinit var imageLoader: ImageLoader

    private val Context.coilFile get() = cacheDir.resolve("coil")

    /**
     * Initialize the image loader
     */
    fun initializeImageLoader(context: Context) {
        val maxCacheSize = PreferenceHelper.getString(PreferenceKeys.MAX_IMAGE_CACHE, "128")

        val httpClient = OkHttpClient().newBuilder()

        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            httpClient.addInterceptor(loggingInterceptor)
        }

        httpClient.addInterceptor(CronetInterceptor.newBuilder(CronetHelper.cronetEngine).build())

        imageLoader = ImageLoader.Builder(context)
            .callFactory(CronetHelper.callFactory)
            .crossfade(true)
            .okHttpClient {
                httpClient.build()
            }
            .apply {
                if (maxCacheSize.isEmpty()) {
                    diskCachePolicy(CachePolicy.DISABLED)
                } else {
                    diskCachePolicy(CachePolicy.ENABLED)
                    memoryCachePolicy(CachePolicy.ENABLED)

                    val diskCache = generateDiskCache(
                        directory = context.coilFile,
                        size = maxCacheSize.toInt()
                    )
                    diskCache(diskCache)
                }
            }
            .build()
    }

    private fun generateDiskCache(directory: File, size: Int): DiskCache {
        return DiskCache.Builder()
            .directory(directory)
            .maxSizeBytes(size * 1024 * 1024L)
            .build()
    }

    /**
     * load an image from a url into an imageView
     */
    fun loadImage(url: String?, target: ImageView, whiteBackground: Boolean = false) {
        // clear image to avoid loading issues at fast scrolling
        target.setImageBitmap(null)

        // only load the image if the data saver mode is disabled
        if (DataSaverMode.isEnabled(target.context) || url.isNullOrEmpty()) return
        val urlToLoad = ProxyHelper.unwrapImageUrl(url)

        val request = ImageRequest.Builder(target.context)
            .data(urlToLoad)
            .listener { _, result ->
                // set the background to white for transparent images
                if (whiteBackground) target.setBackgroundColor(Color.WHITE)

                target.setImageDrawable(result.drawable)
            }
            .build()

        imageLoader.enqueue(request)
    }

    suspend fun downloadImage(context: Context, url: String, path: Path) {
        val bitmap = getImage(context, url) ?: return
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(path.toAndroidUri())?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 25, it)
            }
        }
    }

    suspend fun getImage(context: Context, url: String?): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .build()

        return imageLoader.execute(request).drawable?.toBitmapOrNull()
    }

    fun getImageWithCallback(context: Context, url: String?, onBitmap: (Bitmap) -> Unit) {
        val request = ImageRequest.Builder(context)
            .data(url)
            .target { drawable ->
                onBitmap(drawable.toBitmap())
            }
            .build()

        imageLoader.enqueue(request)
    }

    /**
     * Get a squared bitmap with the same width and height from a bitmap
     * @param bitmap The bitmap to resize
     */
    fun getSquareBitmap(bitmap: Bitmap): Bitmap {
        val newSize = minOf(bitmap.width, bitmap.height)
        return Bitmap.createBitmap(
            bitmap,
            (bitmap.width - newSize) / 2,
            (bitmap.height - newSize) / 2,
            newSize,
            newSize
        )
    }
}
