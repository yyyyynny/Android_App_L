package com.langsense.app.util

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice

/**
 * 외장(하드웨어) 키보드 연결 여부 감지 (추가 기능 2).
 *
 * ### 감지 방식 선택 근거
 * - `Configuration.keyboard` 는 키보드 "타입"만 알려주고 개발자 옵션·도킹 상태에 흔들리며 실시간
 *   연결/해제 콜백이 없다.
 * - `InputMethodManager` 는 소프트 키보드(IME) 상태용이라 HW 키보드 연결 판정에 부적합하다.
 * - **`InputManager`+`InputDevice`** 는 기기 단위로 "가상이 아닌 알파벳(QWERTY) 키보드"를 정확히
 *   식별할 수 있고, [InputManager.InputDeviceListener] 로 연결/해제를 실시간 통지받을 수 있어 가장
 *   신뢰성이 높다. 따라서 이를 주 경로로 쓴다(서비스가 `onConfigurationChanged` 백스톱을 추가로 둠).
 *
 * 상태가 바뀔 때만 [onChanged] 를 호출한다(불필요한 갱신 방지).
 */
class HardwareKeyboardDetector(
    context: Context,
    private val onChanged: () -> Unit
) {
    private val appContext = context.applicationContext
    private val inputManager =
        appContext.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val handler = Handler(Looper.getMainLooper())

    /** 현재 외장 키보드 연결 여부(메인 스레드에서만 접근). */
    var connected: Boolean = false
        private set

    private var registered = false

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = recheck()
        override fun onInputDeviceRemoved(deviceId: Int) = recheck()
        override fun onInputDeviceChanged(deviceId: Int) = recheck()
    }

    /** 리스너 등록 + 현재 상태 캐시. 현재 연결 여부 반환. */
    fun start(): Boolean {
        connected = detect()
        if (!registered) {
            // 등록 실패를 성공으로 기록하면 재시도 없이 감지가 조용히 죽는다(ImeStateDetector 와
            // 같은 이유로 실제 결과를 반영 — 다음 start() 에서 재시도된다).
            registered = runCatching {
                inputManager.registerInputDeviceListener(listener, handler)
            }.isSuccess
        }
        return connected
    }

    fun stop() {
        // 등록 플래그와 무관하게 무조건 해제 시도(부분 실패 상태에서 리스너가 살아남는 누수 방지).
        runCatching { inputManager.unregisterInputDeviceListener(listener) }
        registered = false
    }

    /** 외부 신호(예: onConfigurationChanged)로도 다시 확인할 수 있게 공개. */
    fun recheck() {
        val now = detect()
        if (now != connected) {
            connected = now
            onChanged()
        }
    }

    /** 연결된 입력 기기 중 "가상이 아닌 알파벳 키보드"가 하나라도 있으면 true. */
    private fun detect(): Boolean = runCatching {
        inputManager.inputDeviceIds.any { id ->
            val device = inputManager.getInputDevice(id) ?: return@any false
            qualifies(device.isVirtual, device.keyboardType, device.sources)
        }
    }.getOrDefault(false)

    companion object {
        /**
         * "진짜 타이핑용 외장 키보드"인지 판정하는 순수 함수(단위 테스트 가능).
         *
         * - 가상 기기(시스템이 항상 만드는 device id -1)는 제외.
         * - 전체 자판(알파벳) 타입만 인정(게임패드 등 비알파벳 SOURCE_KEYBOARD 오탐 방지).
         * - SOURCE_KEYBOARD 비트를 함께 확인.
         */
        fun qualifies(isVirtual: Boolean, keyboardType: Int, sources: Int): Boolean {
            if (isVirtual) return false
            if (keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
            return (sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
        }
    }
}
