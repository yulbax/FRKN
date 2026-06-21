package io.github.yulbax.frkn.ui.screens.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import io.github.yulbax.frkn.R
import io.github.yulbax.frkn.ui.components.DropdownSetting

private val LANGUAGE_TAGS = listOf("en", "ru", "zh", "fa")
private val LANGUAGE_NAMES = mapOf(
    "en" to "English",
    "ru" to "Русский",
    "zh" to "中文",
    "fa" to "فارسی"
)

@Composable
internal fun LanguagePicker() {
    val systemLabel = stringResource(R.string.language_system)
    val labels = remember(systemLabel) {
        listOf(systemLabel) + LANGUAGE_TAGS.map { LANGUAGE_NAMES.getValue(it) }
    }
    val currentTag = AppCompatDelegate.getApplicationLocales()[0]?.language ?: ""
    val selected = LANGUAGE_NAMES[currentTag] ?: systemLabel

    DropdownSetting(
        label = stringResource(R.string.language),
        options = labels,
        selected = selected,
        onSelect = { label ->
            val tag = LANGUAGE_NAMES.entries.firstOrNull { it.value == label }?.key
            AppCompatDelegate.setApplicationLocales(
                if (tag == null) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(tag)
            )
        }
    )
}
