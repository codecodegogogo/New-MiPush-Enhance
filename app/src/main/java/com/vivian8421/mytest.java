package com.vivian8421;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class mytest implements IXposedHookLoadPackage {
    private static final String MODULE_PACKAGE = "com.vivian8421.mipushEnhance";
    private static final long PENDING_INTENT_RETRY_DELAY_MS = 1600L;
    private static final long PENDING_INTENT_SECOND_RETRY_DELAY_MS = 900L;
    private static final long PACKAGE_READY_WAIT_MS = 2200L;
    private static final long PACKAGE_READY_WAIT_STEP_MS = 50L;
    private static final long RECENT_NOTIFICATION_LAUNCH_WINDOW_MS = 10000L;
    private static final int MAX_PENDING_INTENT_RETRY_COUNT = 2;
    private static final int INTENT_SENDER_ACTIVITY = 2;
    private static final String XMSF_PACKAGE = "com.xiaomi.xmsf";
    private static final int FLAG_ACTIVITY_SENDER = 1;
    private static final int FLAG_BROADCAST_SENDER = 1 << 1;
    private static final int FLAG_SERVICE_SENDER = 1 << 2;
    private static final int FLAG_ALL_SENDERS = FLAG_ACTIVITY_SENDER
            | FLAG_BROADCAST_SENDER
            | FLAG_SERVICE_SENDER;
    private static final int SYSTEM_UID = 1000;
    private static final ThreadLocal<Boolean> retryingPendingIntent = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> startingActivityFallback = new ThreadLocal<>();
    private static final ThreadLocal<List<WakeRequest>> pendingIntentWakeRequests = new ThreadLocal<>();
    private static final ThreadLocal<List<ActivityStartRequest>> activityStartRequests = new ThreadLocal<>();
    private static final Object recentNotificationLaunchLock = new Object();
    private static final List<RecentNotificationLaunch> recentNotificationLaunches = new ArrayList<>();
    private static Context systemContext;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        hookMipushReceiverQuery(loadPackageParam);

        if ("android".equals(loadPackageParam.packageName)) {
            hookPendingIntentWake(loadPackageParam.classLoader);
            hookActivityStartWake(loadPackageParam.classLoader);
            hookServiceStartWake(loadPackageParam.classLoader);
            hookBroadcastWake(loadPackageParam.classLoader);
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
                    if (!isSystemOrSystemUiSender(classLoader)) {
                        return;
                    }
                    if (!isMipushNotificationClickPendingIntent(param.thisObject, param.args)) {
                        log("skip non-MiPush PendingIntent " + describePendingIntentForLog(param.thisObject, param.args));
                        pushPendingIntentWakeRequest(null);
                        return;
                    }
                    WakeRequest wakeRequest = wakePackagesBeforeLaunch(param.thisObject, param.args, classLoader);
                    if (wakeRequest.enabledPackage) {
                        log("pass original PendingIntent after enabling target package packages="
                                + wakeRequest.packages + " userId=" + wakeRequest.userId);
                        applyPendingIntentSendArgs(param.thisObject, param.args, param.method);
                        rememberNotificationLaunch(wakeRequest.packages, wakeRequest.userId);
                        if (!waitPackagesReady(wakeRequest.packages, wakeRequest.userId, classLoader)) {
                            log("target package is still disabled, keep PendingIntent blocked packages="
                                    + wakeRequest.packages + " userId=" + wakeRequest.userId);
                            retryPendingIntentSend(param.thisObject, param.args, classLoader, wakeRequest.userId);
                            param.setResult(0);
                            return;
                        }
                        pushPendingIntentWakeRequest(wakeRequest);
                        return;
                    }
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
                        rememberNotificationLaunch(wakeRequest.packages, wakeRequest.userId);
                        retryPendingIntentSend(param.thisObject, param.args, classLoader, wakeRequest.userId);
                    } else if (wakeRequest != null && wakeRequest.enabledPackage) {
                        startNonActivityPendingIntentLaunchFallback(
                                param.thisObject,
                                param.args,
                                wakeRequest.packages,
                                classLoader,
                                wakeRequest.userId);
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

    private void hookServiceStartWake(final ClassLoader classLoader) {
        hookStartServiceClass("com.android.server.am.ActivityManagerService", classLoader);
    }

    private void hookBroadcastWake(final ClassLoader classLoader) {
        hookBroadcastClass("com.android.server.am.ActivityManagerService", classLoader);
    }

    private void hookStartActivityClass(String className, final ClassLoader classLoader) {
        Class<?> serviceClass = findClassSafely(className, classLoader);
        if (serviceClass == null) {
            return;
        }

        hookStartActivityMethod(serviceClass, className, "startActivity", classLoader);
        hookStartActivityMethod(serviceClass, className, "startActivityAsUser", classLoader);
    }

    private void hookStartActivityMethod(
            Class<?> serviceClass,
            String className,
            String methodName,
            final ClassLoader classLoader) {
        try {
            XposedBridge.hookAllMethods(serviceClass, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (isStartingActivityFallback()) {
                        return;
                    }
                    ActivityStartRequest request = createActivityStartRequest(param.args, classLoader);
                    if (request != null
                            && isRecentNotificationLaunch(request.packageName, request.userId)) {
                        wakePackagesBeforeLaunch(param.thisObject, param.args, classLoader);
                        if (startRecentNotificationActivityFromSystem(request, classLoader, param.method)) {
                            param.setResult(createActivityStartSuccessResult(param.method));
                            pushActivityStartRequest(null);
                            return;
                        }
                        pushActivityStartRequest(request);
                    } else {
                        pushActivityStartRequest(null);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (isStartingActivityFallback()) {
                        return;
                    }
                    ActivityStartRequest request = popActivityStartRequest();
                    Object result = getHookResultSafely(param);
                    if (request != null
                            && request.intent != null
                            && isStartFailureResult(result)
                            && isRecentNotificationLaunch(request.packageName, request.userId)) {
                        log("retry blocked notification activity start package="
                                + request.packageName + " userId=" + request.userId
                                + " result=" + result);
                        startActivityIntentFallback(request.intent, classLoader, request.userId);
                    }
                }
            });
            log(className + "." + methodName + " hook installed");
        } catch (Throwable e) {
            log(className + "." + methodName + " hook failed: " + e);
        }
    }

    private void hookStartServiceClass(String className, final ClassLoader classLoader) {
        Class<?> serviceClass = findClassSafely(className, classLoader);
        if (serviceClass == null) {
            return;
        }

        try {
            XposedBridge.hookAllMethods(serviceClass, "startService", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    WakeRequest wakeRequest = wakePackagesForRecentNotificationLaunch(
                            param.thisObject,
                            param.args,
                            classLoader);
                    if (wakeRequest.enabledPackage) {
                        waitPackagesReady(wakeRequest.packages, wakeRequest.userId, classLoader);
                        log("enabled package before startService packages="
                                + wakeRequest.packages + " userId=" + wakeRequest.userId);
                    }
                }
            });
            log(className + ".startService hook installed");
        } catch (Throwable e) {
            log(className + ".startService hook failed: " + e);
        }
    }

    private void hookBroadcastClass(String className, final ClassLoader classLoader) {
        Class<?> serviceClass = findClassSafely(className, classLoader);
        if (serviceClass == null) {
            return;
        }

        try {
            XposedBridge.hookAllMethods(serviceClass, "broadcastIntentWithFeature", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    WakeRequest wakeRequest = wakePackagesForRecentNotificationLaunch(
                            param.thisObject,
                            param.args,
                            classLoader);
                    if (wakeRequest.enabledPackage) {
                        waitPackagesReady(wakeRequest.packages, wakeRequest.userId, classLoader);
                        log("enabled package before broadcastIntentWithFeature packages="
                                + wakeRequest.packages + " userId=" + wakeRequest.userId);
                    }
                }
            });
            XposedBridge.hookAllMethods(serviceClass, "broadcastIntent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    WakeRequest wakeRequest = wakePackagesForRecentNotificationLaunch(
                            param.thisObject,
                            param.args,
                            classLoader);
                    if (wakeRequest.enabledPackage) {
                        waitPackagesReady(wakeRequest.packages, wakeRequest.userId, classLoader);
                        log("enabled package before broadcastIntent packages="
                                + wakeRequest.packages + " userId=" + wakeRequest.userId);
                    }
                }
            });
            log(className + ".broadcastIntent hooks installed");
        } catch (Throwable e) {
            log(className + ".broadcastIntent hook failed: " + e);
        }
    }

    private WakeRequest wakePackagesBeforeLaunch(Object sourceObject, Object[] args, ClassLoader classLoader) {
        int userId = readUserIdFromArgs(args, readUserId(sourceObject));
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

    private WakeRequest wakePackagesForRecentNotificationLaunch(
            Object sourceObject,
            Object[] args,
            ClassLoader classLoader) {
        int userId = readUserIdFromArgs(args, readUserId(sourceObject));
        Set<String> packages = new HashSet<>();
        collectPackagesFromArgs(args, packages);
        collectPackagesFromPendingIntentKey(sourceObject, packages);
        if (!isRecentNotificationLaunch(packages, userId)) {
            return new WakeRequest(false, packages, userId);
        }

        return wakePackagesBeforeLaunch(sourceObject, args, classLoader);
    }

    private ActivityStartRequest createActivityStartRequest(Object[] args, ClassLoader classLoader) {
        Intent intent = getFillInIntentFromArgs(args);
        if (intent == null) {
            return null;
        }

        String packageName = getPackageNameFromIntent(intent, classLoader);
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        return new ActivityStartRequest(new Intent(intent), packageName, readUserIdFromArgs(args, 0));
    }

    private String getPackageNameFromIntent(Intent intent, ClassLoader classLoader) {
        if (intent == null) {
            return null;
        }

        ComponentName componentName = intent.getComponent();
        if (componentName != null) {
            return componentName.getPackageName();
        }

        String packageName = intent.getPackage();
        if (!TextUtils.isEmpty(packageName)) {
            return packageName;
        }

        Context context = getSystemContext(classLoader);
        if (context == null) {
            return null;
        }

        try {
            ResolveInfo resolveInfo = context.getPackageManager().resolveActivity(intent, 0);
            if (resolveInfo != null && resolveInfo.activityInfo != null) {
                return resolveInfo.activityInfo.packageName;
            }
        } catch (Throwable ignored) {
        }
        return null;
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
                } else if (value instanceof byte[] && isPayloadExtraKey(key)) {
                    collectPackageNamesFromBytes((byte[]) value, packages);
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
        } else if (value instanceof byte[]) {
            collectPackageNamesFromBytes((byte[]) value, packages);
        }
    }

    private void collectPackageNamesFromBytes(byte[] bytes, Set<String> packages) {
        if (bytes == null || bytes.length == 0) {
            return;
        }

        int packageCount = packages.size();
        addPackage(readThriftStringField(bytes, 6), packages);
        if (packages.size() > packageCount) {
            return;
        }

        StringBuilder token = new StringBuilder();
        for (byte item : bytes) {
            int value = item & 0xff;
            if (isPackageNameByte(value)) {
                token.append((char) value);
            } else {
                collectPackageNameToken(token, packages);
            }
        }
        collectPackageNameToken(token, packages);
    }

    private String readThriftStringField(byte[] bytes, int targetFieldId) {
        try {
            int offset = 0;
            while (offset < bytes.length) {
                int type = bytes[offset++] & 0xff;
                if (type == 0) {
                    return null;
                }
                if (offset + 2 > bytes.length) {
                    return null;
                }
                int fieldId = readI16(bytes, offset);
                offset += 2;
                if (fieldId == targetFieldId && type == 11) {
                    return readThriftString(bytes, offset);
                }
                offset = skipThriftValue(bytes, offset, type);
                if (offset < 0) {
                    return null;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Map<String, String> readThriftStringMapEntries(byte[] bytes) {
        Map<String, String> result = new HashMap<>();
        if (bytes == null || bytes.length == 0) {
            return result;
        }

        try {
            scanThriftStructForStringMaps(bytes, 0, result, 0);
        } catch (Throwable ignored) {
        }
        return result;
    }

    private int scanThriftStructForStringMaps(
            byte[] bytes,
            int offset,
            Map<String, String> result,
            int depth) {
        if (depth > 8) {
            return skipThriftStruct(bytes, offset);
        }

        while (offset < bytes.length) {
            int type = bytes[offset++] & 0xff;
            if (type == 0) {
                return offset;
            }
            if (offset + 2 > bytes.length) {
                return -1;
            }
            offset += 2;
            offset = scanThriftValueForStringMaps(bytes, offset, type, result, depth + 1);
            if (offset < 0) {
                return -1;
            }
        }
        return -1;
    }

    private int scanThriftValueForStringMaps(
            byte[] bytes,
            int offset,
            int type,
            Map<String, String> result,
            int depth) {
        if (depth > 8) {
            return skipThriftValue(bytes, offset, type);
        }

        switch (type) {
            case 12:
                return scanThriftStructForStringMaps(bytes, offset, result, depth + 1);
            case 13:
                return scanThriftMapForStringMaps(bytes, offset, result, depth + 1);
            case 14:
            case 15:
                return scanThriftListOrSetForStringMaps(bytes, offset, result, depth + 1);
            default:
                return skipThriftValue(bytes, offset, type);
        }
    }

    private int scanThriftMapForStringMaps(
            byte[] bytes,
            int offset,
            Map<String, String> result,
            int depth) {
        if (offset + 6 > bytes.length) {
            return -1;
        }

        int keyType = bytes[offset++] & 0xff;
        int valueType = bytes[offset++] & 0xff;
        int size = readI32(bytes, offset);
        offset += 4;
        if (size < 0) {
            return -1;
        }

        for (int i = 0; i < size; i++) {
            if (keyType == 11 && valueType == 11) {
                String key = readThriftString(bytes, offset);
                offset = skipThriftBinary(bytes, offset);
                if (offset < 0) {
                    return -1;
                }
                String value = readThriftString(bytes, offset);
                offset = skipThriftBinary(bytes, offset);
                if (offset < 0) {
                    return -1;
                }
                if (!TextUtils.isEmpty(key) && value != null) {
                    result.put(key, value);
                }
            } else {
                offset = scanThriftValueForStringMaps(bytes, offset, keyType, result, depth + 1);
                if (offset < 0) {
                    return -1;
                }
                offset = scanThriftValueForStringMaps(bytes, offset, valueType, result, depth + 1);
                if (offset < 0) {
                    return -1;
                }
            }
        }
        return offset;
    }

    private int scanThriftListOrSetForStringMaps(
            byte[] bytes,
            int offset,
            Map<String, String> result,
            int depth) {
        if (offset + 5 > bytes.length) {
            return -1;
        }

        int itemType = bytes[offset++] & 0xff;
        int size = readI32(bytes, offset);
        offset += 4;
        if (size < 0) {
            return -1;
        }

        for (int i = 0; i < size; i++) {
            offset = scanThriftValueForStringMaps(bytes, offset, itemType, result, depth + 1);
            if (offset < 0) {
                return -1;
            }
        }
        return offset;
    }

    private int skipThriftValue(byte[] bytes, int offset, int type) {
        switch (type) {
            case 2:
            case 3:
                return offset + 1 <= bytes.length ? offset + 1 : -1;
            case 4:
            case 10:
                return offset + 8 <= bytes.length ? offset + 8 : -1;
            case 6:
                return offset + 2 <= bytes.length ? offset + 2 : -1;
            case 8:
                return offset + 4 <= bytes.length ? offset + 4 : -1;
            case 11:
                return skipThriftBinary(bytes, offset);
            case 12:
                return skipThriftStruct(bytes, offset);
            case 13:
                return skipThriftMap(bytes, offset);
            case 14:
            case 15:
                return skipThriftListOrSet(bytes, offset);
            default:
                return -1;
        }
    }

    private int skipThriftStruct(byte[] bytes, int offset) {
        while (offset < bytes.length) {
            int type = bytes[offset++] & 0xff;
            if (type == 0) {
                return offset;
            }
            if (offset + 2 > bytes.length) {
                return -1;
            }
            offset += 2;
            offset = skipThriftValue(bytes, offset, type);
            if (offset < 0) {
                return -1;
            }
        }
        return -1;
    }

    private int skipThriftMap(byte[] bytes, int offset) {
        if (offset + 6 > bytes.length) {
            return -1;
        }
        int keyType = bytes[offset++] & 0xff;
        int valueType = bytes[offset++] & 0xff;
        int size = readI32(bytes, offset);
        offset += 4;
        if (size < 0) {
            return -1;
        }
        for (int i = 0; i < size; i++) {
            offset = skipThriftValue(bytes, offset, keyType);
            if (offset < 0) {
                return -1;
            }
            offset = skipThriftValue(bytes, offset, valueType);
            if (offset < 0) {
                return -1;
            }
        }
        return offset;
    }

    private int skipThriftListOrSet(byte[] bytes, int offset) {
        if (offset + 5 > bytes.length) {
            return -1;
        }
        int itemType = bytes[offset++] & 0xff;
        int size = readI32(bytes, offset);
        offset += 4;
        if (size < 0) {
            return -1;
        }
        for (int i = 0; i < size; i++) {
            offset = skipThriftValue(bytes, offset, itemType);
            if (offset < 0) {
                return -1;
            }
        }
        return offset;
    }

    private int skipThriftBinary(byte[] bytes, int offset) {
        if (offset + 4 > bytes.length) {
            return -1;
        }
        int length = readI32(bytes, offset);
        offset += 4;
        if (length < 0 || offset + length > bytes.length) {
            return -1;
        }
        return offset + length;
    }

    private String readThriftString(byte[] bytes, int offset) {
        if (offset + 4 > bytes.length) {
            return null;
        }
        int length = readI32(bytes, offset);
        offset += 4;
        if (length <= 0 || offset + length > bytes.length) {
            return null;
        }
        return new String(bytes, offset, length);
    }

    private int readI16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8)
                | (bytes[offset + 1] & 0xff);
    }

    private int readI32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private boolean isPackageNameByte(int value) {
        return (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || value == '.'
                || value == '_';
    }

    private void collectPackageNameToken(StringBuilder token, Set<String> packages) {
        if (token.length() == 0) {
            return;
        }

        String value = token.toString();
        token.setLength(0);
        addPackage(value, packages);
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

    private boolean isPayloadExtraKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.US);
        return lower.contains("payload")
                || lower.contains("mipush")
                || lower.contains("push");
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

    private boolean waitPackagesReady(Set<String> packages, int userId, ClassLoader classLoader) {
        if (packages == null || packages.isEmpty()) {
            return true;
        }

        long deadline = System.currentTimeMillis() + PACKAGE_READY_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (arePackagesReady(packages, userId, classLoader)) {
                return true;
            }
            try {
                Thread.sleep(PACKAGE_READY_WAIT_STEP_MS);
            } catch (InterruptedException ignored) {
                return false;
            }
        }
        return arePackagesReady(packages, userId, classLoader);
    }

    private boolean arePackagesReady(Set<String> packages, int userId, ClassLoader classLoader) {
        Context context = getSystemContext(classLoader);
        if (context == null) {
            return true;
        }

        PackageManager packageManager = context.getPackageManager();
        for (String packageName : packages) {
            if (shouldSkipPackage(packageName)) {
                continue;
            }
            int state = getApplicationEnabledSetting(packageManager, packageName, userId, classLoader);
            if (isDisabledState(state)) {
                return false;
            }
        }
        return true;
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

    private int readUserIdFromArgs(Object[] args, int defaultValue) {
        if (args == null) {
            return defaultValue;
        }

        for (int i = args.length - 1; i >= 0; i--) {
            Object arg = args[i];
            if (arg instanceof Integer) {
                int value = (Integer) arg;
                if (value >= 0 && value < 1000) {
                    return value;
                }
            }
        }
        return defaultValue;
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
                WakeRequest wakeRequest = wakePackagesBeforeLaunch(
                        pendingIntentRecord,
                        retryArgs,
                        classLoader);
                int retryUserId = wakeRequest.userId >= 0 ? wakeRequest.userId : userId;
                if (!waitPackagesReady(wakeRequest.packages, retryUserId, classLoader)) {
                    log("skip retry because target package is still disabled packages="
                            + wakeRequest.packages + " userId=" + retryUserId);
                    if (attempt < MAX_PENDING_INTENT_RETRY_COUNT) {
                        retryPendingIntentSend(
                                pendingIntentRecord,
                                retryArgs,
                                classLoader,
                                retryUserId,
                                attempt + 1,
                                PENDING_INTENT_SECOND_RETRY_DELAY_MS);
                    }
                    return;
                }

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
                                retryUserId,
                                attempt + 1,
                                PENDING_INTENT_SECOND_RETRY_DELAY_MS);
                    } else if (isStartFailureResult(result)) {
                        startPendingIntentLaunchFallback(
                                pendingIntentRecord,
                                retryArgs,
                                wakeRequest.packages,
                                classLoader,
                                retryUserId);
                    } else {
                        startNonActivityPendingIntentLaunchFallback(
                                pendingIntentRecord,
                                retryArgs,
                                wakeRequest.packages,
                                classLoader,
                                retryUserId);
                    }
                } catch (Throwable e) {
                    log("retry notification PendingIntent failed attempt=" + attempt + " error=" + e);
                    if (attempt < MAX_PENDING_INTENT_RETRY_COUNT) {
                        retryPendingIntentSend(
                                pendingIntentRecord,
                                retryArgs,
                                classLoader,
                                retryUserId,
                                attempt + 1,
                                PENDING_INTENT_SECOND_RETRY_DELAY_MS);
                    } else {
                        startPendingIntentLaunchFallback(
                                pendingIntentRecord,
                                retryArgs,
                                wakeRequest.packages,
                                classLoader,
                                retryUserId);
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

    private Object[] preparePendingIntentSendArgs(Object pendingIntentRecord, Object[] args, Member method) {
        Object[] retryArgs = args == null ? new Object[0] : args.clone();
        applyPendingIntentSendArgs(pendingIntentRecord, retryArgs, method);
        return retryArgs;
    }

    private void applyPendingIntentSendArgs(Object pendingIntentRecord, Object[] args, Member method) {
        if (args == null) {
            return;
        }
        applyBackgroundActivityStartOptions(args, method);
        applyBackgroundActivityStartToken(pendingIntentRecord, args, method);
    }

    private void applyBackgroundActivityStartOptions(Object[] args, Member method) {
        Bundle backgroundStartOptions = createBackgroundActivityStartOptionsBundle();
        if (backgroundStartOptions == null) {
            return;
        }

        Class<?>[] parameterTypes = getParameterTypes(method);
        if (parameterTypes != null && parameterTypes.length == args.length) {
            for (int i = 0; i < parameterTypes.length; i++) {
                if (Bundle.class.equals(parameterTypes[i])) {
                    args[i] = mergeOptionsBundle(args[i], backgroundStartOptions);
                    return;
                }
            }
        }

        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof Bundle) {
                args[i] = mergeOptionsBundle(args[i], backgroundStartOptions);
                return;
            }
        }
    }

    private Bundle mergeOptionsBundle(Object originalOptions, Bundle backgroundStartOptions) {
        Bundle merged = originalOptions instanceof Bundle
                ? new Bundle((Bundle) originalOptions)
                : new Bundle();
        merged.putAll(backgroundStartOptions);
        return merged;
    }

    private Bundle createBackgroundActivityStartOptionsBundle() {
        try {
            Class<?> activityOptionsClass = Class.forName("android.app.ActivityOptions");
            Object options = XposedHelpers.callStaticMethod(activityOptionsClass, "makeBasic");
            int mode = getActivityOptionsBackgroundStartMode(activityOptionsClass);
            XposedHelpers.callMethod(options, "setPendingIntentBackgroundActivityStartMode", mode);
            return (Bundle) XposedHelpers.callMethod(options, "toBundle");
        } catch (Throwable e) {
            log("create BAL ActivityOptions failed: " + e);
            return null;
        }
    }

    private int getActivityOptionsBackgroundStartMode(Class<?> activityOptionsClass) {
        String[] fieldNames = new String[]{
                "MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS",
                "MODE_BACKGROUND_ACTIVITY_START_ALLOWED",
                "MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE"
        };
        for (String fieldName : fieldNames) {
            try {
                return XposedHelpers.getStaticIntField(activityOptionsClass, fieldName);
            } catch (Throwable ignored) {
            }
        }
        return 1;
    }

    private void applyBackgroundActivityStartToken(Object pendingIntentRecord, Object[] args, Member method) {
        int tokenIndex = findFirstBinderParameterIndex(args, method);
        if (tokenIndex < 0) {
            return;
        }

        Object token = args[tokenIndex];
        if (!(token instanceof IBinder)) {
            token = new Binder();
            args[tokenIndex] = token;
        }

        try {
            XposedHelpers.callMethod(
                    pendingIntentRecord,
                    "setAllowBgActivityStarts",
                    token,
                    FLAG_ALL_SENDERS);
        } catch (Throwable e) {
            log("setAllowBgActivityStarts failed: " + e);
        }
    }

    private int findFirstBinderParameterIndex(Object[] args, Member method) {
        Class<?>[] parameterTypes = getParameterTypes(method);
        if (parameterTypes != null && parameterTypes.length == args.length) {
            for (int i = 0; i < parameterTypes.length; i++) {
                if (IBinder.class.equals(parameterTypes[i])) {
                    return i;
                }
            }
        }

        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof IBinder) {
                return i;
            }
        }
        return -1;
    }

    private Class<?>[] getParameterTypes(Member method) {
        if (method instanceof Method) {
            return ((Method) method).getParameterTypes();
        }
        return null;
    }

    private boolean isStartFailureResult(Object result) {
        return result instanceof Integer && ((Integer) result) < 0;
    }

    private boolean shouldRetryPendingIntentAfterOriginalSend(WakeRequest wakeRequest, Object result) {
        return wakeRequest != null && wakeRequest.enabledPackage && isStartFailureResult(result);
    }

    private Object getHookResultSafely(XC_MethodHook.MethodHookParam param) {
        try {
            return param.getResult();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean startPendingIntentLaunchFallback(
            Object pendingIntentRecord,
            Object[] args,
            Set<String> packages,
            ClassLoader classLoader,
            int userId) {
        Object key = getObjectFieldSafely(pendingIntentRecord, "key");
        int type = getIntFieldSafely(key, "type", -1);
        if (type == INTENT_SENDER_ACTIVITY) {
            return startOriginalActivityIntent(pendingIntentRecord, args, classLoader, userId);
        }
        return startNonActivityPendingIntentLaunchFallback(
                pendingIntentRecord,
                args,
                packages,
                classLoader,
                userId);
    }

    private boolean startNonActivityPendingIntentLaunchFallback(
            Object pendingIntentRecord,
            Object[] args,
            Set<String> packages,
            ClassLoader classLoader,
            int userId) {
        Object key = getObjectFieldSafely(pendingIntentRecord, "key");
        int type = getIntFieldSafely(key, "type", -1);
        if (type == INTENT_SENDER_ACTIVITY) {
            return false;
        }

        if (!isMipushNotificationClickPendingIntent(pendingIntentRecord, args)) {
            log("skip package launch fallback for non-click PendingIntent type=" + type);
            return false;
        }

        Set<String> launchPackages = collectPendingIntentPackages(pendingIntentRecord, args, packages);
        Intent mipushJumpIntent = createMipushJumpIntentFallback(
                pendingIntentRecord,
                args,
                launchPackages,
                classLoader);
        return startTargetPackageLaunchFallback(launchPackages, mipushJumpIntent, classLoader, userId, type);
    }

    private Set<String> collectPendingIntentPackages(
            Object pendingIntentRecord,
            Object[] args,
            Set<String> packages) {
        Set<String> launchPackages = new HashSet<>();
        if (packages != null) {
            launchPackages.addAll(packages);
        }
        collectPackagesFromArgs(args, launchPackages);
        collectPackagesFromPendingIntentKey(pendingIntentRecord, launchPackages);
        return launchPackages;
    }

    private boolean startTargetPackageLaunchFallback(
            Set<String> packages,
            Intent mipushJumpIntent,
            ClassLoader classLoader,
            int userId,
            int pendingIntentType) {
        Context context = getSystemContext(classLoader);
        if (context == null || packages == null || packages.isEmpty()) {
            return false;
        }

        PackageManager packageManager = context.getPackageManager();
        String packageName = findLaunchableTargetPackage(packageManager, packages);
        if (TextUtils.isEmpty(packageName)) {
            log("skip package launch fallback because no launchable target package packages="
                    + packages + " type=" + pendingIntentType);
            return false;
        }

        Intent launchIntent = mipushJumpIntent != null
                ? new Intent(mipushJumpIntent)
                : packageManager.getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            log("skip package launch fallback because launch intent is null package="
                    + packageName + " type=" + pendingIntentType);
            return false;
        }

        launchIntent.addCategory(Intent.CATEGORY_DEFAULT);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startingActivityFallback.set(true);
        try {
            Object userHandle = createUserHandle(classLoader, userId);
            Bundle options = createBackgroundActivityStartOptionsBundle();
            if (userHandle != null) {
                if (!startActivityAsUserWithOptions(context, launchIntent, options, userHandle)) {
                    XposedHelpers.callMethod(context, "startActivityAsUser", launchIntent, userHandle);
                }
            } else if (options != null) {
                context.startActivity(launchIntent, options);
            } else {
                context.startActivity(launchIntent);
            }
            rememberNotificationLaunch(packages, userId);
            log("started target package launch fallback package=" + packageName
                    + " userId=" + userId + " type=" + pendingIntentType
                    + " intent=" + launchIntent);
            return true;
        } catch (Throwable e) {
            log("start target package launch fallback failed package="
                    + packageName + " userId=" + userId + " error=" + e);
            return false;
        } finally {
            startingActivityFallback.remove();
        }
    }

    private Intent createMipushJumpIntentFallback(
            Object pendingIntentRecord,
            Object[] args,
            Set<String> packages,
            ClassLoader classLoader) {
        Context context = getSystemContext(classLoader);
        if (context == null || packages == null || packages.isEmpty()) {
            return null;
        }

        PackageManager packageManager = context.getPackageManager();
        String packageName = findLaunchableTargetPackage(packageManager, packages);
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }

        byte[] payload = getMipushPayloadFromPendingIntent(pendingIntentRecord, args);
        Intent intent = buildMipushJumpIntent(context, packageName, payload);
        if (intent != null) {
            log("resolved MiPush jump intent package=" + packageName + " intent=" + intent);
        }
        return intent;
    }

    private Intent buildMipushJumpIntent(Context context, String packageName, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }

        Map<String, String> extra = readThriftStringMapEntries(payload);
        if (extra.isEmpty()) {
            return null;
        }

        Intent intent = null;
        String notifyEffect = getExtraValue(extra, "notify_effect");
        String intentUri = getExtraValue(extra, "intent_uri");
        String className = getExtraValue(extra, "class_name");
        String intentFlag = getExtraValue(extra, "intent_flag");
        String webUri = getExtraValue(extra, "web_uri");

        if ("1".equals(notifyEffect) || "default".equalsIgnoreCase(notifyEffect)) {
            intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        } else if ("2".equals(notifyEffect)
                || "intent".equalsIgnoreCase(notifyEffect)
                || !TextUtils.isEmpty(intentUri)
                || !TextUtils.isEmpty(className)) {
            intent = buildMipushIntentJump(packageName, intentUri, className, intentFlag);
        } else if ("3".equals(notifyEffect)
                || "web".equalsIgnoreCase(notifyEffect)
                || "web_page".equalsIgnoreCase(notifyEffect)
                || !TextUtils.isEmpty(webUri)) {
            intent = buildMipushWebJump(webUri);
        }

        if (intent == null) {
            return null;
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (context.getPackageManager().resolveActivity(intent, 0) == null) {
            log("resolved MiPush jump intent is not launchable package="
                    + packageName + " extras=" + extra.keySet() + " intent=" + intent);
            return null;
        }
        return intent;
    }

    private Intent buildMipushIntentJump(
            String packageName,
            String intentUri,
            String className,
            String intentFlag) {
        Intent intent = null;
        if (!TextUtils.isEmpty(intentUri)) {
            try {
                intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
                intent.setPackage(packageName);
            } catch (Throwable e) {
                log("parse MiPush intent_uri failed package=" + packageName + " error=" + e);
                intent = null;
            }
        }

        if (intent == null && !TextUtils.isEmpty(className)) {
            String resolvedClassName = className.startsWith(".")
                    ? packageName + className
                    : className;
            intent = new Intent();
            intent.setComponent(new ComponentName(packageName, resolvedClassName));
        }

        if (intent != null && !TextUtils.isEmpty(intentFlag)) {
            try {
                intent.setFlags(Integer.parseInt(intentFlag));
            } catch (Throwable e) {
                log("parse MiPush intent_flag failed package=" + packageName + " error=" + e);
            }
        }
        return intent;
    }

    private Intent buildMipushWebJump(String webUri) {
        if (TextUtils.isEmpty(webUri)) {
            return null;
        }

        String uri = webUri.trim();
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
            uri = "http://" + uri;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(uri));
        return intent;
    }

    private String getExtraValue(Map<String, String> extra, String key) {
        if (extra == null || key == null) {
            return null;
        }
        String value = extra.get(key);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, String> entry : extra.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private byte[] getMipushPayloadFromPendingIntent(Object pendingIntentRecord, Object[] args) {
        Object key = getObjectFieldSafely(pendingIntentRecord, "key");
        byte[] payload = getMipushPayloadFromIntent(getLastIntentFromPendingIntentKey(key));
        if (payload != null) {
            return payload;
        }
        return getMipushPayloadFromIntent(getFillInIntentFromArgs(args));
    }

    private byte[] getMipushPayloadFromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        return getMipushPayloadFromBundle(intent.getExtras(), 0);
    }

    private byte[] getMipushPayloadFromBundle(Bundle bundle, int depth) {
        if (bundle == null || depth > 2) {
            return null;
        }

        try {
            for (String key : bundle.keySet()) {
                Object value;
                try {
                    value = bundle.get(key);
                } catch (Throwable ignored) {
                    continue;
                }

                if (value instanceof byte[] && isPayloadExtraKey(key)) {
                    return (byte[]) value;
                } else if (value instanceof Bundle) {
                    byte[] payload = getMipushPayloadFromBundle((Bundle) value, depth + 1);
                    if (payload != null) {
                        return payload;
                    }
                } else if (value instanceof Intent) {
                    byte[] payload = getMipushPayloadFromIntent((Intent) value);
                    if (payload != null) {
                        return payload;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String findLaunchableTargetPackage(PackageManager packageManager, Set<String> packages) {
        for (String packageName : packages) {
            if (isTargetLaunchCandidate(packageName)
                    && packageManager.getLaunchIntentForPackage(packageName) != null) {
                return packageName;
            }
        }
        return null;
    }

    private boolean isTargetLaunchCandidate(String packageName) {
        return !shouldSkipPackage(packageName)
                && !isPushServicePackage(packageName);
    }

    private boolean isPushServicePackage(String packageName) {
        return XMSF_PACKAGE.equals(packageName);
    }

    private boolean isMipushNotificationClickPendingIntent(Object pendingIntentRecord, Object[] args) {
        Object key = getObjectFieldSafely(pendingIntentRecord, "key");
        Intent keyIntent = getLastIntentFromPendingIntentKey(key);
        Intent fillInIntent = getFillInIntentFromArgs(args);
        if (isAlarmLikePendingIntent(keyIntent) || isAlarmLikePendingIntent(fillInIntent)) {
            return false;
        }
        if (hasMipushNotificationClickMarker(keyIntent)
                || hasMipushNotificationClickMarker(fillInIntent)) {
            return true;
        }
        if (hasPushMessageHandlerComponent(keyIntent)
                || hasPushMessageHandlerComponent(fillInIntent)) {
            return true;
        }

        Set<String> packages = collectPendingIntentPackages(pendingIntentRecord, args, null);
        return packages.contains(XMSF_PACKAGE) && hasTargetLaunchPackage(packages);
    }

    private boolean hasMipushNotificationClickMarker(Intent intent) {
        if (intent == null) {
            return false;
        }
        return hasMipushNotificationClickMarker(intent.getExtras(), 0);
    }

    private boolean hasPushMessageHandlerComponent(Intent intent) {
        if (intent == null) {
            return false;
        }
        ComponentName componentName = intent.getComponent();
        if (componentName != null
                && componentName.getClassName() != null
                && componentName.getClassName().contains("PushMessageHandler")) {
            return true;
        }
        Intent selector = intent.getSelector();
        return selector != null && selector != intent && hasPushMessageHandlerComponent(selector);
    }

    private boolean hasTargetLaunchPackage(Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return false;
        }
        for (String packageName : packages) {
            if (isTargetLaunchCandidate(packageName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAlarmLikePendingIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return false;
        }
        String action = intent.getAction().toLowerCase(Locale.US);
        return action.contains("alarm");
    }

    private String describePendingIntentForLog(Object pendingIntentRecord, Object[] args) {
        Object key = getObjectFieldSafely(pendingIntentRecord, "key");
        Intent keyIntent = getLastIntentFromPendingIntentKey(key);
        Intent fillInIntent = getFillInIntentFromArgs(args);
        Set<String> packages = collectPendingIntentPackages(pendingIntentRecord, args, null);
        int type = getIntFieldSafely(key, "type", -1);
        return "type=" + type
                + " packages=" + packages
                + " keyIntent=" + summarizeIntentForLog(keyIntent)
                + " fillIn=" + summarizeIntentForLog(fillInIntent);
    }

    private String summarizeIntentForLog(Intent intent) {
        if (intent == null) {
            return "null";
        }
        return "{action=" + intent.getAction()
                + ",pkg=" + intent.getPackage()
                + ",cmp=" + intent.getComponent()
                + ",categories=" + intent.getCategories()
                + "}";
    }

    private boolean hasMipushNotificationClickMarker(Bundle bundle, int depth) {
        if (bundle == null || depth > 2) {
            return false;
        }

        boolean hasPayload = false;
        boolean hasFromNotification = false;
        try {
            for (String key : bundle.keySet()) {
                Object value;
                try {
                    value = bundle.get(key);
                } catch (Throwable ignored) {
                    continue;
                }

                String lower = key == null ? "" : key.toLowerCase(Locale.US);
                if (value instanceof byte[] && isPayloadExtraKey(key)) {
                    hasPayload = true;
                } else if (value instanceof Boolean
                        && (Boolean) value
                        && lower.contains("notification")) {
                    hasFromNotification = true;
                } else if (value instanceof Bundle
                        && hasMipushNotificationClickMarker((Bundle) value, depth + 1)) {
                    return true;
                } else if (value instanceof Intent
                        && hasMipushNotificationClickMarker((Intent) value)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return hasPayload && hasFromNotification;
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
            Bundle options = createBackgroundActivityStartOptionsBundle();
            if (userHandle != null) {
                if (!startActivityAsUserWithOptions(context, retryIntent, options, userHandle)) {
                    XposedHelpers.callMethod(context, "startActivityAsUser", retryIntent, userHandle);
                }
            } else {
                if (options != null) {
                    context.startActivity(retryIntent, options);
                } else {
                    context.startActivity(retryIntent);
                }
            }
            log("started original notification activity intent fallback userId=" + userId
                    + " intent=" + retryIntent);
            return true;
        } catch (Throwable e) {
            log("start original notification activity intent fallback failed: " + e);
            return false;
        }
    }

    private boolean startActivityIntentFallback(Intent intent, ClassLoader classLoader, int userId) {
        Context context = getSystemContext(classLoader);
        if (context == null || intent == null) {
            return false;
        }

        Intent retryIntent = new Intent(intent);
        retryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startingActivityFallback.set(true);
        try {
            Object userHandle = createUserHandle(classLoader, userId);
            Bundle options = createBackgroundActivityStartOptionsBundle();
            if (userHandle != null) {
                if (!startActivityAsUserWithOptions(context, retryIntent, options, userHandle)) {
                    XposedHelpers.callMethod(context, "startActivityAsUser", retryIntent, userHandle);
                }
            } else if (options != null) {
                context.startActivity(retryIntent, options);
            } else {
                context.startActivity(retryIntent);
            }
            log("started blocked notification activity fallback userId=" + userId
                    + " intent=" + retryIntent);
            return true;
        } catch (Throwable e) {
            log("start blocked notification activity fallback failed: " + e);
            return false;
        } finally {
            startingActivityFallback.remove();
        }
    }

    private boolean startRecentNotificationActivityFromSystem(
            ActivityStartRequest request,
            ClassLoader classLoader,
            Member method) {
        if (request == null || request.intent == null) {
            return false;
        }

        boolean started = startActivityIntentFallback(request.intent, classLoader, request.userId);
        if (started) {
            log("bypass background activity launch block for notification package="
                    + request.packageName + " userId=" + request.userId
                    + " method=" + getMemberName(method));
        }
        return started;
    }

    private Object createActivityStartSuccessResult(Member method) {
        Class<?> returnType = getReturnType(method);
        if (returnType == null || Void.TYPE.equals(returnType)) {
            return null;
        }
        if (Integer.TYPE.equals(returnType) || Integer.class.equals(returnType)) {
            return 0;
        }
        if (Boolean.TYPE.equals(returnType) || Boolean.class.equals(returnType)) {
            return true;
        }
        return null;
    }

    private Class<?> getReturnType(Member method) {
        if (method instanceof Method) {
            return ((Method) method).getReturnType();
        }
        return null;
    }

    private String getMemberName(Member method) {
        return method == null ? "unknown" : method.getName();
    }

    private boolean startActivityAsUserWithOptions(Context context, Intent intent, Bundle options, Object userHandle) {
        if (options == null || userHandle == null) {
            return false;
        }
        try {
            XposedHelpers.callMethod(context, "startActivityAsUser", intent, options, userHandle);
            return true;
        } catch (Throwable ignored) {
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

    private boolean isSystemOrSystemUiSender(ClassLoader classLoader) {
        try {
            int callingUid = Binder.getCallingUid();
            if (callingUid == SYSTEM_UID) {
                return true;
            }

            Context context = getSystemContext(classLoader);
            if (context == null) {
                return false;
            }

            String[] packageNames = context.getPackageManager().getPackagesForUid(callingUid);
            if (packageNames == null) {
                return false;
            }

            for (String packageName : packageNames) {
                if ("com.android.systemui".equals(packageName)
                        || "com.miui.systemui".equals(packageName)
                        || (!TextUtils.isEmpty(packageName)
                        && packageName.toLowerCase(Locale.US).contains("systemui"))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isStartingActivityFallback() {
        Boolean starting = startingActivityFallback.get();
        return starting != null && starting;
    }

    private void rememberNotificationLaunch(Set<String> packages, int userId) {
        if (packages == null || packages.isEmpty()) {
            return;
        }

        long expiresAt = System.currentTimeMillis() + RECENT_NOTIFICATION_LAUNCH_WINDOW_MS;
        synchronized (recentNotificationLaunchLock) {
            pruneRecentNotificationLaunchesLocked(System.currentTimeMillis());
            for (String packageName : packages) {
                if (!shouldSkipPackage(packageName) && !isPushServicePackage(packageName)) {
                    recentNotificationLaunches.add(
                            new RecentNotificationLaunch(packageName, userId, expiresAt));
                }
            }
        }
    }

    private boolean isRecentNotificationLaunch(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }

        long now = System.currentTimeMillis();
        synchronized (recentNotificationLaunchLock) {
            pruneRecentNotificationLaunchesLocked(now);
            for (RecentNotificationLaunch launch : recentNotificationLaunches) {
                if (packageName.equals(launch.packageName)
                        && (launch.userId == userId || launch.userId < 0 || userId < 0)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isRecentNotificationLaunch(Set<String> packages, int userId) {
        if (packages == null || packages.isEmpty()) {
            return false;
        }
        for (String packageName : packages) {
            if (isRecentNotificationLaunch(packageName, userId)) {
                return true;
            }
        }
        return false;
    }

    private void pruneRecentNotificationLaunchesLocked(long now) {
        for (int i = recentNotificationLaunches.size() - 1; i >= 0; i--) {
            if (recentNotificationLaunches.get(i).expiresAt < now) {
                recentNotificationLaunches.remove(i);
            }
        }
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

    private void pushActivityStartRequest(ActivityStartRequest request) {
        List<ActivityStartRequest> requests = activityStartRequests.get();
        if (requests == null) {
            requests = new ArrayList<>();
            activityStartRequests.set(requests);
        }
        requests.add(request);
    }

    private ActivityStartRequest popActivityStartRequest() {
        List<ActivityStartRequest> requests = activityStartRequests.get();
        if (requests == null || requests.isEmpty()) {
            return null;
        }

        ActivityStartRequest request = requests.remove(requests.size() - 1);
        if (requests.isEmpty()) {
            activityStartRequests.remove();
        }
        return request;
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

    private static class ActivityStartRequest {
        final Intent intent;
        final String packageName;
        final int userId;

        ActivityStartRequest(Intent intent, String packageName, int userId) {
            this.intent = intent;
            this.packageName = packageName;
            this.userId = userId;
        }
    }

    private static class RecentNotificationLaunch {
        final String packageName;
        final int userId;
        final long expiresAt;

        RecentNotificationLaunch(String packageName, int userId, long expiresAt) {
            this.packageName = packageName;
            this.userId = userId;
            this.expiresAt = expiresAt;
        }
    }
}
