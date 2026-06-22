package io.github.yulbax.frkn.ui.screens.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import io.github.yulbax.frkn.R
import io.github.yulbax.frkn.ui.components.DropdownSetting

private enum class AppLanguage(val tag: String, val displayName: String) {
    English("en", "English"),
    Russian("ru", "Русский"),
    Chinese("zh", "中文"),
    Persian("fa", "فارسی");

    companion object {
        fun fromTag(tag: String): AppLanguage? = entries.firstOrNull { it.tag == tag }
        fun fromName(name: String): AppLanguage? = entries.firstOrNull { it.displayName == name }
    }
}

@Composable
internal fun LanguagePicker() {
    val systemLabel = stringResource(R.string.language_system)
    val labels = remember(systemLabel) {
        listOf(systemLabel) + AppLanguage.entries.map { it.displayName }
    }
    val currentTag = AppCompatDelegate.getApplicationLocales()[0]?.language ?: ""
    val selected = AppLanguage.fromTag(currentTag)?.displayName ?: systemLabel

    DropdownSetting(
        label = stringResource(R.string.language),
        options = labels,
        selected = selected,
        onSelect = { label ->
            val language = AppLanguage.fromName(label)
            AppCompatDelegate.setApplicationLocales(
                if (language == null) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(language.tag)
            )
        }
    )
}
