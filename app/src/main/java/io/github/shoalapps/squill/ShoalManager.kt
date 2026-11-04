package io.github.shoalapps.squill

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** Squill has no network access, so updates are Shoal's job, or the releases page's without it. */
object ShoalManager {
    private const val PACKAGE = "io.github.shoalapps.shoal"
    private const val ACTION = "io.github.shoalapps.shoal.CHECK_UPDATES"
    private const val RELEASES = "https://github.com/ShoalApps/squill/releases/latest"

    /** Debug builds pair with Shoal's debug build. */
    private fun pkg(context: Context) = if (context.packageName.endsWith(".debug")) "$PACKAGE.debug" else PACKAGE

    fun checkForUpdates(context: Context) {
        val opened = runCatching {
            context.packageManager.getPackageInfo(pkg(context), 0)
            context.startActivity(Intent(ACTION).setPackage(pkg(context)).putExtra("package", context.packageName))
        }.isSuccess
        if (!opened) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, RELEASES.toUri())) }
    }
}
