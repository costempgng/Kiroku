package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import ani.dantotsu.parsers.NovelInterface
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.parsers.novel.NovelLoadResult
import ani.dantotsu.util.Logger
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.util.system.getApplicationIcon
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * Loads Kiroku's in-app novel extension APKs.
 *
 * Anime and manga extensions are no longer loaded here: they are executed by the Kiroku Extension
 * Runtime Bridge out of process. Novel extensions have no bridge runtime yet, so they keep using
 * Kiroku's local loader.
 */
internal object ExtensionLoader {

    val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else 0)

    /**
     * Return a list of all the installed novel extensions initialized concurrently.
     *
     * @param context The application context.
     */
    fun loadNovelExtensions(context: Context): List<NovelLoadResult> {
        val pkgManager = context.packageManager

        val installedPkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            pkgManager.getInstalledPackages(PACKAGE_FLAGS)
        }

        val extPkgs = installedPkgs.filter { isNovelPackage(it) }

        if (extPkgs.isEmpty()) return emptyList()

        return runBlocking {
            val deferred = extPkgs.map {
                async { loadNovelExtension(context, it.packageName, it) }
            }
            deferred.map { it.await() }
        }
    }

    /**
     * Attempts to load a novel extension from the given package name.
     */
    fun loadNovelExtensionFromPkgName(context: Context, pkgName: String): NovelLoadResult {
        val pkgInfo = try {
            context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
        } catch (error: PackageManager.NameNotFoundException) {
            Logger.log(error)
            return NovelLoadResult.Error(error)
        }
        if (!isNovelPackage(pkgInfo)) {
            Logger.log("Tried to load a package that wasn't an extension ($pkgName)")
            return NovelLoadResult.Error(Exception("Tried to load a package that wasn't an extension ($pkgName)"))
        }
        return loadNovelExtension(context, pkgName, pkgInfo)
    }

    private fun loadNovelExtension(
        context: Context,
        pkgName: String,
        pkgInfo: PackageInfo
    ): NovelLoadResult {
        val pkgManager = context.packageManager

        val appInfo = try {
            pkgManager.getApplicationInfo(pkgName, PackageManager.GET_META_DATA)
        } catch (error: PackageManager.NameNotFoundException) {
            Logger.log(error)
            return NovelLoadResult.Error(error)
        }

        val extName =
            pkgManager.getApplicationLabel(appInfo).toString().substringAfter("Tachiyomi: ")
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        if (versionName.isNullOrEmpty()) {
            Logger.log("Missing versionName for extension $extName")
            return NovelLoadResult.Error(Exception("Missing versionName for extension $extName"))
        }

        val classLoader = PathClassLoader(appInfo.sourceDir, null, context.classLoader)
        val novelInterfaceInstance = try {
            val className = appInfo.loadLabel(context.packageManager).toString()
            val extensionClassName =
                "some.random.novelextensions.${className.lowercase(Locale.getDefault())}.$className"
            val loadedClass = classLoader.loadClass(extensionClassName)
            val instance = loadedClass.getDeclaredConstructor().newInstance()
            instance as? NovelInterface
        } catch (e: Throwable) {
            Logger.log("Extension load error: $extName")
            return NovelLoadResult.Error(e as Exception)
        }

        val extension = NovelExtension.Installed(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            sources = listOfNotNull(novelInterfaceInstance),
            isUnofficial = true,
            icon = context.getApplicationIcon(pkgName),
        )
        return NovelLoadResult.Success(extension)
    }

    /** Novel extensions are published under the `some.random.novelextensions` package namespace. */
    private fun isNovelPackage(pkgInfo: PackageInfo): Boolean =
        pkgInfo.packageName.startsWith("some.random")
}
