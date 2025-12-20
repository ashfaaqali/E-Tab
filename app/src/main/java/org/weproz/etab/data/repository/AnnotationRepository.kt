package org.weproz.etab.data.repository

import org.weproz.etab.data.model.whiteboard.DrawAction
import org.weproz.etab.data.model.whiteboard.GridType
import org.weproz.etab.data.model.whiteboard.ParsedPage
import org.weproz.etab.data.serializer.WhiteboardSerializer
import java.io.File

class AnnotationRepository(private val baseDir: File) {

    fun getAnnotationsFile(documentPath: String): File {
        val fileName = File(documentPath).name + ".annotations.json"
        return File(baseDir, fileName)
    }

    fun loadAnnotations(documentPath: String): Map<Int, List<DrawAction>> {
        val file = getAnnotationsFile(documentPath)
        if (!file.exists()) return emptyMap()

        return try {
            val json = file.readText()
            val data = WhiteboardSerializer.deserialize(json)
            val map = mutableMapOf<Int, List<DrawAction>>()
            data.pages.forEachIndexed { index, page ->
                map[index + 1] = page.actions
            }
            map
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    fun saveAnnotations(documentPath: String, annotations: Map<Int, List<DrawAction>>) {
        try {
            val pages = mutableListOf<ParsedPage>()
            val maxPage = annotations.keys.maxOrNull() ?: 0
            
            for (i in 1..maxPage) {
                val actions = annotations[i] ?: emptyList()
                pages.add(ParsedPage(actions, GridType.NONE))
            }
            
            val json = WhiteboardSerializer.serialize(pages)
            val file = getAnnotationsFile(documentPath)
            file.parentFile?.mkdirs()
            file.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
