package com.mystudio.borderannounce.circuit

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton // 💡 対策①：インポート漏れを修正しました
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.screen.Screen
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import kotlinx.parcelize.Parcelize

// 💡 対策②：コンパイラが確実に解釈できるよう、データ保持型をファイル最上部に移動しました
data class CheckedSettings(
    val verbosity: String,
    val overridePref: Boolean,
    val overrideCity: Boolean,
    val overrideTown: Boolean
)

@Parcelize
data object HomeScreen : Screen {
    data class State(
        val maxDetail: String,
        val verbosity: String,
        val overridePref: Boolean,
        val overrideCity: Boolean,
        val overrideTown: Boolean,
        val omitSuffix: Boolean,
        val useAudioFocus: Boolean,
        val eventSink: (Event) -> Unit
    ) : CircuitUiState

    sealed interface Event {
        data class ChangeMaxDetail(val value: String) : Event
        data class ChangeVerbosity(val value: String) : Event
        data class ToggleOverridePref(val enabled: Boolean) : Event
        data class ToggleOverrideCity(val enabled: Boolean) : Event
        data class ToggleOverrideTown(val enabled: Boolean) : Event
        data class ToggleOmitSuffix(val enabled: Boolean) : Event
        data class ToggleUseAudioFocus(val enabled: Boolean) : Event
    }
}

private fun getLevelRank(level: String): Int {
    return when (level) {
        "A" -> 1
        "B" -> 2
        "C" -> 3
        "D" -> 4
        else -> 4
    }
}

