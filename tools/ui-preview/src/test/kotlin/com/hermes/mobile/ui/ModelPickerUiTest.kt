package com.hermes.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import com.hermes.mobile.data.model.ModelOption
import com.hermes.mobile.ui.chat.ModelPickerSheet
import com.hermes.mobile.ui.theme.HermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ModelPickerUiTest {
    private val models =
        (1..12).map { ModelOption("big:m$it", "big-model-$it", "大提供方", providerSlug = "big") } +
            listOf(
                ModelOption("small:a", "small-a", "小提供方", providerSlug = "small"),
                ModelOption("small:b", "small-b", "小提供方", providerSlug = "small"),
                ModelOption("plain:x", "no-thinking", "不推理的", providerSlug = "plain", reasoning = false),
            )

    /** 只展开正在用的那组，其它组收起只显示个数；点标题展开；搜索时全部展开。 */
    @Test fun groupsFoldExceptTheCurrentOneAndSearchOpensAll() = runComposeUiTest {
        setContent { HermesTheme { ModelPickerSheet(models, selectedName = "small-b", onSelect = {}, onDismiss = {}) } }
        onNodeWithText("small-b").assertExists()
        onNodeWithText("big-model-1").assertDoesNotExist()
        onNodeWithText("12 个模型").assertExists()
        onNodeWithTag("provider-小提供方").performClick()
        onNodeWithText("small-b").assertDoesNotExist()
        onNodeWithText("2 个模型 · 正在用 small-b").assertExists()
        onNodeWithTag("provider-大提供方").performClick()
        onNodeWithText("big-model-1").assertExists()
        onNodeWithTag("provider-大提供方").performClick()
        onNodeWithText("big-model-1").assertDoesNotExist()

        onNodeWithText("搜索模型或 Provider").performTextInput("model-1")
        onNodeWithText("big-model-12").assertExists()
    }

    /** 推理强度点了就回调，不关面板；当前模型不支持推理时不显示。 */
    @Test fun effortChipsReportChoiceAndHideForNonReasoningModels() = runComposeUiTest {
        var effort by mutableStateOf<String?>(null)
        var selected by mutableStateOf("small-a")
        setContent {
            HermesTheme {
                ModelPickerSheet(models, selectedName = selected, onSelect = {}, onDismiss = {}, effort = effort, onEffort = { effort = it })
            }
        }
        onNodeWithText("推理强度").assertExists()
        onNodeWithTag("effort-high").assertIsNotSelected().performClick()
        assertEquals("high", effort)
        onNodeWithTag("effort-high").assertIsSelected()
        onNodeWithTag("effort-default").performClick()
        assertEquals(null, effort)

        selected = "no-thinking"
        onNodeWithText("推理强度").assertDoesNotExist()
    }
}
