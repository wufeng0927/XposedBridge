package de.robv.android.xposed.TestPackage;



import android.os.Build;
import android.util.Log;

import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook com.android.server.pm.PackageManagerService to grant permissions.
 */
public class PermissionGranterHook   {
	
//	private static final String TAG = PermissionGranterHook.class.getSimpleName();

    private static final String TAG = XposedBridge.TAG;

    private static final String CLASS_PACKAGE_MANAGER_SERVICE = "com.android.server.pm.PackageManagerService";
    
    //PackageParser.Package结构
    //https://android.googlesource.com/platform/frameworks/base/+/0e2d281/core/java/android/content/pm/PackageParser.java#3350
    private static final String CLASS_PACKAGE_PARSER_PACKAGE = "android.content.pm.PackageParser.Package";

    private static final List<String> PERMISSIONS_TO_GRANT = PermConst.PERMISSIONS_TO_GRANT;


    public static void onLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
    	
    	Log.i(XposedBridge.TAG, "lpparam.packageName:"+lpparam.packageName);

        if ("android".equals(lpparam.packageName) && "android".equals(lpparam.processName)) {
            try {
                hookPackageManagerService(lpparam);
            } catch (Exception e) {
                Log.e(TAG, e.getMessage()+"");
            }
        }
    	
        
    }

    private static void hookPackageManagerService(XC_LoadPackage.LoadPackageParam lpparam) {
        hookGrantPermissionsLPw(lpparam);
    }

    private static void hookGrantPermissionsLPw(XC_LoadPackage.LoadPackageParam lpparam) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            hookGrantPermissionsLPwSinceLollipop(lpparam);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            hookGrantPermissionsLPwSinceKitkat(lpparam);
        }
    }

    private static void hookGrantPermissionsLPwSinceKitkat(XC_LoadPackage.LoadPackageParam lpparam) {
    	Log.e(TAG, "Hooking grantPermissionsLPw() for Android 19+");
        XposedHelpers.findAndHookMethod(CLASS_PACKAGE_MANAGER_SERVICE, lpparam.classLoader, "grantPermissionsLPw",
                /* PackageParser.Package pkg */ CLASS_PACKAGE_PARSER_PACKAGE,
                /* boolean replace           */ boolean.class,
                new GrantPermissionsLPwHook());
    }

    private static void hookGrantPermissionsLPwSinceLollipop(XC_LoadPackage.LoadPackageParam lpparam) {
    	Log.e(TAG, "Hooking grantPermissionsLPw() for Android 21+");
    	
    	final Class<?> pmServiceClass = XposedHelpers.findClass(CLASS_PACKAGE_MANAGER_SERVICE, lpparam.classLoader);
    	XposedHelpers.findAndHookMethod(pmServiceClass, "grantPermissionsLPw",
                /* PackageParser.Package pkg */ CLASS_PACKAGE_PARSER_PACKAGE,
                /* boolean replace           */ boolean.class,
                /* String packageOfInterest  */ String.class,
                new GrantPermissionsLPwHook());
    }

    private static class GrantPermissionsLPwHook extends XC_MethodHook {

        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    afterGrantPermissionsLPwHandlerSinceM(param);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    afterGrantPermissionsLPwHandlerSinceKitkat(param);
                }
            } catch (Exception e) {
            	Log.e(TAG, "Hook grantPermissionsLPw() failed", e);
            }
        }

    }

    @SuppressWarnings("unchecked")
    private static void afterGrantPermissionsLPwHandlerSinceM(XC_MethodHook.MethodHookParam param) {
        // android.content.pm.PackageParser.Package 对象
        Object pkg = param.args[0];

        final String packageName = (String) XposedHelpers.getObjectField(pkg, "packageName");

        if (PermConst.PACKAGE_TO_GRANT.contains(packageName)) {
            // PackageParser$Package.mExtras 实际上是 com.android.server.pm.PackageSetting mExtras 对象
            final Object extras = XposedHelpers.getObjectField(pkg, "mExtras");
            // com.android.server.pm.PermissionsState 对象
            final Object permissionsState = XposedHelpers.callMethod(extras, "getPermissionsState");

            // Demo Manifest.xml 中声明的permission列表
            final List<String> requestedPermissions = (List<String>)
                    XposedHelpers.getObjectField(pkg, "requestedPermissions");
            
            
	        for(String string : requestedPermissions) {
	             Log.i(TAG, "demo申请需要的权限:" + string);
	        }

            // com.android.server.pm.Settings mSettings 对象
            final Object settings = XposedHelpers.getObjectField(param.thisObject, "mSettings");
            // ArrayMap<String, com.android.server.pm.BasePermission> mPermissions 对象
            final Object permissions = XposedHelpers.getObjectField(settings, "mPermissions");

            //模块中已经申明的所有敏感权限
            for (String permissionToGrant : PERMISSIONS_TO_GRANT) {
            	// demo中如果包含该项敏感权限
                if (requestedPermissions.contains(permissionToGrant)) {
                	// 检查该项敏感权限是否已经被grant
                    boolean granted = (boolean) XposedHelpers.callMethod(
                            permissionsState, "hasInstallPermission", permissionToGrant);
                    if (!granted) {
                        // com.android.server.pm.BasePermission bpToGrant
                        final Object bpToGrant = XposedHelpers.callMethod(permissions, "get", permissionToGrant);
                        // 强行将该permission改成grant状态
                        int result = (int) XposedHelpers.callMethod(permissionsState, "grantInstallPermission", bpToGrant);
                        Log.e(TAG, "Add permission " + bpToGrant + "; result = " + result);
                    } else {
                    	Log.e(TAG, "Already have " + permissionToGrant + " permission");
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void afterGrantPermissionsLPwHandlerSinceKitkat(XC_MethodHook.MethodHookParam param) {
        // API 19

        // android.content.pm.PackageParser.Package object
        Object pkg = param.args[0];
        

        final String packageName = (String) XposedHelpers.getObjectField(pkg, "packageName");
        
        if (PermConst.PACKAGE_TO_GRANT.contains(packageName)) {
            // com.android.server.pm.PackageSetting mExtra object
            final Object extra = XposedHelpers.getObjectField(pkg, "mExtras");
            // PackageSetting extends PackageSettingBase
            // PackageSettingBase extends GrantedPermissions
            // Android 4.4~4.4.4 api 19 HashSet<String>
            // Android 5.0 api 21 HashSet<String>
            // Android 5.1 api 22 ArraySet<String>
            final Set<String> grantedPermissions = (Set<String>)
                    XposedHelpers.getObjectField(extra, "grantedPermissions");
            
            Log.i(TAG, "已经通过的权限包名："+packageName);
//            for(String string : grantedPermissions) {
//                Log.i(TAG, "已经通过的权限:" + string);
//            }

            // com.android.server.pm.Settings mSettings object
            final Object settings = XposedHelpers.getObjectField(param.thisObject, "mSettings");
            // HashMap<String, com.android.server.pm.BasePermission> mPermissions obj
            final Object permissions = XposedHelpers.getObjectField(settings, "mPermissions");

            for (String permissionToGrant : PERMISSIONS_TO_GRANT) { //三个地理权限
            	// 已经通过的权限不包含地理位置权限
                if (!grantedPermissions.contains(permissionToGrant)) {
                    // com.android.server.pm.BasePermission
                    final Object bpToGrant = XposedHelpers.
                            callMethod(permissions, "get", permissionToGrant);
                    Log.i(TAG, "重新加入grantedPermissions:" + permissionToGrant);
                    grantedPermissions.add(permissionToGrant);

                    // granted permission gids
                    int[] gpGids = (int[]) XposedHelpers.getObjectField(extra, "gids");
                    // base permission to grant gids
                    int[] bpGids = (int[]) XposedHelpers.getObjectField(bpToGrant, "gids");
                    XposedHelpers.callStaticMethod(
                            param.thisObject.getClass(), "appendInts", gpGids, bpGids);

                    Log.e(TAG, "Add permission " + bpToGrant);
                } else {
                	Log.e(TAG, "Already have " + permissionToGrant + " permission");
                }
            }
        }
    }

}
