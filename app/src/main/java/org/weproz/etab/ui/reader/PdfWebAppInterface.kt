package org.weproz.etab.ui.reader

import android.webkit.JavascriptInterface

interface PdfReaderBridge {
    fun onDefine(word: String)
    fun onHighlight(text: String, page: Int, rangeData: String)
    fun onRemoveHighlight(text: String, page: Int, rangeData: String)
    fun onCopy(text: String)
    fun onPageChanged(pageNumber: Int, totalPages: Int)
    fun onToggleControls()
}

class PdfWebAppInterface(private val bridge: PdfReaderBridge) {

    @JavascriptInterface
    fun onDefine(word: String) {
        bridge.onDefine(word)
    }

    @JavascriptInterface
    fun onHighlight(text: String, page: Int, rangeData: String) {
        bridge.onHighlight(text, page, rangeData)
    }

    @JavascriptInterface
    fun onRemoveHighlight(text: String, page: Int, rangeData: String) {
        bridge.onRemoveHighlight(text, page, rangeData)
    }

    @JavascriptInterface
    fun onCopy(text: String) {
        bridge.onCopy(text)
    }

    @JavascriptInterface
    fun onPageChanged(pageNumber: Int, totalPages: Int) {
        bridge.onPageChanged(pageNumber, totalPages)
    }

    @JavascriptInterface
    fun onToggleControls() {
        bridge.onToggleControls()
    }
}
