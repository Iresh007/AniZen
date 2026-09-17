/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.kanade.tachiyomi.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.kanade.presentation.player.components.PlayerSheet
import eu.kanade.presentation.player.components.SliderItem
import eu.kanade.presentation.player.components.SwitchPreference
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.pow
import kotlin.math.roundToInt

// ANZ -->
@Composable
fun PlaybackSpeedSheet(
    pitchCorrection: Boolean,
    onPitchCorrectionChange: (Boolean) -> Unit,
    speed: Float,
    speedPresets: List<Float>,
    onSpeedChange: (Float) -> Unit,
    onAddSpeedPreset: (Float) -> Unit,
    onRemoveSpeedPreset: (Float) -> Unit,
    onResetPresets: () -> Unit,
    longPressSpeed: Float,
    longPressSpeedPresets: List<Float>,
    onLongPressSpeedChange: (Float) -> Unit,
    onAddLongPressSpeedPreset: (Float) -> Unit,
    onRemoveLongPressSpeedPreset: (Float) -> Unit,
    onResetLongPressPresets: () -> Unit,
    onMakeDefault: (Float) -> Unit,
    onResetDefault: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = MaterialTheme.padding.medium),
        ) {
            SliderItem(
                label = stringResource(MR.strings.player_sheets_speed_slider_label),
                value = speed,
                valueText = stringResource(MR.strings.player_speed, speed),
                onChange = onSpeedChange,
                max = 6f,
                min = 0.01f,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            ) {
                FilledTonalIconButton(onClick = onResetPresets) {
                    Icon(Icons.Default.RestartAlt, null)
                }
                LazyRow(
                    modifier = Modifier
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    items(speedPresets, key = { "speed-$it" }) {
                        InputChip(
                            selected = speed.toFixed(2) == it.toFixed(2),
                            onClick = { onSpeedChange(it) },
                            label = { Text(stringResource(MR.strings.player_speed, it)) },
                            modifier = Modifier
                                .animateItem(),
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    null,
                                    modifier = Modifier.clickable { onRemoveSpeedPreset(it.toFixed(2)) },
                                )
                            },
                        )
                    }
                }
                FilledTonalIconButton(onClick = { onAddSpeedPreset(speed.toFixed(2)) }) {
                    Icon(Icons.Default.Add, null)
                }
            }

            SliderItem(
                label = stringResource(AMR.strings.player_sheets_speed_long_press),
                value = longPressSpeed,
                valueText = stringResource(MR.strings.player_speed, longPressSpeed),
                onChange = onLongPressSpeedChange,
                max = 6f,
                min = 0.01f,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            ) {
                FilledTonalIconButton(onClick = onResetLongPressPresets) {
                    Icon(Icons.Default.RestartAlt, null)
                }
                LazyRow(
                    modifier = Modifier
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    items(longPressSpeedPresets, key = { "lp-speed-$it" }) {
                        InputChip(
                            selected = longPressSpeed.toFixed(2) == it.toFixed(2),
                            onClick = { onLongPressSpeedChange(it) },
                            label = { Text(stringResource(MR.strings.player_speed, it)) },
                            modifier = Modifier
                                .animateItem(),
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    null,
                                    modifier = Modifier.clickable { onRemoveLongPressSpeedPreset(it.toFixed(2)) },
                                )
                            },
                        )
                    }
                }
                FilledTonalIconButton(onClick = { onAddLongPressSpeedPreset(longPressSpeed.toFixed(2)) }) {
                    Icon(Icons.Default.Add, null)
                }
            }

            SwitchPreference(
                value = pitchCorrection,
                onValueChange = onPitchCorrectionChange,
                content = {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(MR.strings.pref_audio_pitch_correction_title))
                        Text(
                            text = stringResource(MR.strings.pref_audio_pitch_correction_summary),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                modifier = Modifier
                    .padding(MaterialTheme.padding.medium),
            )
            Row(
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.medium),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { onMakeDefault(speed) },
                ) {
                    Text(text = stringResource(MR.strings.player_sheets_speed_make_default))
                }
                FilledIconButton(onClick = onResetDefault) {
                    Icon(imageVector = Icons.Default.RestartAlt, contentDescription = null)
                }
            }
        }
    }
}

fun Float.toFixed(precision: Int = 1): Float {
    val factor = 10.0f.pow(precision)
    return (this * factor).roundToInt() / factor
}
// ANZ <--
