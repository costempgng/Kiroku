package eu.kanade.tachiyomi.extension.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import ani.dantotsu.media.MediaType
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.parsers.novel.NovelLoadResult
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import tachiyomi.core.util.lang.launchNow

/**
 * Broadcast receiver that listens for the system's packages installed, updated or removed, and only
 * notifies the given [listener] when the package is a novel extension.
 *
 * Anime and manga extensions are managed by the extension runtime, which reports its own changes
 * through the bridge, so they are not observed here.
 */
internal class ExtensionInstallReceiver : BroadcastReceiver() {

    private var novelListener: NovelListener? = null
    private var type: MediaType? = null

    /**
     * Registers this broadcast receiver
     */
    fun register(context: Context) {
        ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun setNovelListener(listener: NovelListener): ExtensionInstallReceiver {
        this.type = MediaType.NOVEL
        novelListener = listener
        return this
    }

    /**
     * Called when one of the events of the [filter] is received. When the package is an extension,
     * it's loaded in background and it notifies the [listener] when finished.
     */
    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (isReplacing(intent)) return

                launchNow {
                    when (val result = getNovelExtensionFromIntent(context, intent)) {
                        is NovelLoadResult.Success -> novelListener?.onExtensionInstalled(
                            result.extension
                        )

                        else -> {}
                    }
                }
            }

            Intent.ACTION_PACKAGE_REPLACED -> {
                launchNow {
                    when (val result = getNovelExtensionFromIntent(context, intent)) {
                        is NovelLoadResult.Success -> novelListener?.onExtensionUpdated(
                            result.extension
                        )

                        else -> {}
                    }
                }
            }

            Intent.ACTION_PACKAGE_REMOVED -> {
                if (isReplacing(intent)) return

                val pkgName = getPackageNameFromIntent(intent)
                if (pkgName != null) {
                    novelListener?.onPackageUninstalled(pkgName)
                }
            }
        }
    }

    /**
     * Returns the extension triggered by the given intent.
     *
     * @param context The application context.
     * @param intent The intent containing the package name of the extension.
     */
    @Suppress("unused")
    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun getNovelExtensionFromIntent(
        context: Context,
        intent: Intent?
    ): NovelLoadResult {
        val pkgName = getPackageNameFromIntent(intent)
        if (pkgName == null) {
            Logger.log("Package name not found")
            return NovelLoadResult.Error(Exception("Package name not found"))
        }
        return GlobalScope.async(Dispatchers.Default, CoroutineStart.DEFAULT) {
            ExtensionLoader.loadNovelExtensionFromPkgName(
                context,
                pkgName,
            )
        }.await()
    }

    /**
     * Listener that receives novel extension installation events.
     */
    interface NovelListener {
        fun onExtensionInstalled(extension: NovelExtension.Installed)
        fun onExtensionUpdated(extension: NovelExtension.Installed)
        fun onPackageUninstalled(pkgName: String)
    }

    companion object {

        /**
         * Returns the intent filter this receiver should subscribe to.
         */
        val filter
            get() = IntentFilter().apply {
                priority = 100
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }

        /**
         * Returns true if this package is performing an update.
         *
         * @param intent The intent that triggered the event.
         */
        fun isReplacing(intent: Intent): Boolean {
            return intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        }

        /**
         * Returns the package name of the installed, updated or removed application.
         */
        fun getPackageNameFromIntent(intent: Intent?): String? {
            return intent?.data?.encodedSchemeSpecificPart ?: return null
        }
    }
}