@CircuitInject(HomeScreen::class, AppScope::class)
@Inject
class HomePresenter
    constructor() : Presenter<HomeScreen.State> {
        @Composable
        override fun present(): HomeScreen.State {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
            
            var maxDetail by remember { mutableStateOf(prefs.getString("max_detail_level", "D") ?: "D") }
            var verbosity by remember { mutableStateOf(prefs.getString("verbosity_level", "C") ?: "C") }
            var overridePref by remember { mutableStateOf(prefs.getBoolean("override_pref", true)) }
            var overrideCity by remember { mutableStateOf(prefs.getBoolean("override_city", true)) }
            var overrideTown by remember { mutableStateOf(prefs.getBoolean("override_town", true)) }
            var omitSuffix by remember { mutableStateOf(prefs.getBoolean("omit_suffix", false)) }
            var useAudioFocus by remember { mutableStateOf(prefs.getBoolean("audio_focus", true)) }

            fun enforceLogicalConstraints(
                currMax: String,
                currVerb: String,
                currOverPref: Boolean,
                currOverCity: Boolean,
                currOverTown: Boolean
            ): CheckedSettings {
                val maxRank = getLevelRank(currMax)
                var resolvedVerb = currVerb
                if (getLevelRank(currVerb) > maxRank) {
                    resolvedVerb = currMax
                }

                val verbRank = getLevelRank(resolvedVerb)
                val canPref = verbRank >= 2
                val canCity = verbRank >= 3
                val canTown = verbRank >= 4

                // 初期状態の有効化フィルタ
                var resolvedOverPref = if (canPref) currOverPref else false
                var resolvedOverCity = if (canCity) currOverCity else false
                var resolvedOverTown = if (canTown) currOverTown else false

                // 下位がOFFなら、自動的にそれより上位もOFFにする
                if (canTown && !resolvedOverTown) {
                    resolvedOverCity = false
                    resolvedOverPref = false
                }
                if (canCity && !resolvedOverCity) {
                    resolvedOverPref = false
                }

                return CheckedSettings(resolvedVerb, resolvedOverPref, resolvedOverCity, resolvedOverTown)
            }

            val checked = enforceLogicalConstraints(maxDetail, verbosity, overridePref, overrideCity, overrideTown)
            if (checked.verbosity != verbosity || checked.overridePref != overridePref || checked.overrideCity != overrideCity || checked.overrideTown != overrideTown) {
                verbosity = checked.verbosity
                overridePref = checked.overridePref
                overrideCity = checked.overrideCity
                overrideTown = checked.overrideTown
                
                prefs.edit()
                    .putString("verbosity_level", verbosity)
                    .putBoolean("override_pref", overridePref)
                    .putBoolean("override_city", overrideCity)
                    .putBoolean("override_town", overrideTown)
                    .apply()
            }

            return HomeScreen.State(
                maxDetail = maxDetail,
                verbosity = verbosity,
                overridePref = overridePref,
                overrideCity = overrideCity,
                overrideTown = overrideTown,
                omitSuffix = omitSuffix,
                useAudioFocus = useAudioFocus,
                eventSink = { event ->
                    when (event) {
                        is HomeScreen.Event.ChangeMaxDetail -> {
                            maxDetail = event.value
                            val res = enforceLogicalConstraints(event.value, verbosity, overridePref, overrideCity, overrideTown)
                            verbosity = res.verbosity
                            overridePref = res.overridePref
                            overrideCity = res.overrideCity
                            overrideTown = res.overrideTown

                            prefs.edit()
                                .putString("max_detail_level", event.value)
                                .putString("verbosity_level", verbosity)
                                .putBoolean("override_pref", overridePref)
                                .putBoolean("override_city", overrideCity)
                                .putBoolean("override_town", overrideTown)
                                .apply()
                        }
                        
                        is HomeScreen.Event.ChangeVerbosity -> {
                            val oldVerbosity = verbosity
                            verbosity = event.value
                            
                            val oldRank = getLevelRank(oldVerbosity)
                            val newRank = getLevelRank(event.value)
                            if (newRank > oldRank) {
                                if (oldRank < 2 && newRank >= 2) overridePref = true
                                if (oldRank < 3 && newRank >= 3) overrideCity = true
                                if (oldRank < 4 && newRank >= 4) overrideTown = true
                            }

                            val res = enforceLogicalConstraints(maxDetail, event.value, overridePref, overrideCity, overrideTown)
                            overridePref = res.overridePref
                            overrideCity = res.overrideCity
                            overrideTown = res.overrideTown

                            prefs.edit()
                                .putString("verbosity_level", verbosity)
                                .putBoolean("override_pref", overridePref)
                                .putBoolean("override_city", overrideCity)
                                .putBoolean("override_town", overrideTown)
                                .apply()
                        }
                        
                        is HomeScreen.Event.ToggleOverridePref -> {
                            val enabled = event.enabled
                            overridePref = enabled
                            if (enabled) {
                                val verbRank = getLevelRank(verbosity)
                                if (verbRank >= 3) overrideCity = true
                                if (verbRank >= 4) overrideTown = true
                            }
                            prefs.edit()
                                .putBoolean("override_pref", overridePref)
                                .putBoolean("override_city", overrideCity)
                                .putBoolean("override_town", overrideTown)
                                .apply()
                        }
                        
                        is HomeScreen.Event.ToggleOverrideCity -> {
                            val enabled = event.enabled
                            overrideCity = enabled
                            if (enabled) {
                                val verbRank = getLevelRank(verbosity)
                                if (verbRank >= 4) overrideTown = true
                            } else {
                                overridePref = false
                            }
                            prefs.edit()
                                .putBoolean("override_pref", overridePref)
                                .putBoolean("override_city", overrideCity)
                                .putBoolean("override_town", overrideTown)
                                .apply()
                        }
                        
                        is HomeScreen.Event.ToggleOverrideTown -> {
                            val enabled = event.enabled
                            overrideTown = enabled
                            if (!enabled) {
                                overridePref = false
                                overrideCity = false
                            }
                            prefs.edit()
                                .putBoolean("override_pref", overridePref)
                                .putBoolean("override_city", overrideCity)
                                .putBoolean("override_town", overrideTown)
                                .apply()
                        }
                        is HomeScreen.Event.ToggleOmitSuffix -> {
                            omitSuffix = event.enabled
                            prefs.edit().putBoolean("omit_suffix", event.enabled).apply()
                        }
                        is HomeScreen.Event.ToggleUseAudioFocus -> {
                            useAudioFocus = event.enabled
                            prefs.edit().putBoolean("audio_focus", event.enabled).apply()
                        }
                    }
                }
            )
        }
    }

