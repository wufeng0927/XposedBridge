package de.robv.android.xposed;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.app.LoadedApk;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XResources;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.os.ZygoteInit;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;
import de.robv.android.xposed.TestPackage.PermissionGranterHook;
import de.robv.android.xposed.TestPackage.test;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.callbacks.XCallback;
import de.robv.android.xposed.services.BaseService;

import static de.robv.android.xposed.XposedBridge.hookAllConstructors;
import static de.robv.android.xposed.XposedBridge.hookAllMethods;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.closeSilently;
import static de.robv.android.xposed.XposedHelpers.fileContains;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findFieldIfExists;
import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.getOverriddenMethods;
import static de.robv.android.xposed.XposedHelpers.getParameterIndexByType;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static de.robv.android.xposed.XposedHelpers.setStaticBooleanField;
import static de.robv.android.xposed.XposedHelpers.setStaticLongField;
import static de.robv.android.xposed.XposedHelpers.setStaticObjectField;

/*package*/ final class XposedInit {
	private static final String TAG = XposedBridge.TAG;

	private static final boolean startsSystemServer = XposedBridge.startsSystemServer();
	private static final String startClassName = XposedBridge.getStartClassName();

	private static final String INSTALLER_PACKAGE_NAME = "de.robv.android.xposed.installer";
	@SuppressLint("SdCardPath")
	private static final String BASE_DIR = Build.VERSION.SDK_INT >= 24
			? "/data/user_de/0/" + INSTALLER_PACKAGE_NAME + "/"
			: "/data/data/" + INSTALLER_PACKAGE_NAME + "/";
	private static final String INSTANT_RUN_CLASS = "com.android.tools.fd.runtime.BootstrapApplication";

	private static boolean disableResources = false;
	private static final String[] XRESOURCES_CONFLICTING_PACKAGES = { "com.sygic.aura" };

	private XposedInit() {}

	/**
	 * Hook some methods which we want to create an easier interface for developers.
	 */
	/*package*/ static void initForZygote() throws Throwable {
		Log.i(TAG, "initForZygote>>>>>>");
		if (needsToCloseFilesForFork()) {
			XC_MethodHook callback = new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					XposedBridge.closeFilesBeforeForkNative();
				}

				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					XposedBridge.reopenFilesAfterForkNative();
				}
			};

			Class<?> zygote = findClass("com.android.internal.os.Zygote", null);
			// 对使用art虚拟机的机器，需要hook Zygote 的nativeForkAndSpecialize 及nativeForkSystemServer 方法，
			// 在调用他们之前，记录下文件描述符表，在fork完成后，重新打开这些文件。防止文件被system意外关闭
			hookAllMethods(zygote, "nativeForkAndSpecialize", callback);
			hookAllMethods(zygote, "nativeForkSystemServer", callback);
		}

		final HashSet<String> loadedPackagesInProcess = new HashSet<>(1);

		Log.i(TAG, "before findAndHookMethod>>>>>>");
		// https://paper.tuisec.win/detail-70011a2328b2266.html
		// 挂钩了ActivityThread 类的 handleBindApplication 函数，这个函数是在android ams 系统创建新进程成功后在新进程内部调用的，挂钩这个函数，可以在新进程创建后做一些事情
		// normal process initialization (for new Activity, Service, BroadcastReceiver etc.)
		// handleBindApplication主要工作是初始化APP（APP由zygote进程fork而来，在hanldeBindApplication之前，这个APP进程和zygote没什么区别。只有调用完handleBindApplication之后，这个APP进程才是APP,比如该进程有了对应的名字，Aplication对象被创建等）。
		findAndHookMethod(ActivityThread.class, "handleBindApplication", "android.app.ActivityThread.AppBindData", new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {

				//调用所有的xposed插件（IXposedHookLoadPackage）
				XposedInit.loadModules(true);

				ActivityThread activityThread = (ActivityThread) param.thisObject;
				ApplicationInfo appInfo = (ApplicationInfo) getObjectField(param.args[0], "appInfo");
				String reportedPackageName = appInfo.packageName.equals("android") ? "system" : appInfo.packageName;
				Log.i(TAG, "reportedPackageName:"+reportedPackageName+">>>>>>");




				SELinuxHelper.initForProcess(reportedPackageName);
				ComponentName instrumentationName = (ComponentName) getObjectField(param.args[0], "instrumentationName");
				if (instrumentationName != null) {
					Log.w(TAG, "Instrumentation detected, disabling framework for " + reportedPackageName);
					XposedBridge.disableHooks = true;
					return;
				}
				CompatibilityInfo compatInfo = (CompatibilityInfo) getObjectField(param.args[0], "compatInfo");
				if (appInfo.sourceDir == null)
					return;

				setObjectField(activityThread, "mBoundApplication", param.args[0]);
				loadedPackagesInProcess.add(reportedPackageName);
				LoadedApk loadedApk = activityThread.getPackageInfoNoCheck(appInfo, compatInfo);
				XResources.setPackageNameForResDir(appInfo.packageName, loadedApk.getResDir());

				// XposedBridge 会遍历 set- sLoadedPackageCallbacks 中的所有钩子函数(模块中定义的)，并进行回调
				final XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
				lpparam.packageName = reportedPackageName;
				lpparam.processName = (String) getObjectField(param.args[0], "processName");
				lpparam.classLoader = loadedApk.getClassLoader();
				lpparam.appInfo = appInfo;
				lpparam.isFirstApplication = true;

				// 这里实际上就是顺序调用XposedBridge.sLoadedPackageCallbacks的call方法，这是1个集合，
				// 里面保存的即时现在加载进来的所有钩子，目前为止我们只挂上了资源钩子。
				XC_LoadPackage.callAll(lpparam);


				//直接hook指定包
				if(!TextUtils.isEmpty(reportedPackageName)
						&& "com.example.imeitest".equals(reportedPackageName)){
					Log.i(TAG, "Hook "+reportedPackageName +" Begin>>>>>>");

                    //将loadPackageParam的classloader替换为宿主程序Application的classloader,解决宿主程序存在多个.dex文件时,有时候ClassNotFound的问题
                    XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Context context=(Context) param.args[0];
                            lpparam.classLoader = context.getClassLoader();

							Log.i(TAG, "Hook appClz：" + context +" Begin>>>>>>");
							Log.i(TAG, "Hook loader：" + context.getClassLoader() +" Begin>>>>>>");

                            test.HookPackage(context.getClassLoader());

                        }
                    });
				}
				if (reportedPackageName.equals(INSTALLER_PACKAGE_NAME))
					hookXposedInstaller(lpparam.classLoader);
			}
		});



		Log.i(XposedBridge.TAG,"startsSystemServer:"+startsSystemServer);

		// system_server initialization
        // Android应用框架中的各种Service，例如ActivityManagerService，PacakgeManagerService，WindowManagerService等等重要的系统服务都是在SystemServer中启动的
		// initAndLoop是system_server进程的关键函数，在这个函数里Android Framework的绝大部分Service都将被创建。
		// 插件函数如果想区分被hook的进程是否为system_server的话，只需要判断packageName是否为"android"即可。
		// system_server是Android Java层Framework的核心，不到万不得已，不要hook system_sever
		if (Build.VERSION.SDK_INT < 21) {
			findAndHookMethod("com.android.server.ServerThread", null,
					Build.VERSION.SDK_INT < 19 ? "run" : "initAndLoop", new XC_MethodHook() {
						@Override
						protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
							SELinuxHelper.initForProcess("android");
							loadedPackagesInProcess.add("android");

							// 和handleBindApplication类似，也是由IXposedHookLoadPackage类型的钩子进行处理
							XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
							lpparam.packageName = "android";
							lpparam.processName = "android"; // it's actually system_server, but other functions return this as well
							lpparam.classLoader = XposedBridge.BOOTCLASSLOADER;
							lpparam.appInfo = null;
							lpparam.isFirstApplication = true;
							XC_LoadPackage.callAll(lpparam);
						}
					});
		} else if (startsSystemServer) {
			findAndHookMethod(ActivityThread.class, "systemMain", new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					final ClassLoader cl = Thread.currentThread().getContextClassLoader();
					findAndHookMethod("com.android.server.SystemServer", cl, "startBootstrapServices", new XC_MethodHook() {
						@Override
						protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
							SELinuxHelper.initForProcess("android");
							loadedPackagesInProcess.add("android");

							XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
							lpparam.packageName = "android";
							lpparam.processName = "android"; // it's actually system_server, but other functions return this as well
							lpparam.classLoader = cl;
							lpparam.appInfo = null;
							lpparam.isFirstApplication = true;
							XC_LoadPackage.callAll(lpparam);

							// Hook permission - PackageManagerService
							Log.i(XposedBridge.TAG, "PermissionGranterHook begin...");
							PermissionGranterHook.onLoadPackage(lpparam);


							// Huawei
							try {
								findAndHookMethod("com.android.server.pm.HwPackageManagerService", cl, "isOdexMode", XC_MethodReplacement.returnConstant(false));
							} catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError ignored) {}

							try {
								String className = "com.android.server.pm." + (Build.VERSION.SDK_INT >= 23 ? "PackageDexOptimizer" : "PackageManagerService");
								findAndHookMethod(className, cl, "dexEntryExists", String.class, XC_MethodReplacement.returnConstant(true));

							} catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError ignored) {}
						}
					});
				}
			});
		}

		// when a package is loaded for an existing process, trigger the callbacks as well
		// 除了handleBindApplication之外，由于一个APP进程事实上可以加载多个APK（比如那些申明同样的uid和运行在同一进程的APP），
		// 在LoadedApk的构造函数中也做了跟handBindApplication类似的处理
        // system进程和app进程都运行着一个或多个app，每个app都会有一个对应的Application对象(该对象跟LoadedApk一一对应)
		hookAllConstructors(LoadedApk.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				LoadedApk loadedApk = (LoadedApk) param.thisObject;

				String packageName = loadedApk.getPackageName();
				XResources.setPackageNameForResDir(packageName, loadedApk.getResDir());
				if (packageName.equals("android") || !loadedPackagesInProcess.add(packageName))
					return;

				if (!getBooleanField(loadedApk, "mIncludeCode"))
					return;

				XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
				lpparam.packageName = packageName;
				lpparam.processName = AndroidAppHelper.currentProcessName();
				lpparam.classLoader = loadedApk.getClassLoader();
				lpparam.appInfo = loadedApk.getApplicationInfo();
				lpparam.isFirstApplication = false;
				XC_LoadPackage.callAll(lpparam);
			}
		});

		findAndHookMethod("android.app.ApplicationPackageManager", null, "getResourcesForApplication",
				ApplicationInfo.class, new XC_MethodHook() {
					@Override
					protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
						ApplicationInfo app = (ApplicationInfo) param.args[0];
						XResources.setPackageNameForResDir(app.packageName,
								app.uid == Process.myUid() ? app.sourceDir : app.publicSourceDir);
					}
				});

		// MIUI
		if (findFieldIfExists(ZygoteInit.class, "BOOT_START_TIME") != null) {
			setStaticLongField(ZygoteInit.class, "BOOT_START_TIME", XposedBridge.BOOT_START_TIME);
		}

		// Samsung
		if (Build.VERSION.SDK_INT >= 24) {
			Class<?> zygote = findClass("com.android.internal.os.Zygote", null);
			try {
				setStaticBooleanField(zygote, "isEnhancedZygoteASLREnabled", false);
			} catch (NoSuchFieldError ignored) {
			}
		}
	}

	// hookResources主要是对ResourcesManager的getTopLevelResources进行了Hook。
	// APP中原来使用的是Resources代表资源，Hook之后，Xposed用XResources代替了Resources
	/*package*/ static void hookResources() throws Throwable {
		if (SELinuxHelper.getAppDataFileService().checkFileExists(BASE_DIR + "conf/disable_resources")) {
			Log.w(TAG, "Found " + BASE_DIR + "conf/disable_resources, not hooking resources");
			disableResources = true;
			return;
		}

		if (!XposedBridge.initXResourcesNative()) {
			Log.e(TAG, "Cannot hook resources");
			disableResources = true;
			return;
		}

		/*
		 * getTopLevelResources(a)
		 *   -> getTopLevelResources(b)
		 *     -> key = new ResourcesKey()
		 *     -> r = new Resources()
		 *     -> mActiveResources.put(key, r)
		 *     -> return r
		 */

		final Class<?> classGTLR;
		final Class<?> classResKey;
		final ThreadLocal<Object> latestResKey = new ThreadLocal<>();

		if (Build.VERSION.SDK_INT <= 18) {
			classGTLR = ActivityThread.class;
			classResKey = Class.forName("android.app.ActivityThread$ResourcesKey");
		} else {
			classGTLR = Class.forName("android.app.ResourcesManager");
			classResKey = Class.forName("android.content.res.ResourcesKey");
		}

		if (Build.VERSION.SDK_INT >= 24) {
			hookAllMethods(classGTLR, "getOrCreateResources", new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					// At least on OnePlus 5, the method has an additional parameter compared to AOSP.
					final int activityTokenIdx = getParameterIndexByType(param.method, IBinder.class);
					final int resKeyIdx = getParameterIndexByType(param.method, classResKey);

					String resDir = (String) getObjectField(param.args[resKeyIdx], "mResDir");
					XResources newRes = cloneToXResources(param, resDir);
					if (newRes == null) {
						return;
					}

					Object activityToken = param.args[activityTokenIdx];
					synchronized (param.thisObject) {
						ArrayList<WeakReference<Resources>> resourceReferences;
						if (activityToken != null) {
							Object activityResources = callMethod(param.thisObject, "getOrCreateActivityResourcesStructLocked", activityToken);
							resourceReferences = (ArrayList<WeakReference<Resources>>) getObjectField(activityResources, "activityResources");
						} else {
							resourceReferences = (ArrayList<WeakReference<Resources>>) getObjectField(param.thisObject, "mResourceReferences");
						}
						resourceReferences.add(new WeakReference(newRes));
					}
				}
			});
		} else {
			hookAllConstructors(classResKey, new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					latestResKey.set(param.thisObject);
				}
			});

			hookAllMethods(classGTLR, "getTopLevelResources", new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					latestResKey.set(null);
				}

				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					Object key = latestResKey.get();
					if (key == null) {
						return;
					}
					latestResKey.set(null);

					String resDir = (String) getObjectField(key, "mResDir");
					XResources newRes = cloneToXResources(param, resDir);
					if (newRes == null) {
						return;
					}

					@SuppressWarnings("unchecked")
					Map<Object, WeakReference<Resources>> mActiveResources =
							(Map<Object, WeakReference<Resources>>) getObjectField(param.thisObject, "mActiveResources");
					Object lockObject = (Build.VERSION.SDK_INT <= 18)
							? getObjectField(param.thisObject, "mPackages") : param.thisObject;

					synchronized (lockObject) {
						WeakReference<Resources> existing = mActiveResources.put(key, new WeakReference<Resources>(newRes));
						if (existing != null && existing.get() != null && existing.get().getAssets() != newRes.getAssets()) {
							existing.get().getAssets().close();
						}
					}
				}
			});

			if (Build.VERSION.SDK_INT >= 19) {
				// This method exists only on CM-based ROMs
				hookAllMethods(classGTLR, "getTopLevelThemedResources", new XC_MethodHook() {
					@Override
					protected void afterHookedMethod(MethodHookParam param) throws Throwable {
						String resDir = (String) param.args[0];
						cloneToXResources(param, resDir);
					}
				});
			}
		}

		// Invalidate callers of methods overridden by XTypedArray
		if (Build.VERSION.SDK_INT >= 24) {
			Set<Method> methods = getOverriddenMethods(XResources.XTypedArray.class);
			XposedBridge.invalidateCallersNative(methods.toArray(new Member[methods.size()]));
		}

		// Replace TypedArrays with XTypedArrays
		hookAllConstructors(TypedArray.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				TypedArray typedArray = (TypedArray) param.thisObject;
				Resources res = typedArray.getResources();
				if (res instanceof XResources) {
					XposedBridge.setObjectClass(typedArray, XResources.XTypedArray.class);
				}
			}
		});

		// Replace system resources
		XResources systemRes = (XResources) XposedBridge.cloneToSubclass(Resources.getSystem(), XResources.class);
		systemRes.initObject(null);
		setStaticObjectField(Resources.class, "mSystem", systemRes);

		XResources.init(latestResKey);
	}

	private static XResources cloneToXResources(XC_MethodHook.MethodHookParam param, String resDir) {
		Object result = param.getResult();
		if (result == null || result instanceof XResources ||
				Arrays.binarySearch(XRESOURCES_CONFLICTING_PACKAGES, AndroidAppHelper.currentPackageName()) == 0) {
			return null;
		}

		// Replace the returned resources with our subclass.
		XResources newRes = (XResources) XposedBridge.cloneToSubclass(result, XResources.class);
		newRes.initObject(resDir);

		// Invoke handleInitPackageResources().
		if (newRes.isFirstLoad()) {
			String packageName = newRes.getPackageName();
			XC_InitPackageResources.InitPackageResourcesParam resparam = new XC_InitPackageResources.InitPackageResourcesParam(XposedBridge.sInitPackageResourcesCallbacks);
			resparam.packageName = packageName;
			resparam.res = newRes;
			XCallback.callAll(resparam);
		}

		param.setResult(newRes);
		return newRes;
	}

	private static boolean needsToCloseFilesForFork() {
		if (Build.VERSION.SDK_INT >= 24) {
			return true;
		} else if (Build.VERSION.SDK_INT < 21) {
			return false;
		}

		File lib = new File(Environment.getRootDirectory(), "lib/libandroid_runtime.so");
		try {
			return fileContains(lib, "Unable to construct file descriptor table");
		} catch (IOException e) {
			Log.e(TAG, "Could not check whether " + lib + " has security patch level 5");
			// In doubt, just do it. The worst case should be unnecessary work and log messages.
			return true;
		}
	}

	private static void hookXposedInstaller(ClassLoader classLoader) {
		try {
			findAndHookMethod(INSTALLER_PACKAGE_NAME + ".XposedApp", classLoader, "getActiveXposedVersion",
					XC_MethodReplacement.returnConstant(XposedBridge.getXposedVersion()));

			findAndHookMethod(INSTALLER_PACKAGE_NAME + ".XposedApp", classLoader, "onCreate", new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					Application application = (Application) param.thisObject;
					Resources res = application.getResources();
					if (res.getIdentifier("installer_needs_update", "string", INSTALLER_PACKAGE_NAME) == 0) {
						// If this resource is missing, take it as indication that the installer is outdated.
						Log.e("XposedInstaller", "Xposed Installer is outdated (resource string \"installer_needs_update\" is missing)");
						Toast.makeText(application, "Please update Xposed Installer!", Toast.LENGTH_LONG).show();
					}
				}
			});
		} catch (Throwable t) { Log.e(TAG, "Could not hook Xposed Installer", t); }
	}

	/**
	 * Try to load all modules defined in <code>BASE_DIR/conf/modules.list</code>
	 */
	/*package*/ static void loadModules(boolean isLoadHookLoadPackage) throws IOException {
		final String filename = BASE_DIR + "conf/modules.list";
		// 举例：data/app/com.example.xposedtest-1/base.apk 模块第一次安装的apk路径
		BaseService service = SELinuxHelper.getAppDataFileService();
		if (!service.checkFileExists(filename)) {
			Log.e(TAG, "Cannot load any modules because " + filename + " was not found");
			return;
		}

		ClassLoader topClassLoader = XposedBridge.BOOTCLASSLOADER;
		ClassLoader parent;
		while ((parent = topClassLoader.getParent()) != null) {
			topClassLoader = parent;
		}

		InputStream stream = service.getFileInputStream(filename);
		BufferedReader apks = new BufferedReader(new InputStreamReader(stream));
		String apk;
		while ((apk = apks.readLine()) != null) {
			loadModule(isLoadHookLoadPackage, apk, topClassLoader);
		}
		apks.close();
	}

	/**
	 * Load a module from an APK by calling the init(String) method for all classes defined
	 * in <code>assets/xposed_init</code>.
	 */
	private static void loadModule(boolean isLoadHookLoadPackage, String apk, ClassLoader topClassLoader) {
		Log.i(TAG, "Loading modules from " + apk);

		if (!new File(apk).exists()) {
			Log.e(TAG, "  File does not exist");
			return;
		}

		DexFile dexFile;
		try {
			dexFile = new DexFile(apk);
		} catch (IOException e) {
			Log.e(TAG, "  Cannot load module", e);
			return;
		}

		if (dexFile.loadClass(INSTANT_RUN_CLASS, topClassLoader) != null) {
			Log.e(TAG, "  Cannot load module, please disable \"Instant Run\" in Android Studio.");
			closeSilently(dexFile);
			return;
		}

		if (dexFile.loadClass(XposedBridge.class.getName(), topClassLoader) != null) {
			Log.e(TAG, "  Cannot load module:");
			Log.e(TAG, "  The Xposed API classes are compiled into the module's APK.");
			Log.e(TAG, "  This may cause strange issues and must be fixed by the module developer.");
			Log.e(TAG, "  For details, see: http://api.xposed.info/using.html");
			closeSilently(dexFile);
			return;
		}

		closeSilently(dexFile);

		ZipFile zipFile = null;
		InputStream is;
		try {
			zipFile = new ZipFile(apk);
			ZipEntry zipEntry = zipFile.getEntry("assets/xposed_init");
			if (zipEntry == null) {
				Log.e(TAG, "  assets/xposed_init not found in the APK");
				closeSilently(zipFile);
				return;
			}
			is = zipFile.getInputStream(zipEntry);
		} catch (IOException e) {
			Log.e(TAG, "  Cannot read assets/xposed_init in the APK", e);
			closeSilently(zipFile);
			return;
		}

		ClassLoader mcl = new PathClassLoader(apk, XposedBridge.BOOTCLASSLOADER);
		BufferedReader moduleClassesReader = new BufferedReader(new InputStreamReader(is));
		try {
			String moduleClassName;
			while ((moduleClassName = moduleClassesReader.readLine()) != null) {
				moduleClassName = moduleClassName.trim();
				if (moduleClassName.isEmpty() || moduleClassName.startsWith("#"))
					continue;

				try {
					Log.i(TAG, "  Loading class " + moduleClassName);
					Class<?> moduleClass = mcl.loadClass(moduleClassName);

					if (!IXposedMod.class.isAssignableFrom(moduleClass)) {
						Log.e(TAG, "    This class doesn't implement any sub-interface of IXposedMod, skipping it");
						continue;
					} else if (disableResources && IXposedHookInitPackageResources.class.isAssignableFrom(moduleClass)) {
						Log.e(TAG, "    This class requires resource-related hooks (which are disabled), skipping it.");
						continue;
					}

					final Object moduleInstance = moduleClass.newInstance();
					if (XposedBridge.isZygote) {
						// 针对系统级别hook
						// 实现IXposedHookZygoteInit接口,在Zygote进程启动之前执行initZygote方法,该方法只会执行两次,
						// 在实际操作过程中可以用于工具类的初始化,避免在IXposedHookLoadPackage中会重复加载
						if (moduleInstance instanceof IXposedHookZygoteInit) {
							IXposedHookZygoteInit.StartupParam param = new IXposedHookZygoteInit.StartupParam();
							param.modulePath = apk;
							param.startsSystemServer = startsSystemServer;
							((IXposedHookZygoteInit) moduleInstance).initZygote(param);
						}

						// 针对应用级别hook
						if (moduleInstance instanceof IXposedHookLoadPackage)
							// 在hook handleBindApplication的时候也调用一次loadModules，为true
							// 此时可以保证每次启动app时都能重新获取module_list列表
							Log.i(XposedBridge.TAG, "XposedBridge.hookLoadPackage begin>>>");
							if(isLoadHookLoadPackage){
								Log.i(XposedBridge.TAG, "XposedBridge.hookLoadPackage Start>>>");
								XposedBridge.hookLoadPackage(new IXposedHookLoadPackage.Wrapper((IXposedHookLoadPackage) moduleInstance));
							}else {
								Log.i(XposedBridge.TAG, "XposedBridge.hookLoadPackage Not Start");
							}



						// 资源布局初始化时进行hook,如果要使用此类,必须在xposed->设置中,将[禁用资源勾子]一项取消打钩
						if (moduleInstance instanceof IXposedHookInitPackageResources)
							XposedBridge.hookInitPackageResources(new IXposedHookInitPackageResources.Wrapper((IXposedHookInitPackageResources) moduleInstance));
					} else {
						if (moduleInstance instanceof IXposedHookCmdInit) {
							IXposedHookCmdInit.StartupParam param = new IXposedHookCmdInit.StartupParam();
							param.modulePath = apk;
							param.startClassName = startClassName;
							((IXposedHookCmdInit) moduleInstance).initCmdApp(param);
						}
					}
				} catch (Throwable t) {
					Log.e(TAG, "    Failed to load class " + moduleClassName, t);
				}
			}
		} catch (IOException e) {
			Log.e(TAG, "  Failed to load module from " + apk, e);
		} finally {
			closeSilently(is);
			closeSilently(zipFile);
		}
	}
}
