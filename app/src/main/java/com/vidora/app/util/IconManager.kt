package com.vidora.app.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Switches the launcher icon/name by enabling exactly one activity-alias. */
object IconManager {

    private fun aliasName(context: Context, icon: String, name: String) =
        "com.vidora.app.alias.L_${icon}_${name}"

    fun apply(context: Context, icon: String, name: String) {
        val pm = context.packageManager
        val target = aliasName(context, icon, name)
        // enable the new one first so the app is never without a launcher entry
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, target),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        LauncherCatalog.icons.forEach { i ->
            LauncherCatalog.names.forEach { n ->
                val alias = aliasName(context, i.id, n.id)
                if (alias != target) {
                    pm.setComponentEnabledSetting(
                        ComponentName(context.packageName, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        }
    }
}
