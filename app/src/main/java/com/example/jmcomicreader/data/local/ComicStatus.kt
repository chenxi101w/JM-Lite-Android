package com.example.jmreader.data.local

/**
 * v1.12.0 数据层重构后，本文件原「位掩码状态登记表 ComicStatus」已取消——
 * 状态并入 comics 单表的 finished / favorite / blocked 三个 0/1 列，
 * 由 ComicLedger 提供唯一写入点。
 *
 * 仅保留作者名归一化工具（拉黑/算法共用）。
 */

/** 作者名归一化（与 ContentFilter.normalizeAuthor 一致）：去首尾空白、转小写、合并内部空白，用于拉黑匹配。 */
fun normalizeAuthorName(a: String): String =
    a.trim().lowercase().replace(Regex("\\s+"), "")
