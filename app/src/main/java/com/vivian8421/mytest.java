package com.vivian8421;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

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
    private static final long PENDING_INTENT_RETRY_DELAY_MS = 250L;
    private static final long PENDING_INTENT_SECOND_RETRY_DELAY_MS = 700L;
    private static final long DIRECT_ACTIVITY_FALLBACK_DELAY_MS = 1200L;
    private static final int MAX_PENDING_INTENT_RETRY_COUNT = 2;
    private static final int INTENT_SENDER_ACTIVITY = 2;
    private static final ThreadLocal<Boolean> retryingPendingIntent = new ThreadLocal<>();
    private static final ThreadLocal<List<WakeRequest>> pendingIntentWakeRequests = new ThreadLocal<>();
    private static Context systemContext;

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
        hookPendingIntentWakeClass("com.android.server.am.PendingIntentRecord", classLoader);
        hookPendingIntentWakeClass("com.android.server.wm.PendingIntentRecord", classLoader);
    }

    private void hookPendingIntentWakeClass(String className, final ClassLoader classLoader) {
        Class<?> pendingIntentRecordClass = findClassSafely(className, classLoader);
        if (pendingIntentRecordClass == null) {
            return;
        }

        try {
            XposedBridge.hookAllMethods(pendingIntentRecordClass, "sendInner", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (isRetryingPendingIntent()) {
                        return;
                    }
                    WakeRequest wakeRequest = wakePackagesBeforeLaunch(param.thisObject, param.args, classLoader);
                    pushPendingIntentWakeRequest(wakeRequest);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (isRetryingPendingIntent()) {
                        return;
                    }
                    WakeRequest wakeRequest = popPendingIntentWakeRequest();
                    Object result = getHookResultSafely(param);
                    if (shouldRetryPendingIntentAfterOriginalSend(wakeRequest, result)) {
                        log("retry PendingIntent for packages=" + wakeRequest.packages
                                + " userId=" + wakeRequest.userId + " originalResult=" + result);
                        retryPendingIntentSend(param.thisObject, param.args, classLoader, wakeRequest.userId);
                        scheduleOriginalActivityFallback(
                                param.thisObject,
                                param.args,
                                classLoader,
                                wakeRequest.userId,
                                DIRECT_ACTIVITY_FALLBACK_DELAY_MS);
                    }
                }
            });
            log(className + ".sendInner hook installed");
        } catch (Throwable e) {
            log(className + ".sendInner hook failed: " + e);
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

    private WakeRequest wakePackagesBeforeLaunch(Object sourceObject, Object[] args, ClassLoader classLoader) {
        int userId = readUserId(sourceObject);
        Set<String> packages = new HashSet<>();
        collectPackagesFromArgs(args, packages);
        collectPackagesFromPendingIntentKey(sourceObject, packages);

        boolean enabledPackage = false;
        for (String packageName : packages) {
            if (shouldSkipPackage(packageName)) {
                continue;
            }
            if (enablePackageIfDisabled(packageName, userId, classLoader)) {
                enabledPackage = true;
            }
        }
        return new WakeRequest(enabledPackage, packages, userId);
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

    private boolean enablePackageIfDisabled(String packageName, int userId, ClassLoader classLoader) {
        Context context = getSystemContext(classLoader);
        if (context == null) {
            return false;
        }

        PackageManager packageManager = context.getPackageManager();
        int state = getApplicationEnabledSetting(packageManager, packageName, userId, classLoader);
        if (!isDisabledState(state)) {
            return false;
        }

        try {
            setApplicationEnabledSetting(packageManager, packageName, userId, classLoader);
            clearPackageStoppedState(packageManager, packageName, userId, classLoader);
            log("enabled disabled package before launch: " + packageName + " userId=" + userId);
            return true;
        } catch (Throwable e) {
            log("enable package failed: " + packageName + " userId=" + userId + " error=" + e);
            return false;
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

    private void clearPackageStoppedState(PackageManager packageManager, String packageName, int userId, ClassLoader classLoader) {
        Object iPackageManager = getPackageManagerService(classLoader);
        if (iPackageManager != null) {
            try {
                XposedHelpers.callMethod(iPackageManager, "setPackageStoppedState", packageName, false, userId);
                return;
            } catch (Throwable ignored) {
            }
        }

        try {
            XposedHelpers.callMethod(packageManager, "setPackageStoppedState", packageName, false);
        } catch (Throwable ignored) {
        }
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

    private void retryPendingIntentSend(
            final Object pendingIntentRecord,
            Object[] args,
            final ClassLoader classLoader,
            final int userId) {
        retryPendingIntentSend(pendingIntentRecord, args, classLoader, userId, 1, PENDING_INTENT_RETRY_DELAY_MS);
    }

    private void retryPendingIntentSend(
            final Object pendingIntentRecord,
            Object[] args,
            final ClassLoader classLoader,
            final int userId,
            final int attempt,
            long delayMs) {
        final Object[] retryArgs = args == null ? new Object[0] : args.clone();
        Handler handler = getMainHandler();
        Runnable retryRunnable = new Runnable() {
            @Override
            public void run() {
                retryingPendingIntent.set(true);
                try {
                    Object result = XposedHelpers.callMethod(pendingIntentRecord, "sendInner", retryArgs);
                    log("retried notification PendingIntent after enabling target package attempt="
                            + attempt + " result=" + result);
                    if (isStartFailureResult(result) && attempt < MAX_PENDING_INTENT_RETRY_COUNT) {
                        retryPendingIntentSend(
                                pendingIntentRecord,
                                retryArgs,
                                classLoader,
                                userId,
                                attempt + 1,
                                PENDING_INTENT_SECOND_RETRY_DELAY_MS);
                    } else if (isStartFailureResult(result)) {
                        startOriginalActivityIntent(pendingIntentRecord, retryArgs, classLoader, userId);
                    }
                } catch (Throwable e) {
                    log("retry notification PendingIntent failed attempt=" + attempt + " error=" + e);
                    if (attempt < MAX_PENDING_INTENT_RETRY_COUNT) {
                        retryPendingIntentSend(
                                pendingIntentRecord,
                                retryArgs,
                                classLoader,
                                userId,
                                attempt + 1,
                                PENDING_INTENT_SECOND_RETRY_DELAY_MS);
                    } else {
                        startOriginalActivityIntent(pendingIntentRecord, retryArgs, classLoader, userId);
                    }
                } finally {
                    retryingPendingIntent.remove();
                }
            }
        };

        if (handler != null) {
            handler.postDelayed(retryRunnable, delayMs);
        } else {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ignored) {
                    }
                    retryRunnable.run();
                }
            }).start();
        }
    }

    private boolean isStartFailureResult(Object result) {
        return result instanceof Integer && ((Integer) result) < 0;
    }

    private void scheduleOriginalActivityFallback(
            final Object pendingIntentRecord,
            Object[] args,
            final ClassLoader classLoader,
            final int userId,
            long delayMs) {
        final Object[] fallbackArgs = args == null ? new Object[0] : args.clone();
        Handler handler = getMainHandler();
        Runnable fallbackRunnable = new Runnable() {
            @Override
            public void run() {
                startOriginalActivityIntent(pendingIntentRecord, fallbackArgs, classLoader, userId);
            }
        };

        if (handler != null) {
            handler.postDelayed(fallbackRunnable, delayMs);
        } else {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ignored) {
                    }
                    fallbackRunnable.run();
                }
            }).start();
        }
    }

    private boolean shouldRetryPendingIntentAfterOriginalSend(WakeRequest wakeRequest, Object result) {
        return wakeRequest != null && wakeRequest.enabledPackage;
    }

    private Object getHookResultSafely(XC_MethodHook.MethodHookParam param) {
        try {
            return param.getResult();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean startOriginalActivityIntent(
            Object pendingIntentRecord,
            Object[] args,
            ClassLoader classLoader,
            int userId) {
        Object key = getObjectFieldSafely(pendingIntentRecord, "key");
        if (key == null) {
            return false;
        }

        int type = getIntFieldSafely(key, "type", -1);
        if (type != INTENT_SENDER_ACTIVITY) {
            log("skip direct start fallback for non-activity PendingIntent type=" + type);
            return false;
        }

        Intent launchIntent = getLastIntentFromPendingIntentKey(key);
        if (launchIntent == null) {
            return false;
        }

        Context context = getSystemContext(classLoader);
        if (context == null) {
            return false;
        }

        Intent retryIntent = new Intent(launchIntent);
        mergeFillInIntent(retryIntent, getFillInIntentFromArgs(args), getIntFieldSafely(key, "flags", 0));
        retryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            Object userHandle = createUserHandle(classLoader, userId);
            if (userHandle != null) {
                XposedHelpers.callMethod(context, "startActivityAsUser", retryIntent, userHandle);
            } else {
                context.startActivity(retryIntent);
            }
            log("started original notification activity intent fallback userId=" + userId
                    + " intent=" + retryIntent);
            return true;
        } catch (Throwable e) {
            log("start original notification activity intent fallback failed: " + e);
            return false;
        }
    }

    private Intent getFillInIntentFromArgs(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Intent) {
                return (Intent) arg;
            }
        }
        return null;
    }

    private void mergeFillInIntent(Intent targetIntent, Intent fillInIntent, int pendingIntentFlags) {
        if (targetIntent == null || fillInIntent == null) {
            return;
        }
        targetIntent.fillIn(fillInIntent, pendingIntentFlags);
    }

    private Intent getLastIntentFromPendingIntentKey(Object key) {
        Object allIntents = getObjectFieldSafely(key, "allIntents");
        if (allIntents instanceof Intent[]) {
            Intent[] intents = (Intent[]) allIntents;
            for (int i = intents.length - 1; i >= 0; i--) {
                if (intents[i] != null) {
                    return intents[i];
                }
            }
        } else if (allIntents instanceof List) {
            List<?> intents = (List<?>) allIntents;
            for (int i = intents.size() - 1; i >= 0; i--) {
                Object item = intents.get(i);
                if (item instanceof Intent) {
                    return (Intent) item;
                }
            }
        }

        Object requestIntent = getObjectFieldSafely(key, "requestIntent");
        if (requestIntent instanceof Intent) {
            return (Intent) requestIntent;
        }
        return null;
    }

    private Object createUserHandle(ClassLoader classLoader, int userId) {
        try {
            Class<?> userHandleClass = XposedHelpers.findClass("android.os.UserHandle", classLoader);
            return XposedHelpers.callStaticMethod(userHandleClass, "of", userId);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> userHandleClass = XposedHelpers.findClass("android.os.UserHandle", classLoader);
            return XposedHelpers.newInstance(userHandleClass, userId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Handler getMainHandler() {
        try {
            Looper looper = Looper.getMainLooper();
            if (looper != null) {
                return new Handler(looper);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean isRetryingPendingIntent() {
        Boolean retrying = retryingPendingIntent.get();
        return retrying != null && retrying;
    }

    private void pushPendingIntentWakeRequest(WakeRequest wakeRequest) {
        List<WakeRequest> wakeRequests = pendingIntentWakeRequests.get();
        if (wakeRequests == null) {
            wakeRequests = new ArrayList<>();
            pendingIntentWakeRequests.set(wakeRequests);
        }
        wakeRequests.add(wakeRequest);
    }

    private WakeRequest popPendingIntentWakeRequest() {
        List<WakeRequest> wakeRequests = pendingIntentWakeRequests.get();
        if (wakeRequests == null || wakeRequests.isEmpty()) {
            return null;
        }

        WakeRequest wakeRequest = wakeRequests.remove(wakeRequests.size() - 1);
        if (wakeRequests.isEmpty()) {
            pendingIntentWakeRequests.remove();
        }
        return wakeRequest;
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

    private static class WakeRequest {
        final boolean enabledPackage;
        final Set<String> packages;
        final int userId;

        WakeRequest(boolean enabledPackage, Set<String> packages, int userId) {
            this.enabledPackage = enabledPackage;
            this.packages = packages;
            this.userId = userId;
        }
    }
}
