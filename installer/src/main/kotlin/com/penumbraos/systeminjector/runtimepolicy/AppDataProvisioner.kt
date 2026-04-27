package com.penumbraos.systeminjector.runtimepolicy

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

object AppDataProvisioner {
    private const val TAG = "RuntimePolicy"
    private const val TARGET_USER_ID = 0
    private const val PROVISION_SEINFO = "platform:system_app"
    private const val FLAG_STORAGE_DE = 0x1
    private const val FLAG_STORAGE_CE = 0x2
    private const val PER_USER_RANGE = 100000
    private const val SHARED_USER_ID_SYSTEM = 1000

    sealed interface Result {
        data class Applied(
            val packageName: String,
            val userId: Int,
            val flags: Int,
            val appId: Int,
            val targetSdkVersion: Int,
            val seInfo: String,
            val ceDataInode: Long,
        ) : Result

        data class PackageMissing(val packageName: String) : Result

        data class NotEligible(
            val packageName: String,
            val appUid: Int?,
        ) : Result

        data class Failed(
            val packageName: String,
            val message: String,
            val error: Throwable? = null,
        ) : Result
    }

    fun ensureProvisioned(context: Context, packageName: String): Result {
        val appInfo = getApplicationInfo(context, packageName) ?: return Result.PackageMissing(packageName)
        if (appInfo.uid != SHARED_USER_ID_SYSTEM) {
            return Result.NotEligible(packageName, appInfo.uid)
        }

        return try {
            val installer = findInstaller()
                ?: return Result.Failed(packageName, "PMS.mInstaller not found")
            val appId = appInfo.uid % PER_USER_RANGE
            val flags = FLAG_STORAGE_DE or FLAG_STORAGE_CE
            val ceDataInode = invokeCreateAppData(
                installer = installer,
                packageName = packageName,
                userId = TARGET_USER_ID,
                flags = flags,
                appId = appId,
                seInfo = PROVISION_SEINFO,
                targetSdkVersion = appInfo.targetSdkVersion,
            )
            Result.Applied(
                packageName = packageName,
                userId = TARGET_USER_ID,
                flags = flags,
                appId = appId,
                targetSdkVersion = appInfo.targetSdkVersion,
                seInfo = PROVISION_SEINFO,
                ceDataInode = ceDataInode,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "App-data provisioning failed for $packageName", t)
            Result.Failed(packageName, t.message ?: t.javaClass.name, t)
        }
    }

    private fun getApplicationInfo(context: Context, packageName: String): ApplicationInfo? {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findInstaller(): Any? {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        val pms = getService.invoke(null, "package") ?: return null
        return readDeclaredField(pms, "mInstaller")
    }

    private fun invokeCreateAppData(
        installer: Any,
        packageName: String,
        userId: Int,
        flags: Int,
        appId: Int,
        seInfo: String,
        targetSdkVersion: Int,
    ): Long {
        val method = installer.javaClass.getMethod(
            "createAppData",
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaPrimitiveType,
        ).apply {
            isAccessible = true
        }

        return method.invoke(
            installer,
            null,
            packageName,
            userId,
            flags,
            appId,
            seInfo,
            targetSdkVersion,
        ) as Long
    }

    private fun readDeclaredField(target: Any, fieldName: String): Any? {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            try {
                val field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }
}
