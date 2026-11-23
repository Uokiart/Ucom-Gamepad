package com.virtualcontroller.android.ipc;

interface IInputEventListener {
    void onInputFrame(in byte[] report);
    void onRawInputEvent(String deviceName, int type, int code, int value);
}
