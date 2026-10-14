package com.example.jmreader.util

import org.json.JSONArray

/** JSONArray -> List<String> */
fun JSONArray.toStringList(): List<String> {
    val list = mutableListOf<String>()
    for (i in 0 until length()) list.add(optString(i))
    return list
}
