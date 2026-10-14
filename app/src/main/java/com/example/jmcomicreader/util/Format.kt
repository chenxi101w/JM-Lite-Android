package com.example.jmreader.util

/** 把字节数格式化为可读字符串（B / KB / MB / GB）。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return if (i == 0) "$bytes ${units[i]}" else "%.1f %s".format(value, units[i])
}

/** 把字节/秒 格式化为下载速度字符串（如 "1.2 MB/s"）。 */
fun formatSpeed(bytesPerSec: Float): String {
    if (bytesPerSec <= 0f) return "0 B/s"
    return "${formatBytes(bytesPerSec.toLong())}/s"
}
