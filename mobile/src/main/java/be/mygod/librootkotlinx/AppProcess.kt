package be.mygod.librootkotlinx

import android.os.Build
import android.os.Debug
import android.os.Process
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

object AppProcess {
    /**
     * Based on: https://android.googlesource.com/platform/bionic/+/aff9a34/linker/linker.cpp#3397
     */
    @get:RequiresApi(29)
    val genericLdConfigFilePath: String get() {
        val classVMRuntime = Class.forName("dalvik.system.VMRuntime")
        val abiString = classVMRuntime.getDeclaredMethod("getCurrentInstructionSet").invoke(
                classVMRuntime.getDeclaredMethod("getRuntime").invoke(null))
        "/system/etc/ld.config.$abiString.txt".let { if (File(it).isFile) return it }
        if (Build.VERSION.SDK_INT >= 30) "/linkerconfig/ld.config.txt".let {
            check(File(it).isFile) { "failed to find generated linker configuration from \"$it\"" }
            return it
        }
        val prop = Class.forName("android.os.SystemProperties")
        if (prop.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.java).invoke(null,
                        "ro.vndk.lite", false) as Boolean) "/system/etc/ld.config.vndk_lite.txt".let {
            if (File(it).isFile) return it
        } else when (val version = prop.getDeclaredMethod("get", String::class.java, String::class.java).invoke(null,
                "ro.vndk.version", "") as String) {
            "", "current" -> { }
            else -> "/system/etc/ld.config.$version.txt".let { if (File(it).isFile) return it }
        }
        return "/system/etc/ld.config.txt"
    }

    /**
     * Based on: https://android.googlesource.com/platform/bionic/+/30f2f05/linker/linker_config.cpp#182
     */
    @RequiresApi(29)
    fun findLinkerSection(lines: Sequence<String>, binaryRealPath: String): String {
        for (untrimmed in lines) {
            val line = untrimmed.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line[0] == '[' && line.last() == ']') break
            if (line.contains("+=")) continue
            val chunks = line.split('=', limit = 2)
            if (chunks.size < 2) {
                Logger.me.w("warning: couldn't parse invalid format: $line (ignoring this line)")
                continue
            }
            var (name, value) = chunks.map { it.trim() }
            if (!name.startsWith("dir.")) {
                Logger.me.w("warning: unexpected property name \"$name\", " +
                        "expected format dir.<section_name> (ignoring this line)")
                continue
            }
            if (value.endsWith('/')) value = value.dropLast(1)
            if (value.isEmpty()) {
                Logger.me.w("warning: property value is empty (ignoring this line)")
                continue
            }
            try {
                value = File(value).canonicalPath
            } catch (e: IOException) {
                Logger.me.i("warning: path \"$value\" couldn't be resolved: ${e.message}")
            }
            if (binaryRealPath.startsWith(value) && binaryRealPath[value.length] == '/') return name.substring(4)
        }
        throw IllegalArgumentException("No valid linker section found")
    }

    val myExe get() = "/proc/${Process.myPid()}/exe"
    val myExeCanonical get() = try {
        File("/proc/self/exe").canonicalPath
    } catch (e: IOException) {
        Logger.me.i("warning: couldn't resolve self exe: ${e.message}")
        "/system/bin/app_process"
    }

    /**
     * To workaround Samsung's stupid kernel patch that prevents exec, we need to relocate exe outside of /data.
     * See also: https://github.com/Chainfire/librootjava/issues/19
     *
     * @return The script to be executed to perform relocation and the relocated binary path.
     */
    fun relocateScript(token: String): Pair<StringBuilder, String> {
        val script = StringBuilder()
        val (baseDir, relocated) = if (Build.VERSION.SDK_INT < 29) "/dev" to "/dev/app_process_$token" else {
            val apexPath = "/apex/$token"
            script.appendln("[ -d $apexPath ] || " +
                    "mkdir $apexPath && " +
                    // we need to mount a new tmpfs to override noexec flag
                    "mount -t tmpfs -o size=1M tmpfs $apexPath || exit 1")
            // unfortunately native ld.config.txt only recognizes /data,/system,/system_ext as system directories;
            // to link correctly, we need to add our path to the linker config too
            val ldConfig = "$apexPath/etc/ld.config.txt"
            val masterLdConfig = genericLdConfigFilePath
            val section = try {
                File(masterLdConfig).useLines { findLinkerSection(it, myExeCanonical) }
            } catch (e: Exception) {
                Logger.me.w("Failed to locate system section", e)
                "system"
            }
            script.appendln("[ -f $ldConfig ] || " +
                    "mkdir -p $apexPath/etc && " +
                    "echo dir.$section = $apexPath >$ldConfig && " +
                    "cat $masterLdConfig >>$ldConfig || exit 1")
            "$apexPath/bin" to "$apexPath/bin/app_process"
        }
        script.appendln("[ -f $relocated ] || " +
                "mkdir -p $baseDir && " +
                "cp $myExe $relocated && " +
                "chmod 700 $relocated || exit 1")
        return script to relocated
    }

    /**
     * Compute the shell script line that exec into the corresponding [clazz].
     * Extra params can be simply appended to the string.
     */
    fun launchString(packageCodePath: String, clazz: String, appProcess: String, niceName: String? = null): String {
        val debugParams = if (Debug.isDebuggerConnected()) when (Build.VERSION.SDK_INT) {
            in 29..Int.MAX_VALUE -> "-XjdwpProvider:adbconnection"
            28 -> "-XjdwpProvider:adbconnection -XjdwpOptions:suspend=n,server=y -Xcompiler-option --debuggable"
            else -> "-Xrunjdwp:transport=dt_android_adb,suspend=n,server=y -Xcompiler-option --debuggable"
        } else ""
        val extraParams = if (niceName != null) " --nice-name=$niceName" else ""
        return "CLASSPATH=$packageCodePath exec $appProcess $debugParams /system/bin$extraParams $clazz"
    }
}
