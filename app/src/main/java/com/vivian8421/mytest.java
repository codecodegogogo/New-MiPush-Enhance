package com.vivian8421;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class mytest implements IXposedHookLoadPackage {
    private static final String MODULE_PACKAGE = "com.vivian8421.mipushEnhance";
    private static Context systemContext;

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        hookMipushReceiverQuery(loadPackageParam);

        if ("android".equals(loadPackageParam.packageName)) {
            hookPendingIntentWake(loadPackageParam.classLoader);
            hookActivityStartWake(loadPackageParam.classLoader);
        }
    }

    private void hookMipushReceiverQuery(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.ApplicationPackageManager",
                    loadPackageParam.classLoader,
                    "queryBroadcastReceivers",
                    Intent.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            List<ResolveInfo> list = (List<ResolveInfo>) param.getResult();
                            if (list == null) {
                                list = new ArrayList<>();
                            }
                            if (list.isEmpty()) {
                                ResolveInfo r = new ResolveInfo();
                                r.resolvePackageName = "com.miui.securitycenter";
                                list.add(r);
                            }
                            param.setResult(list);
                        }
                    });
        } catch (Throwable e) {
            log("queryBroadcastReceivers hook failed: " + e);
        }
    }

    private void hookPendingIntentWake(final ClassLoader classLoader) {
        Class<?> pendingIntentRecordClass = findClassSafely("com.android.server.am.PendingIntentRecord", classLoader);
        if (pendingIntentRecordClass == null) {
            return;
        }

        try {
            XposedBridge.hookAllMethods(pendingIntentRecordClass, "sendInner", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    wakePackagesBeforeLaunch(param.thisObject, param.args, classLoader);
                }
            });
            log("PendingIntentRecord.sendInner hook installed");
        } catch (Throwable e) {
            log("PendingIntentRecord.sendInner hook failed: " + e);
        }
    }

    private void hookActivityStartWake(final ClassLoader classLoader) {
        hookStartActivityClass("com.android.server.wm.ActivityTaskManagerService", classLoader);
        hookStartActivityClass("com.android.server.am.ActivityManagerService", classLoader);
    }

    private void hookStartActivityClass(String className, final ClassLoader classLoader) {
        Class<?> serviceClass = findClassSafely(className, classLoader);
        if (serviceClass == null) {
            return;
        }

        try {
            XposedBridge.hookAllMethods(serviceClass, "startActivityAsUser", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    wakePackagesBeforeLaunch(param.thisObject, param.args, classLoader);
                }
            });
            log(className + ".startActivityAsUser hook installed");
        } catch (Throwable e) {
            log(className + ".startActivityAsUser hook failed: " + e);
        }
    }

    private void wakePackagesBeforeLaunch(Object sourceObject, Object[] args, ClassLoader classLoader) {
        int userId = readUserId(sourceObject);
        Set<String> packages = new HashSet<>();
        collectPackagesFromArgs(args, packages);
        collectPackagesFromPendingIntentKey(sourceObject, packages);

        for (String packageName : packages) {
            if (shouldSkipPackage(packageName)) {
                continue;
            }
            enablePackageIfDisabled(packageName, userId, classLoader);
        }
    }

    private void collectPackagesFromArgs(Object[] args, Set<String> packages) {
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg instanceof Intent) {
                collectPackagesFromIntent((Intent) arg, packages);
            } else if (arg instanceof Bundle) {
                collectPackagesFromBundle((Bundle) arg, packages, 0);
            }
        }
    }

    private void collectPackagesFromPendingIntentKey(Object sourceObject, Set<String> packages) {
        Object key = getObjectFieldSafely(sourceObject, "key");
        if (key == null) {
            return;
        }

        addPackage((String) getObjectFieldSafely(key, "packageName"), packages);

        Object requestIntent = getObjectFieldSafely(key, "requestIntent");
        if (requestIntent instanceof Intent) {
            collectPackagesFromIntent((Intent) requestIntent, packages);
        }

        Object allIntents = getObjectFieldSafely(key, "allIntents");
        if (allIntents instanceof Intent[]) {
            for (Intent intent : (Intent[]) allIntents) {
                collectPackagesFromIntent(intent, packages);
            }
        } else if (allIntents instanceof List) {
            for (Object item : (List<?>) allIntents) {
                if (item instanceof Intent) {
                    collectPackagesFromIntent((Intent) item, packages);
                }
            }
        }
    }

    private void collectPackagesFromIntent(Intent intent, Set<String> packages) {
        if (intent == null) {
            return;
        }

        ComponentName componentName = intent.getComponent();
        if (componentName != null) {
            addPackage(componentName.getPackageName(), packages);
        }

        addPackage(intent.getPackage(), packages);

        Intent selector = intent.getSelector();
        if (selector != null && selector != intent) {
            collectPackagesFromIntent(selector, packages);
        }

        collectPackagesFromBundle(intent.getExtras(), packages, 0);
    }

    private void collectPackagesFromBundle(Bundle bundle, Set<String> packages, int depth) {
        if (bundle == null || depth > 2) {
            return;
        }

        try {
            for (String key : bundle.keySet()) {
                Object value;
                try {
                    value = bundle.get(key);
                } catch (Throwable ignored) {
                    continue;
                }

                if (value instanceof Intent) {
                    collectPackagesFromIntent((Intent) value, packages);
                } else if (value instanceof Bundle) {
                    collectPackagesFromBundle((Bundle) value, packages, depth + 1);
                } else if (isPackageExtraKey(key)) {
                    collectPackageValue(value, packages);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void collectPackageValue(Object value, Set<String> packages) {
        if (value instanceof String) {
            addPackage((String) value, packages);
        } else if (value instanceof String[]) {
            for (String item : (String[]) value) {
                addPackage(item, packages);
            }
        } else if (value instanceof CharSequence) {
            addPackage(value.toString(), packages);
        }
    }

    private boolean isPackageExtraKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.US);
        return lower.contains("package")
                || lower.contains("pkg")
                || lower.contains("target")
                || lower.equals("app")
                || lower.equals("app_id");
    }

    private void addPackage(String packageName, Set<String> packages) {
        if (looksLikePackageName(packageName)) {
            packages.add(packageName);
        }
    }

    private boolean looksLikePackageName(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        return value.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    }

    private boolean shouldSkipPackage(String packageName) {
        return TextUtils.isEmpty(packageName)
                || "android".equals(packageName)
                || MODULE_PACKAGE.equals(packageName);
    }

    private void enablePackageIfDisabled(String packageName, int userId, ClassLoader classLoader) {
        Context context = getSystemContext(classLoader);
        if (context == null) {
            return;
        }

        PackageManager packageManager = context.getPackageManager();
        int state = getApplicationEnabledSetting(packageManager, packageName, userId, classLoader);
        if (!isDisabledState(state)) {
            return;
        }

        try {
            setApplicationEnabledSetting(packageManager, packageName, userId, classLoader);
            log("enabled disabled package before launch: " + packageName + " userId=" + userId);
        } catch (Throwable e) {
            log("enable package failed: " + packageName + " userId=" + userId + " error=" + e);
        }
    }

    private int getApplicationEnabledSetting(PackageManager packageManager, String packageName, int userId, ClassLoader classLoader) {
        Object iPackageManager = getPackageManagerService(classLoader);
        if (iPackageManager != null) {
            try {
                Object result = XposedHelpers.callMethod(iPackageManager, "getApplicationEnabledSetting", packageName, userId);
                if (result instanceof Integer) {
                    return (Integer) result;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            return packageManager.getApplicationEnabledSetting(packageName);
        } catch (Throwable ignored) {
            return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        }
    }

    private void setApplicationEnabledSetting(PackageManager packageManager, String packageName, int userId, ClassLoader classLoader) {
        Object iPackageManager = getPackageManagerService(classLoader);
        if (iPackageManager != null) {
            try {
                XposedHelpers.callMethod(
                        iPackageManager,
                        "setApplicationEnabledSetting",
                        packageName,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP,
                        userId,
                        MODULE_PACKAGE);
                return;
            } catch (Throwable ignored) {
            }
        }

        packageManager.setApplicationEnabledSetting(
                packageName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    private boolean isDisabledState(int state) {
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }

    private int readUserId(Object sourceObject) {
        Object key = getObjectFieldSafely(sourceObject, "key");
        int userId = getIntFieldSafely(key, "userId", -1);
        if (userId >= 0) {
            return userId;
        }
        return getIntFieldSafely(sourceObject, "userId", 0);
    }

    private Object getPackageManagerService(ClassLoader classLoader) {
        try {
            Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", classLoader);
            return XposedHelpers.callStaticMethod(activityThreadClass, "getPackageManager");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Context getSystemContext(ClassLoader classLoader) {
        if (systemContext != null) {
            return systemContext;
        }

        try {
            Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", classLoader);
            Object currentActivityThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
            if (currentActivityThread != null) {
                systemContext = (Context) XposedHelpers.callMethod(currentActivityThread, "getSystemContext");
            }
        } catch (Throwable e) {
            log("get system context failed: " + e);
        }
        return systemContext;
    }

    private Class<?> findClassSafely(String className, ClassLoader classLoader) {
        try {
            return XposedHelpers.findClass(className, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object getObjectFieldSafely(Object object, String fieldName) {
        if (object == null) {
            return null;
        }
        try {
            return XposedHelpers.getObjectField(object, fieldName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int getIntFieldSafely(Object object, String fieldName, int defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        try {
            return XposedHelpers.getIntField(object, fieldName);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    private void log(String message) {
        XposedBridge.log("NewMipushEnhance: " + message);
    }
}
