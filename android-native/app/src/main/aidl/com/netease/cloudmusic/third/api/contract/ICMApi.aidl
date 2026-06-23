package com.netease.cloudmusic.third.api.contract;

import android.os.Bundle;

interface ICMApi {
    Bundle execute(String cmd, in Bundle params);
    void executeAsync(String cmd, String subCmd, in Bundle params, IBinder callback);
    void registerEventListener(IBinder listener);
    void unregisterEventListener(IBinder listener);
}
