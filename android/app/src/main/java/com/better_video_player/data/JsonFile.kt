package com.better_video_player.data

import android.util.Log
import org.json.JSONObject
import java.io.File

// The app's own files (choices.json, playlists.json, stats.json...): read whole, and written
// aside and swapped in, so a crash mid-write can't lose what was there.
object JsonFile {
    // null if the file isn't there; an empty object if it can't be read.
    fun read(file: File, tag: String): JSONObject? {
        if (!file.exists()) return null
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            Log.w(tag, "Couldn't read ${file.path}", e)
            JSONObject()
        }
    }

    fun write(file: File, json: JSONObject, tag: String) {
        try {
            val temp = File(file.path + ".tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(tag, "Couldn't write ${file.path}", e)
        }
    }
}
