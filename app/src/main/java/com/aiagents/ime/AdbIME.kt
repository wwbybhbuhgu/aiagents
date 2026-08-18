package com.aiagents.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Base64
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.aiagents.R

/**
 * 内置 AI 输入法(融合自 ADBKeyBoard 的 AdbIME)。
 * 通过广播接收文本并提交到当前聚焦的输入框, 供 AI 以输入法方式向其他应用输入文本。
 */
class AdbIME : InputMethodService() {
    private val IME_MESSAGE = "ADB_INPUT_TEXT"
    private val IME_CHARS = "ADB_INPUT_CHARS"
    private val IME_KEYCODE = "ADB_INPUT_CODE"
    private val IME_META_KEYCODE = "ADB_INPUT_MCODE"
    private val IME_EDITORCODE = "ADB_EDITOR_CODE"
    private val IME_MESSAGE_B64 = "ADB_INPUT_B64"
    private val IME_CLEAR_TEXT = "ADB_CLEAR_TEXT"
    private val IME_ACTION_SEARCH = "ADB_ACTION_SEARCH"
    private val IME_ACTION_GO = "ADB_ACTION_GO"
    private val IME_ACTION_DONE = "ADB_ACTION_DONE"
    private val IME_ACTION_NEXT = "ADB_ACTION_NEXT"
    private val IME_ACTION_SEND = "ADB_ACTION_SEND"
    private var mReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        registerAdbReceiver()
    }

    private fun registerAdbReceiver() {
        if (mReceiver != null) return
        val filter = IntentFilter(IME_MESSAGE)
        filter.addAction(IME_CHARS)
        filter.addAction(IME_KEYCODE)
        filter.addAction(IME_MESSAGE)
        filter.addAction(IME_EDITORCODE)
        filter.addAction(IME_MESSAGE_B64)
        filter.addAction(IME_CLEAR_TEXT)
        filter.addAction(IME_ACTION_SEARCH)
        filter.addAction(IME_ACTION_GO)
        filter.addAction(IME_ACTION_DONE)
        filter.addAction(IME_ACTION_NEXT)
        filter.addAction(IME_ACTION_SEND)
        mReceiver = AdbReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: 广播可能来自 shell/adb 或其他 UID, 必须导出
            registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(mReceiver, filter)
        }
    }

    override fun onCreateInputView(): View =
        layoutInflater.inflate(R.layout.ime_view, null)

    override fun onDestroy() {
        mReceiver?.let { unregisterReceiver(it) }
        mReceiver = null
        super.onDestroy()
    }

    inner class AdbReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                IME_MESSAGE -> {
                    val msg = intent.getStringExtra("msg")
                    if (msg != null) {
                        currentInputConnection?.commitText(msg, 1)
                    }
                    val metaCodes = intent.getStringExtra("mcode")
                    if (metaCodes != null) {
                        val mcodes = metaCodes.split(",")
                        val ic = currentInputConnection
                        var i = 0
                        while (i < mcodes.size - 1) {
                            if (ic != null) {
                                val ke: KeyEvent
                                if (mcodes[i].contains("+")) {
                                    val arrCode = mcodes[i].split("\\+".toRegex())
                                    ke = KeyEvent(
                                        0, 0, KeyEvent.ACTION_DOWN,
                                        mcodes[i + 1].toInt(), 0,
                                        arrCode[0].toInt() or arrCode[1].toInt(), 0, 0,
                                        KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                                        InputDevice.SOURCE_KEYBOARD
                                    )
                                } else {
                                    ke = KeyEvent(
                                        0, 0, KeyEvent.ACTION_DOWN,
                                        mcodes[i + 1].toInt(), 0,
                                        mcodes[i].toInt(), 0, 0,
                                        KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                                        InputDevice.SOURCE_KEYBOARD
                                    )
                                }
                                ic.sendKeyEvent(ke)
                            }
                            i += 2
                        }
                    }
                }

                IME_MESSAGE_B64 -> {
                    val data = intent.getStringExtra("msg")
                    val b64 = Base64.decode(data, Base64.DEFAULT)
                    val msg = try {
                        String(b64, Charsets.UTF_8)
                    } catch (e: Exception) {
                        "NOT SUPPORTED"
                    }
                    currentInputConnection?.commitText(msg, 1)
                }

                IME_CHARS -> {
                    val chars = intent.getIntArrayExtra("chars")
                    if (chars != null) {
                        val msg = String(chars, 0, chars.size)
                        currentInputConnection?.commitText(msg, 1)
                    }
                }

                IME_KEYCODE -> {
                    val code = intent.getIntExtra("code", -1)
                    if (code != -1) {
                        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    }
                }

                IME_EDITORCODE -> {
                    val code = intent.getIntExtra("code", -1)
                    if (code != -1) {
                        currentInputConnection?.performEditorAction(code)
                    }
                }

                IME_CLEAR_TEXT -> {
                    val ic = currentInputConnection
                    if (ic != null) {
                        val req = ExtractedTextRequest()
                        req.hintMaxChars = 100000
                        req.hintMaxLines = 10000
                        val et = ic.getExtractedText(req, 0)
                        if (et != null && et.text != null) {
                            val beforePos = ic.getTextBeforeCursor(et.text.length, 0)
                            val afterPos = ic.getTextAfterCursor(et.text.length, 0)
                            if (beforePos != null && afterPos != null) {
                                ic.deleteSurroundingText(beforePos.length, afterPos.length)
                            }
                        } else {
                            ic.performContextMenuAction(android.R.id.selectAll)
                            ic.commitText("", 1)
                        }
                    }
                }

                IME_ACTION_SEARCH -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
                IME_ACTION_GO -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_GO)
                IME_ACTION_DONE -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_DONE)
                IME_ACTION_NEXT -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_NEXT)
                IME_ACTION_SEND -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND)
            }
        }
    }
}
