package com.maxrave.data.io

import com.maxrave.common.DESKTOP_DATA_DIR
import okio.FileSystem
import java.io.File


actual fun fileSystem(): FileSystem {
    return FileSystem.SYSTEM
}

actual fun fileDir(): String = File(getHomeFolderPath(listOf(DESKTOP_DATA_DIR))).absolutePath