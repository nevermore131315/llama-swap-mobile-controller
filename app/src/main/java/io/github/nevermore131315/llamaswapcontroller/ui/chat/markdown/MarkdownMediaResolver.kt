package io.github.nevermore131315.llamaswapcontroller.ui.chat.markdown

/** Standalone build: no gateway media proxy, so only absolute URLs are meaningful. */
object MarkdownMediaResolver {
    fun resolveImageUrl(uri: String): String = uri.trim()
}
