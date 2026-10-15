package com.maxrave.common

import java.io.File

/**
 * [DESKTOP_DATA_DIR] under the user's home, with [subfolders] appended: the one place a desktop
 * data path is put together, for modules that cannot reach `data`'s `fileDir()`.
 */
fun desktopDataPath(vararg subfolders: String): String =
    (listOf(System.getProperty("user.home"), DESKTOP_DATA_DIR) + subfolders).joinToString(File.separator)
