package de.robv.android.xposed.TestPackage;

import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

public class test{

    public static void HookPackage(Context context, ClassLoader loader){
        findAndHookMethod("com.example.imeitest.MainActivity",
                loader,
                "getAndroidID",
                Context.class,
                new XC_MethodHook() {

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Log.i(XposedBridge.TAG,"开始劫持了~");
                        Log.i(XposedBridge.TAG,"参数1 = " + param.args[0]);

                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult("AndroidId is already Hooked");
                        Log.i(XposedBridge.TAG,"劫持结束了~");
                    }
                });
    };



}
