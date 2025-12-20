package org.weproz.etab.data.model.whiteboard

data class ParsedWhiteboardData(
    val pages: List<ParsedPage>
)

data class ParsedPage(
    val actions: List<DrawAction>,
    val gridType: GridType
)
