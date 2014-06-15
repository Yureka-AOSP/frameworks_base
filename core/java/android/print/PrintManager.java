/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.print;

import android.app.Activity;
import android.app.Application.ActivityLifecycleCallbacks;
import android.content.Context;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.PrintDocumentAdapter.LayoutResultCallback;
import android.print.PrintDocumentAdapter.WriteResultCallback;
import android.printservice.PrintServiceInfo;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.os.SomeArgs;

import libcore.io.IoUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * System level service for accessing the printing capabilities of the platform.
 * <p>
 * To obtain a handle to the print manager do the following:
 * </p>
 *
 * <pre>
 * PrintManager printManager =
 *         (PrintManager) context.getSystemService(Context.PRINT_SERVICE);
 * </pre>
 *
 * <h3>Print mechanics</h3>
 * <p>
 * The key idea behind printing on the platform is that the content to be printed
 * should be laid out for the currently selected print options resulting in an
 * optimized output and higher user satisfaction. To achieve this goal the platform
 * declares a contract that the printing application has to follow which is defined
 * by the {@link PrintDocumentAdapter} class. At a higher level the contract is that
 * when the user selects some options from the print UI that may affect the way
 * content is laid out, for example page size, the application receives a callback
 * allowing it to layout the content to better fit these new constraints. After a
 * layout pass the system may ask the application to render one or more pages one
 * or more times. For example, an application may produce a single column list for
 * smaller page sizes and a multi-column table for larger page sizes.
 * </p>
 * <h3>Print jobs</h3>
 * <p>
 * Print jobs are started by calling the {@link #print(String, PrintDocumentAdapter,
 * PrintAttributes)} from an activity which results in bringing up the system print
 * UI. Once the print UI is up, when the user changes a selected print option that
 * affects the way content is laid out the system starts to interact with the
 * application following the mechanics described the section above.
 * </p>
 * <p>
 * Print jobs can be in {@link PrintJobInfo#STATE_CREATED created}, {@link
 * PrintJobInfo#STATE_QUEUED queued}, {@link PrintJobInfo#STATE_STARTED started},
 * {@link PrintJobInfo#STATE_BLOCKED blocked}, {@link PrintJobInfo#STATE_COMPLETED
 * completed}, {@link PrintJobInfo#STATE_FAILED failed}, and {@link
 * PrintJobInfo#STATE_CANCELED canceled} state. Print jobs are stored in dedicated
 * system spooler until they are handled which is they are cancelled or completed.
 * Active print jobs, ones that are not cancelled or completed, are considered failed
 * if the device reboots as the new boot may be after a very long time. The user may
 * choose to restart such print jobs. Once a print job is queued all relevant content
 * is stored in the system spooler and its lifecycle becomes detached from this of
 * the application that created it.
 * </p>
 * <p>
 * An applications can query the print spooler for current print jobs it created
 * but not print jobs created by other applications.
 * </p>
 *
 * @see PrintJob
 * @see PrintJobInfo
 */
public final class PrintManager {

    private static final String LOG_TAG = "PrintManager";

    private static final boolean DEBUG = true;

    private static final int MSG_NOTIFY_PRINT_JOB_STATE_CHANGED = 1;

    /**
     * The action for launching the print dialog activity.
     *
     * @hide
     */
    public static final String ACTION_PRINT_DIALOG = "android.print.PRINT_DIALOG";

    /**
     * Extra with the intent for starting the print dialog.
     * <p>
     * <strong>Type:</strong> {@link android.content.IntentSender}
     * </p>
     *
     * @hide
     */
    public static final String EXTRA_PRINT_DIALOG_INTENT =
            "android.print.intent.extra.EXTRA_PRINT_DIALOG_INTENT";

    /**
     * Extra with a print job.
     * <p>
     * <strong>Type:</strong> {@link android.print.PrintJobInfo}
     * </p>
     *
     * @hide
     */
    public static final String EXTRA_PRINT_JOB =
            "android.print.intent.extra.EXTRA_PRINT_JOB";

    /**
     * Extra with the print document adapter to be printed.
     * <p>
     * <strong>Type:</strong> {@link android.print.IPrintDocumentAdapter}
     * </p>
     *
     * @hide
     */
    public static final String EXTRA_PRINT_DOCUMENT_ADAPTER =
            "android.print.intent.extra.EXTRA_PRINT_DOCUMENT_ADAPTER";

    /** @hide */
    public static final int APP_ID_ANY = -2;

    private final Context mContext;

    private final IPrintManager mService;

    private final int mUserId;

    private final int mAppId;

    private final Handler mHandler;

    private Map<PrintJobStateChangeListener, PrintJobStateChangeListenerWrapper> mPrintJobStateChangeListeners;

    /** @hide */
    public interface PrintJobStateChangeListener {

        /**
         * Callback notifying that a print job state changed.
         *
         * @param printJobId The print job id.
         */
        public void onPrintJobStateChanged(PrintJobId printJobId);
    }

    /**
     * Creates a new instance.
     *
     * @param context The current context in which to operate.
     * @param service The backing system service.
     * @hide
     */
    public PrintManager(Context context, IPrintManager service, int userId, int appId) {
        mContext = context;
        mService = service;
        mUserId = userId;
        mAppId = appId;
        mHandler = new Handler(context.getMainLooper(), null, false) {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_NOTIFY_PRINT_JOB_STATE_CHANGED: {
                        SomeArgs args = (SomeArgs) message.obj;
                        PrintJobStateChangeListenerWrapper wrapper =
                                (PrintJobStateChangeListenerWrapper) args.arg1;
                        PrintJobStateChangeListener listener = wrapper.getListener();
                        if (listener != null) {
                            PrintJobId printJobId = (PrintJobId) args.arg2;
                            listener.onPrintJobStateChanged(printJobId);
                        }
                        args.recycle();
                    } break;
                }
            }
        };
    }

    /**
     * Creates an instance that can access all print jobs.
     *
     * @param userId The user id for which to get all print jobs.
     * @return An instance if the caller has the permission to access all print
     *         jobs, null otherwise.
     * @hide
     */
    public PrintManager getGlobalPrintManagerForUser(int userId) {
        if (mService == null) {
            Log.w(LOG_TAG, "Feature android.software.print not available");
            return null;
        }
        return new PrintManager(mContext, mService, userId, APP_ID_ANY);
    }

    PrintJobInfo getPrintJobInfo(PrintJobId printJobId) {
        try {
            return mService.getPrintJobInfo(printJobId, mAppId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting a print job info:" + printJobId, re);
        }
        return null;
    }

    /**
     * Adds a listener for observing the state of print jobs.
     *
     * @param listener The listener to add.
     * @hide
     */
    public void addPrintJobStateChangeListener(PrintJobStateChangeListener listener) {
        if (mService == null) {
            Log.w(LOG_TAG, "Feature android.software.print not available");
            return;
        }
        if (mPrintJobStateChangeListeners == null) {
            mPrintJobStateChangeListeners = new ArrayMap<PrintJobStateChangeListener,
                    PrintJobStateChangeListenerWrapper>();
        }
        PrintJobStateChangeListenerWrapper wrappedListener =
                new PrintJobStateChangeListenerWrapper(listener, mHandler);
        try {
            mService.addPrintJobStateChangeListener(wrappedListener, mAppId, mUserId);
            mPrintJobStateChangeListeners.put(listener, wrappedListener);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error adding print job state change listener", re);
        }
    }

    /**
     * Removes a listener for observing the state of print jobs.
     *
     * @param listener The listener to remove.
     * @hide
     */
    public void removePrintJobStateChangeListener(PrintJobStateChangeListener listener) {
        if (mService == null) {
            Log.w(LOG_TAG, "Feature android.software.print not available");
            return;
        }
        if (mPrintJobStateChangeListeners == null) {
            return;
        }
        PrintJobStateChangeListenerWrapper wrappedListener =
                mPrintJobStateChangeListeners.remove(listener);
        if (wrappedListener == null) {
            return;
        }
        if (mPrintJobStateChangeListeners.isEmpty()) {
            mPrintJobStateChangeListeners = null;
        }
        wrappedListener.destroy();
        try {
            mService.removePrintJobStateChangeListener(wrappedListener, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error removing print job state change listener", re);
        }
    }

    /**
     * Gets a print job given its id.
     *
     * @return The print job list.
     * @see PrintJob
     * @hide
     */
    public PrintJob getPrintJob(PrintJobId printJobId) {
        if (mService == null) {
            Log.w(LOG_TAG, "Feature android.software.print not available");
            return null;
        }
        try {
            PrintJobInfo printJob = mService.getPrintJobInfo(printJobId, mAppId, mUserId);
            if (printJob != null) {
                return new PrintJob(printJob, this);
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting print job", re);
        }
        return null;
    }

    /**
     * Gets the print jobs for this application.
     *
     * @return The print job list.
     * @see PrintJob
     */
    public List<PrintJob> getPrintJobs() {
        if (mService == null) {
            Log.w(LOG_TAG, "Feature android.software.print not available");
            return Collections.emptyList();
        }
        try {
            List<PrintJobInfo> printJobInfos = mService.getPrintJobInfos(mAppId, mUserId);
            if (printJobInfos == null) {
                return Collections.emptyList();
            }
            final int printJobCount = printJobInfos.size();
            List<PrintJob> printJobs = new ArrayList<PrintJob>(printJobCount);
            for (int i = 0; i < printJobCount; i++) {
                printJobs.add(new PrintJob(printJobInfos.get(i), this));
            }
            return printJobs;
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting print jobs", re);
        }
        return Collections.emptyList();
    }

    void cancelPrintJob(PrintJobId printJobId) {
        if (mService == null) {
            Log.w(LOG_TAG, "Feature android.software.print not available");
            return;
        }
        try {
            mService.cancelPrintJob(printJobId, mAppId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error canceling a print job: " + printJobId, re);
        }
    }

    void restartPrintJob(PrintJobId printJobId) {
        if (mService == null) {
            Log.w(LOG_TAG, "Feature android.software.print not available");
            return;
        }
        try {
            mService.restartPrintJob(printJobId, mAppId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error restarting a print job: " + printJobId, re);
        }
    }

    /**
     * Creates a print job for printing a {@link PrintDocumentAdapter} with
     * default print attributes.
     * <p>
     * Calling this method brings the print UI allowing the user to customize
     * the print job and returns a {@link PrintJob} object without waiting for the
     * user to customize or confirm the print job. The returned print job instance
     * is in a {@link PrintJobInfo#STATE_CREATED created} state.
     * <p>
     * This method can be called only from an {@link Activity}. The rationale is that
     * printing from a service will create an inconsistent user experience as the print
     * UI would appear without any context.
     * </p>
     * <p>
     * Also the passed in {@link PrintDocumentAdapter} will be considered invalid if
     * your activity is finished. The rationale is that once the activity that
     * initiated printing is finished, the provided adapter may be in an inconsistent
     * state as it may depend on the UI presented by the activity.
     * </p>
     * <p>
     * The default print attributes are a hint to the system how the data is to
     * be printed. For example, a photo editor may look at the photo aspect ratio
     * to determine the default orientation and provide a hint whether the printing
     * should be in portrait or landscape. The system will do a best effort to
     * selected the hinted options in the print dialog, given the current printer
     * supports them.
     * </p>
     * <p>
     * <strong>Note:</strong> Calling this method will bring the print dialog and
     * the system will connect to the provided {@link PrintDocumentAdapter}. If a
     * configuration change occurs that you application does not handle, for example
     * a rotation change, the system will drop the connection to the adapter as the
     * activity has to be recreated and the old adapter may be invalid in this context,
     * hence a new adapter instance is required. As a consequence, if your activity
     * does not handle configuration changes (default behavior), you have to save the
     * state that you were printing and call this method again when your activity
     * is recreated.
     * </p>
     *
     * @param printJobName A name for the new print job which is shown to the user.
     * @param documentAdapter An adapter that emits the document to print.
     * @param attributes The default print job attributes or <code>null</code>.
     * @return The created print job on success or null on failure.
     * @throws IllegalStateException If not called from an {@link Activity}.
     * @throws IllegalArgumentException If the print job name is empty or the
     * document adapter is null.
     *
     * @see PrintJob
     */
    public PrintJob print(String printJobName, PrintDocumentAdapter documentAdapter,
            PrintAttributes attributes) {
        if (mService == null) {
            Log.w(LOG_TAG, "Feature android.software.print not available");
            return null;
        }
        if (!(mContext instanceof Activity)) {
            throw new IllegalStateException("Can print only from an activity");
        }
        if (TextUtils.isEmpty(printJobName)) {
            throw new IllegalArgumentException("printJobName cannot be empty");
        }
        if (documentAdapter == null) {
            throw new IllegalArgumentException("documentAdapter cannot be null");
        }
        PrintDocumentAdapterDelegate delegate = new PrintDocumentAdapterDelegate(
                (Activity) mContext, documentAdapter);
        try {
            Bundle result = mService.print(printJobName, delegate,
                    attributes, mContext.getPackageName(), mAppId, mUserId);
            if (result != null) {
                PrintJobInfo printJob = result.getParcelable(EXTRA_PRINT_JOB);
                IntentSender intent = result.getParcelable(EXTRA_PRINT_DIALOG_INTENT);
                if (printJob == null || intent == null) {
                    return null;
                }
                try {
                    mContext.startIntentSender(intent, null, 0, 0, 0);
                    return new PrintJob(printJob, this);
                } catch (SendIntentException sie) {
                    Log.e(LOG_TAG, "Couldn't start print job config activity.", sie);
                }
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error creating a print job", re);
        }
        return null;
    }

    /**
     * Gets the list of enabled print services.
     *
     * @return The enabled service list or an empty list.
     * @hide
     */
    public List<PrintServiceInfo> getEnabledPrintServices() {
        if (mService == null) {
            Log.w(LOG_TAG, "Feature android.software.print not available");
            return Collections.emptyList();
        }
        try {
            List<PrintServiceInfo> enabledServices = mService.getEnabledPrintServices(mUserId);
            if (enabledServices != null) {
                return enabledServices;
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting the enabled print services", re);
        }
        return Collections.emptyList();
    }

    /**
     * Gets the list of installed print services.
     *
     * @return The installed service list or an empty list.
     * @hide
     */
    public List<PrintServiceInfo> getInstalledPrintServices() {
        if (mService == null) {
            Log.w(LOG_TAG, "Feature android.software.print not available");
            return Collections.emptyList();
        }
        try {
            List<PrintServiceInfo> installedServices = mService.getInstalledPrintServices(mUserId);
            if (installedServices != null) {
                return installedServices;
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting the installed print services", re);
        }
        return Collections.emptyList();
    }

    /**
     * @hide
     */
    public PrinterDiscoverySession createPrinterDiscoverySession() {
        if (mService == null) {
            Log.w(LOG_TAG, "Feature android.software.print not available");
            return null;
        }
        return new PrinterDiscoverySession(mService, mContext, mUserId);
    }

    private static final class PrintDocumentAdapterDelegate extends IPrintDocumentAdapter.Stub
            implements ActivityLifecycleCallbacks {
        private final Object mLock = new Object();

        private Activity mActivity; // Strong reference OK - cleared in destroy

        private PrintDocumentAdapter mDocumentAdapter; // Strong reference OK - cleared in destroy

        private Handler mHandler; // Strong reference OK - cleared in destroy

        private IPrintDocumentAdapterObserver mObserver; // Strong reference OK - cleared in destroy

        private DestroyableCallback mPendingCallback;

        public PrintDocumentAdapterDelegate(Activity activity,
                PrintDocumentAdapter documentAdapter) {
            mActivity = activity;
            mDocumentAdapter = documentAdapter;
            mHandler = new MyHandler(mActivity.getMainLooper());
            mActivity.getApplication().registerActivityLifecycleCallbacks(this);
        }

        @Override
        public void setObserver(IPrintDocumentAdapterObserver observer) {
            final boolean destroyed;
            synchronized (mLock) {
                mObserver = observer;
                destroyed = isDestroyedLocked();
            }

            if (destroyed) {
                try {
                    observer.onDestroy();
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error announcing destroyed state", re);
                }
            }
        }

        @Override
        public void start() {
            synchronized (mLock) {
                // If destroyed the handler is null.
                if (!isDestroyedLocked()) {
                    mHandler.obtainMessage(MyHandler.MSG_ON_START,
                            mDocumentAdapter).sendToTarget();
                }
            }
        }

        @Override
        public void layout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                ILayoutResultCallback callback, Bundle metadata, int sequence) {

            ICancellationSignal cancellationTransport = CancellationSignal.createTransport();
            try {
                callback.onLayoutStarted(cancellationTransport, sequence);
            } catch (RemoteException re) {
                // The spooler is dead - can't recover.
                Log.e(LOG_TAG, "Error notifying for layout start", re);
                return;
            }

            synchronized (mLock) {
                // If destroyed the handler is null.
                if (isDestroyedLocked()) {
                    return;
                }

                CancellationSignal cancellationSignal = CancellationSignal.fromTransport(
                        cancellationTransport);

                SomeArgs args = SomeArgs.obtain();
                args.arg1 = mDocumentAdapter;
                args.arg2 = oldAttributes;
                args.arg3 = newAttributes;
                args.arg4 = cancellationSignal;
                args.arg5 = new MyLayoutResultCallback(callback, sequence);
                args.arg6 = metadata;

                mHandler.obtainMessage(MyHandler.MSG_ON_LAYOUT, args).sendToTarget();
            }
        }

        @Override
        public void write(PageRange[] pages, ParcelFileDescriptor fd,
                IWriteResultCallback callback, int sequence) {

            ICancellationSignal cancellationTransport = CancellationSignal.createTransport();
            try {
                callback.onWriteStarted(cancellationTransport, sequence);
            } catch (RemoteException re) {
                // The spooler is dead - can't recover.
                Log.e(LOG_TAG, "Error notifying for write start", re);
                return;
            }

            synchronized (mLock) {
                // If destroyed the handler is null.
                if (isDestroyedLocked()) {
                    return;
                }

                CancellationSignal cancellationSignal = CancellationSignal.fromTransport(
                        cancellationTransport);

                SomeArgs args = SomeArgs.obtain();
                args.arg1 = mDocumentAdapter;
                args.arg2 = pages;
                args.arg3 = fd;
                args.arg4 = cancellationSignal;
                args.arg5 = new MyWriteResultCallback(callback, fd, sequence);

                mHandler.obtainMessage(MyHandler.MSG_ON_WRITE, args).sendToTarget();
            }
        }

        @Override
        public void finish() {
            synchronized (mLock) {
                // If destroyed the handler is null.
                if (!isDestroyedLocked()) {
                    mHandler.obtainMessage(MyHandler.MSG_ON_FINISH,
                            mDocumentAdapter).sendToTarget();
                }
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
            /* do nothing */
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            /* do nothing */
        }

        @Override
        public void onActivityStarted(Activity activity) {
            /* do nothing */
        }

        @Override
        public void onActivityResumed(Activity activity) {
            /* do nothing */
        }

        @Override
        public void onActivityStopped(Activity activity) {
            /* do nothing */
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            /* do nothing */
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            // We really care only if the activity is being destroyed to
            // notify the the print spooler so it can close the print dialog.
            // Note the the spooler has a death recipient that observes if
            // this process gets killed so we cover the case of onDestroy not
            // being called due to this process being killed to reclaim memory.
            IPrintDocumentAdapterObserver observer = null;
            synchronized (mLock) {
                if (activity == mActivity) {
                    observer = mObserver;
                    destroyLocked();
                }
            }
            if (observer != null) {
                try {
                    observer.onDestroy();
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error announcing destroyed state", re);
                }
            }
        }

        private boolean isDestroyedLocked() {
            return (mActivity == null);
        }

        private void destroyLocked() {
            mActivity.getApplication().unregisterActivityLifecycleCallbacks(
                    PrintDocumentAdapterDelegate.this);
            mActivity = null;

            mDocumentAdapter = null;

            // This method is only called from the main thread, so
            // clearing the messages guarantees that any time a
            // message is handled we are not in a destroyed state.
            mHandler.removeMessages(MyHandler.MSG_ON_START);
            mHandler.removeMessages(MyHandler.MSG_ON_LAYOUT);
            mHandler.removeMessages(MyHandler.MSG_ON_WRITE);
            mHandler.removeMessages(MyHandler.MSG_ON_FINISH);
            mHandler = null;

            mObserver = null;

            if (mPendingCallback != null) {
                mPendingCallback.destroy();
                mPendingCallback = null;
            }
        }

        private final class MyHandler extends Handler {
            public static final int MSG_ON_START = 1;
            public static final int MSG_ON_LAYOUT = 2;
            public static final int MSG_ON_WRITE = 3;
            public static final int MSG_ON_FINISH = 4;

            public MyHandler(Looper looper) {
                super(looper, null, true);
            }

            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_ON_START: {
                        if (DEBUG) {
                            Log.i(LOG_TAG, "onStart()");
                        }

                        ((PrintDocumentAdapter) message.obj).onStart();
                    } break;

                    case MSG_ON_LAYOUT: {
                        SomeArgs args = (SomeArgs) message.obj;
                        PrintDocumentAdapter adapter = (PrintDocumentAdapter) args.arg1;
                        PrintAttributes oldAttributes = (PrintAttributes) args.arg2;
                        PrintAttributes newAttributes = (PrintAttributes) args.arg3;
                        CancellationSignal cancellation = (CancellationSignal) args.arg4;
                        LayoutResultCallback callback = (LayoutResultCallback) args.arg5;
                        Bundle metadata = (Bundle) args.arg6;
                        args.recycle();

                        if (DEBUG) {
                            StringBuilder builder = new StringBuilder();
                            builder.append("PrintDocumentAdapter#onLayout() {\n");
                            builder.append("\n  oldAttributes:").append(oldAttributes);
                            builder.append("\n  newAttributes:").append(newAttributes);
                            builder.append("\n  preview:").append(metadata.getBoolean(
                                    PrintDocumentAdapter.EXTRA_PRINT_PREVIEW));
                            builder.append("\n}");
                            Log.i(LOG_TAG, builder.toString());
                        }

                        adapter.onLayout(oldAttributes, newAttributes, cancellation,
                                callback, metadata);
                    } break;

                    case MSG_ON_WRITE: {
                        SomeArgs args = (SomeArgs) message.obj;
                        PrintDocumentAdapter adapter = (PrintDocumentAdapter) args.arg1;
                        PageRange[] pages = (PageRange[]) args.arg2;
                        ParcelFileDescriptor fd = (ParcelFileDescriptor) args.arg3;
                        CancellationSignal cancellation = (CancellationSignal) args.arg4;
                        WriteResultCallback callback = (WriteResultCallback) args.arg5;
                        args.recycle();

                        if (DEBUG) {
                            StringBuilder builder = new StringBuilder();
                            builder.append("PrintDocumentAdapter#onWrite() {\n");
                            builder.append("\n  pages:").append(Arrays.toString(pages));
                            builder.append("\n}");
                            Log.i(LOG_TAG, builder.toString());
                        }

                        adapter.onWrite(pages, fd, cancellation, callback);
                    } break;

                    case MSG_ON_FINISH: {
                        if (DEBUG) {
                            Log.i(LOG_TAG, "onFinish()");
                        }

                        ((PrintDocumentAdapter) message.obj).onFinish();

                        // Done printing, so destroy this instance as it
                        // should not be used anymore.
                        synchronized (mLock) {
                            destroyLocked();
                        }
                    } break;

                    default: {
                        throw new IllegalArgumentException("Unknown message: "
                                + message.what);
                    }
                }
            }
        }

        private interface DestroyableCallback {
            public void destroy();
        }

        private final class MyLayoutResultCallback extends LayoutResultCallback
                implements DestroyableCallback {
            private ILayoutResultCallback mCallback;
            private final int mSequence;

            public MyLayoutResultCallback(ILayoutResultCallback callback,
                    int sequence) {
                mCallback = callback;
                mSequence = sequence;
            }

            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                final ILayoutResultCallback callback;
                synchronized (mLock) {
                    callback = mCallback;
                }

                // If the callback is null we are destroyed.
                if (callback == null) {
                    Log.e(LOG_TAG, "PrintDocumentAdapter is destroyed. Did you "
                            + "finish the printing activity before print completion "
                            + "or did you invoke a callback after finish?");
                    return;
                }

                try {
                    if (info == null) {
                        throw new NullPointerException("document info cannot be null");
                    }

                    try {
                        callback.onLayoutFinished(info, changed, mSequence);
                    } catch (RemoteException re) {
                        Log.e(LOG_TAG, "Error calling onLayoutFinished", re);
                    }
                } finally {
                    destroy();
                }
            }

            @Override
            public void onLayoutFailed(CharSequence error) {
                final ILayoutResultCallback callback;
                synchronized (mLock) {
                    callback = mCallback;
                }

                // If the callback is null we are destroyed.
                if (callback == null) {
                    Log.e(LOG_TAG, "PrintDocumentAdapter is destroyed. Did you "
                            + "finish the printing activity before print completion "
                            + "or did you invoke a callback after finish?");
                    return;
                }

                try {
                    callback.onLayoutFailed(error, mSequence);
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error calling onLayoutFailed", re);
                } finally {
                    destroy();
                }
            }

            @Override
            public void onLayoutCancelled() {
                final ILayoutResultCallback callback;
                synchronized (mLock) {
                    callback = mCallback;
                }

                // If the callback is null we are destroyed.
                if (callback == null) {
                    Log.e(LOG_TAG, "PrintDocumentAdapter is destroyed. Did you "
                            + "finish the printing activity before print completion "
                            + "or did you invoke a callback after finish?");
                    return;
                }

                try {
                    callback.onLayoutCanceled(mSequence);
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error calling onLayoutFailed", re);
                } finally {
                    destroy();
                }
            }

            @Override
            public void destroy() {
                synchronized (mLock) {
                    mCallback = null;
                    mPendingCallback = null;
                }
            }
        }

        private final class MyWriteResultCallback extends WriteResultCallback
                implements DestroyableCallback {
            private ParcelFileDescriptor mFd;
            private IWriteResultCallback mCallback;
            private final int mSequence;

            public MyWriteResultCallback(IWriteResultCallback callback,
                    ParcelFileDescriptor fd, int sequence) {
                mFd = fd;
                mSequence = sequence;
                mCallback = callback;
            }

            @Override
            public void onWriteFinished(PageRange[] pages) {
                final IWriteResultCallback callback;
                synchronized (mLock) {
                    callback = mCallback;
                }

                // If the callback is null we are destroyed.
                if (callback == null) {
                    Log.e(LOG_TAG, "PrintDocumentAdapter is destroyed. Did you "
                            + "finish the printing activity before print completion "
                            + "or did you invoke a callback after finish?");
                    return;
                }

                try {
                    if (pages == null) {
                        throw new IllegalArgumentException("pages cannot be null");
                    }
                    if (pages.length == 0) {
                        throw new IllegalArgumentException("pages cannot be empty");
                    }

                    try {
                        callback.onWriteFinished(pages, mSequence);
                    } catch (RemoteException re) {
                        Log.e(LOG_TAG, "Error calling onWriteFinished", re);
                    }
                } finally {
                    destroy();
                }
            }

            @Override
            public void onWriteFailed(CharSequence error) {
                final IWriteResultCallback callback;
                synchronized (mLock) {
                    callback = mCallback;
                }

                // If the callback is null we are destroyed.
                if (callback == null) {
                    Log.e(LOG_TAG, "PrintDocumentAdapter is destroyed. Did you "
                            + "finish the printing activity before print completion "
                            + "or did you invoke a callback after finish?");
                    return;
                }

                try {
                    callback.onWriteFailed(error, mSequence);
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error calling onWriteFailed", re);
                } finally {
                    destroy();
                }
            }

            @Override
            public void onWriteCancelled() {
                final IWriteResultCallback callback;
                synchronized (mLock) {
                    callback = mCallback;
                }

                // If the callback is null we are destroyed.
                if (callback == null) {
                    Log.e(LOG_TAG, "PrintDocumentAdapter is destroyed. Did you "
                            + "finish the printing activity before print completion "
                            + "or did you invoke a callback after finish?");
                    return;
                }

                try {
                    callback.onWriteCanceled(mSequence);
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error calling onWriteCanceled", re);
                } finally {
                    destroy();
                }
            }

            @Override
            public void destroy() {
                synchronized (mLock) {
                    IoUtils.closeQuietly(mFd);
                    mCallback = null;
                    mFd = null;
                    mPendingCallback = null;
                }
            }
        }
    }

    private static final class PrintJobStateChangeListenerWrapper extends
            IPrintJobStateChangeListener.Stub {
        private final WeakReference<PrintJobStateChangeListener> mWeakListener;
        private final WeakReference<Handler> mWeakHandler;

        public PrintJobStateChangeListenerWrapper(PrintJobStateChangeListener listener,
                Handler handler) {
            mWeakListener = new WeakReference<PrintJobStateChangeListener>(listener);
            mWeakHandler = new WeakReference<Handler>(handler);
        }

        @Override
        public void onPrintJobStateChanged(PrintJobId printJobId) {
            Handler handler = mWeakHandler.get();
            PrintJobStateChangeListener listener = mWeakListener.get();
            if (handler != null && listener != null) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = this;
                args.arg2 = printJobId;
                handler.obtainMessage(MSG_NOTIFY_PRINT_JOB_STATE_CHANGED,
                        args).sendToTarget();
            }
        }

        public void destroy() {
            mWeakListener.clear();
        }

        public PrintJobStateChangeListener getListener() {
            return mWeakListener.get();
        }
    }
}
