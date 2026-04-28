package com.penumbraos.systeminjector.runtimepolicy

import android.content.Context
import android.util.Log

object LaunchPolicyInstaller {
    private const val TAG = "RuntimePolicy"
    private const val PROP_DISABLE = "debug.penumbra.runtimepolicy.disable"

    fun refreshPolicies(context: Context) {
        try {
            if (isDisabled()) {
                Log.i(TAG, "Runtime policy disabled via $PROP_DISABLE")
                return
            }

            val trackedPackages = PolicyRegistry.loadTrackedPackages(context)
            if (trackedPackages.isEmpty()) {
                Log.i(TAG, "No tracked packages registered")
                return
            }

            Log.i(TAG, "Refreshing runtime policy for ${trackedPackages.size} tracked package(s)")
            val keptPackages = linkedSetOf<String>()

            for (packageName in trackedPackages.sorted()) {
                when (val result = ServerLaunchPatch.applyOverride(context, packageName)) {
                    is ServerLaunchPatch.Result.Applied -> {
                        Log.i(
                            TAG,
                            "Applied seInfo override for ${result.packageName}: " +
                                "base=${result.baseSeInfo ?: "<null>"}, " +
                                "overrideBefore=${result.overrideBefore ?: "<null>"}, " +
                                "effectiveBefore=${result.effectiveBefore ?: "<null>"}, " +
                                "overrideAfter=${result.overrideAfter ?: "<null>"}, " +
                                "effectiveAfter=${result.effectiveAfter ?: "<null>"}"
                        )
                    }
                    is ServerLaunchPatch.Result.AlreadyApplied -> {
                        Log.i(
                            TAG,
                            "seInfo override already set for ${result.packageName}: " +
                                "base=${result.baseSeInfo ?: "<null>"}, " +
                                "override=${result.override ?: "<null>"}, " +
                                "effective=${result.effective ?: "<null>"}"
                        )
                    }
                    is ServerLaunchPatch.Result.PackageMissing -> {
                        Log.w(TAG, "Tracked package missing from PMS: ${result.packageName}")
                        continue
                    }
                    is ServerLaunchPatch.Result.NotEligible -> {
                        Log.w(
                            TAG,
                            "Tracked package is no longer installer-managed system app: " +
                                "package=${result.packageName}, sharedUserId=${result.sharedUserId}, uid=${result.appUid ?: -1}"
                        )
                        continue
                    }
                }

                keptPackages.add(packageName)
            }

            if (keptPackages != trackedPackages) {
                if (PolicyRegistry.replaceTrackedPackages(context, keptPackages)) {
                    Log.i(
                        TAG,
                        "Pruned tracked package registry from ${trackedPackages.size} to ${keptPackages.size} entries"
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Runtime launch policy refresh failed", t)
        }
    }

    private fun isDisabled(): Boolean {
        return try {
            val sysPropClass = Class.forName("android.os.SystemProperties")
            val getMethod = sysPropClass.getDeclaredMethod("get", String::class.java, String::class.java)
            val value = getMethod.invoke(null, PROP_DISABLE, "") as String
            value == "1"
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read $PROP_DISABLE; assuming enabled", t)
            false
        }
    }
}