@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun HomeContent(
    state: HomeScreen.State,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(24.dp)
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            Text(
                text = "ボーダーアナウンス設定",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))

            // 設定項目①：詳細度
            Text(
                text = "設定①：アナウンスの詳細度",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = "住所のどの深さまで情報を知りたいか",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val maxDetailLevels = listOf(
                "A" to "都道府県まで",
                "B" to "市区町村まで",
                "C" to "町名（大字）まで",
                "D" to "丁目まで"
            )
            maxDetailLevels.forEach { (code, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { state.eventSink(HomeScreen.Event.ChangeMaxDetail(code)) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (state.maxDetail == code),
                        onClick = { state.eventSink(HomeScreen.Event.ChangeMaxDetail(code)) }
                    )
                    Text(text = label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // 設定項目②：冗長度
            Text(
                text = "設定②：基本の読み上げ開始レベル",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = "住所のどこから読み上げを開始するか",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val verbosityLevels = listOf(
                "A" to "都道府県から",
                "B" to "市区町村から",
                "C" to "町名から",
                "D" to "丁目のみ"
            )
            verbosityLevels.forEach { (code, label) ->
                val isEnabled = getLevelRank(code) <= getLevelRank(state.maxDetail)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isEnabled) { state.eventSink(HomeScreen.Event.ChangeVerbosity(code)) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        enabled = isEnabled,
                        selected = (state.verbosity == code),
                        onClick = { state.eventSink(HomeScreen.Event.ChangeVerbosity(code)) }
                    )
                    Text(
                        text = label, 
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isEnabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // 設定項目③：例外ルール
            Text(
                text = "設定③：上位境界を跨いだときの例外設定",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = "より大きな境界を越えたときだけ、例外的にフルアドレスに拡張するか",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val verbRank = getLevelRank(state.verbosity)

            // 県境例外
            val isPrefOverrideEnabled = verbRank >= 2
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isPrefOverrideEnabled) { state.eventSink(HomeScreen.Event.ToggleOverridePref(!state.overridePref)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    enabled = isPrefOverrideEnabled,
                    checked = state.overridePref,
                    onCheckedChange = { state.eventSink(HomeScreen.Event.ToggleOverridePref(it)) }
                )
                Text(
                    text = "都道府県を跨いだ際は、都道府県から読み上げる",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isPrefOverrideEnabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline
                )
            }

            // 市境例外
            val isCityOverrideEnabled = verbRank >= 3
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isCityOverrideEnabled) { state.eventSink(HomeScreen.Event.ToggleOverrideCity(!state.overrideCity)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    enabled = isCityOverrideEnabled,
                    checked = state.overrideCity,
                    onCheckedChange = { state.eventSink(HomeScreen.Event.ToggleOverrideCity(it)) }
                )
                Text(
                    text = "市区町村を跨いだ際は、市区町村から読み上げる",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCityOverrideEnabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline
                )
            }

            // 町境例外
            val isTownOverrideEnabled = verbRank >= 4
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isTownOverrideEnabled) { state.eventSink(HomeScreen.Event.ToggleOverrideTown(!state.overrideTown)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    enabled = isTownOverrideEnabled,
                    checked = state.overrideTown,
                    onCheckedChange = { state.eventSink(HomeScreen.Event.ToggleOverrideTown(it)) }
                )
                Text(
                    text = "町名（大字）を跨いだ際は、町名から読み上げる",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isTownOverrideEnabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // 追加：設定項目「〜に入りました」を省略する
            Text(
                text = "発話スタイルのカスタマイズ",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { state.eventSink(HomeScreen.Event.ToggleOmitSuffix(!state.omitSuffix)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "「に入りました」を省略する",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "境界を跨いだ際、地名（住所）のみをアナウンスします",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = state.omitSuffix,
                    onCheckedChange = { state.eventSink(HomeScreen.Event.ToggleOmitSuffix(it)) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 設定項目：オーディオフォーカス
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { state.eventSink(HomeScreen.Event.ToggleUseAudioFocus(!state.useAudioFocus)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "他アプリの音量を下げる (ダッキング)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "アナウンス時に音楽などの音量を一時的に下げます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = state.useAudioFocus,
                    onCheckedChange = { state.eventSink(HomeScreen.Event.ToggleUseAudioFocus(it)) }
                )
            }

            if (getLevelRank(state.maxDetail) >= 4) {
                Text(
                    text = "※一部の特殊な地名表記においては、丁目の変化を正しく検出できない場合があります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 24.dp, start = 4.dp, end = 4.dp)
                )
            }
        }
    }
}