package io.getaimly.backend.bot

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow



fun btn(text: String, cb: String): InlineKeyboardButton =
    InlineKeyboardButton.builder().text(text).callbackData(cb).build()

fun urlBtn(text: String, url: String): InlineKeyboardButton =
    InlineKeyboardButton.builder().text(url).url(url).text(text).build()

fun row(vararg buttons: InlineKeyboardButton): InlineKeyboardRow =
    InlineKeyboardRow(buttons.toList())

fun keyboard(vararg rows: InlineKeyboardRow): InlineKeyboardMarkup =
    InlineKeyboardMarkup(rows.toList())