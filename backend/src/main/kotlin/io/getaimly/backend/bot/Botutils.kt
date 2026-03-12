package io.getaimly.backend.bot

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow

fun btn(text: String, cb: String): InlineKeyboardButton =
    InlineKeyboardButton.builder().text(text).callbackData(cb).build()

fun urlBtn(text: String, url: String): InlineKeyboardButton =
    InlineKeyboardButton.builder().text(text).url(url).build()

fun row(vararg buttons: InlineKeyboardButton): InlineKeyboardRow =
    InlineKeyboardRow(buttons.toList())

fun keyboard(vararg rows: InlineKeyboardRow): InlineKeyboardMarkup =
    InlineKeyboardMarkup(rows.toList())


fun String.md(): String = this
    .replace("\\", "\\\\")
    .replace("_", "\\_")
    .replace("*", "\\*")
    .replace("`", "\\`")
    .replace("[", "\\[")


fun <T> pagedKeyboard(
    items: List<T>,
    page: Int,
    pageSize: Int,
    prevCb: String,
    nextCb: String,
    makeRow: (T) -> InlineKeyboardRow,
): Pair<List<T>, InlineKeyboardRow?> {
    val totalPages = (items.size + pageSize - 1) / pageSize
    val safePageSize = items.size.coerceAtMost(pageSize)
    val from = (page * safePageSize).coerceAtMost(items.size)
    val to   = (from + safePageSize).coerceAtMost(items.size)
    val pageItems = items.subList(from, to)

    val navRow: InlineKeyboardRow? = if (totalPages > 1) {
        val navBtns = mutableListOf<InlineKeyboardButton>()
        if (page > 0)               navBtns.add(btn("◀️", prevCb))
        navBtns.add(btn("${page + 1}/$totalPages", "noop"))
        if (page < totalPages - 1)  navBtns.add(btn("▶️", nextCb))
        InlineKeyboardRow(navBtns)
    } else null

    return pageItems to navRow
}