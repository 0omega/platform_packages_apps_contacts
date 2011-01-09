/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.contacts.vcard;

import com.android.contacts.R;
import com.android.contacts.model.AccountTypeManager;
import com.android.contacts.util.AccountSelectionUtil;
import com.android.vcard.VCardEntryCounter;
import com.android.vcard.VCardInterpreterCollection;
import com.android.vcard.VCardParser;
import com.android.vcard.VCardParser_V21;
import com.android.vcard.VCardParser_V30;
import com.android.vcard.VCardSourceDetector;
import com.android.vcard.exception.VCardException;
import com.android.vcard.exception.VCardNestedException;
import com.android.vcard.exception.VCardVersionException;

import android.accounts.Account;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

/**
 * The class letting users to import vCard. This includes the UI part for letting them select
 * an Account and posssibly a file if there's no Uri is given from its caller Activity.
 *
 * Note that this Activity assumes that the instance is a "one-shot Activity", which will be
 * finished (with the method {@link Activity#finish()}) after the import and never reuse
 * any Dialog in the instance. So this code is careless about the management around managed
 * dialogs stuffs (like how onCreateDialog() is used).
 */
public class ImportVCardActivity extends Activity {
    private static final String LOG_TAG = "VCardImport";

    private static final int SELECT_ACCOUNT = 0;

    /* package */ static final String VCARD_URI_ARRAY = "vcard_uri";
    /* package */ static final String ESTIMATED_VCARD_TYPE_ARRAY = "estimated_vcard_type";
    /* package */ static final String ESTIMATED_CHARSET_ARRAY = "estimated_charset";
    /* package */ static final String VCARD_VERSION_ARRAY = "vcard_version";
    /* package */ static final String ENTRY_COUNT_ARRAY = "entry_count";

    /* package */ final static int VCARD_VERSION_AUTO_DETECT = 0;
    /* package */ final static int VCARD_VERSION_V21 = 1;
    /* package */ final static int VCARD_VERSION_V30 = 2;

    private static final String SECURE_DIRECTORY_NAME = ".android_secure";

    final static String CACHED_URIS = "cached_uris";

    private AccountSelectionUtil.AccountSelectedListener mAccountSelectionListener;

    private Account mAccount;

    private String mAction;
    private Uri mUri;

    private ProgressDialog mProgressDialogForScanVCard;
    private ProgressDialog mProgressDialogForCachingVCard;

    private List<VCardFile> mAllVCardFileList;
    private VCardScanThread mVCardScanThread;

    private VCardCacheThread mVCardCacheThread;
    private ImportRequestConnection mConnection;

    private String mErrorMessage;

    private static class VCardFile {
        private final String mName;
        private final String mCanonicalPath;
        private final long mLastModified;

        public VCardFile(String name, String canonicalPath, long lastModified) {
            mName = name;
            mCanonicalPath = canonicalPath;
            mLastModified = lastModified;
        }

        public String getName() {
            return mName;
        }

        public String getCanonicalPath() {
            return mCanonicalPath;
        }

        public long getLastModified() {
            return mLastModified;
        }
    }

    // Runs on the UI thread.
    private class DialogDisplayer implements Runnable {
        private final int mResId;
        public DialogDisplayer(int resId) {
            mResId = resId;
        }
        public DialogDisplayer(String errorMessage) {
            mResId = R.id.dialog_error_with_message;
            mErrorMessage = errorMessage;
        }
        public void run() {
            showDialog(mResId);
        }
    }

    private class CancelListener
        implements DialogInterface.OnClickListener, DialogInterface.OnCancelListener {
        public void onClick(DialogInterface dialog, int which) {
            finish();
        }

        public void onCancel(DialogInterface dialog) {
            finish();
        }
    }

    private CancelListener mCancelListener = new CancelListener();

    private class ImportRequestConnection implements ServiceConnection {
        private Messenger mMessenger;

        public void sendImportRequest(final ImportRequest request) {
            Log.i(LOG_TAG, String.format("Send an import request (Uri: %s)", request.uri));
            try {
                mMessenger.send(Message.obtain(null,
                        VCardService.MSG_IMPORT_REQUEST,
                        request));
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "RemoteException is thrown when trying to send request");
                runOnUiThread(new DialogDisplayer(getString(R.string.fail_reason_unknown)));
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mMessenger = new Messenger(service);
            Log.i(LOG_TAG,
                    String.format("Connected to VCardService. Kick a vCard cache thread (uri: %s)",
                            Arrays.toString(mVCardCacheThread.getSourceUris())));
            mVCardCacheThread.start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(LOG_TAG, "Disconnected from VCardService");
        }
    }

    /**
     * Caches given vCard files into a local directory, and sends actual import request to
     * {@link VCardService}.
     *
     * We need to cache given files into local storage. One of reasons is that some data (as Uri)
     * may have special permissions. Callers may allow only this Activity to access that content,
     * not what this Activity launched (like {@link VCardService}).
     */
    private class VCardCacheThread extends Thread
            implements DialogInterface.OnCancelListener {
        private boolean mCanceled;
        private PowerManager.WakeLock mWakeLock;
        private VCardParser mVCardParser;
        private final Uri[] mSourceUris;  // Given from a caller.

        public VCardCacheThread(final Uri[] sourceUris) {
            mSourceUris = sourceUris;
            final Context context = ImportVCardActivity.this;
            final PowerManager powerManager =
                    (PowerManager)context.getSystemService(Context.POWER_SERVICE);
            mWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK |
                    PowerManager.ON_AFTER_RELEASE, LOG_TAG);
        }

        @Override
        public void finalize() {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                Log.w(LOG_TAG, "WakeLock is being held.");
                mWakeLock.release();
            }
        }

        @Override
        public void run() {
            Log.i(LOG_TAG, "vCard cache thread starts running.");
            if (mConnection == null) {
                throw new NullPointerException("vCard cache thread must be launched "
                        + "after a service connection is established");
            }

            mWakeLock.acquire();
            try {
                if (mCanceled == true) {
                    Log.i(LOG_TAG, "vCard cache operation is canceled.");
                    return;
                }

                final Context context = ImportVCardActivity.this;
                // Uris given from caller applications may not be opened twice: consider when
                // it is not from local storage (e.g. "file:///...") but from some special
                // provider (e.g. "content://...").
                // Thus we have to once copy the content of Uri into local storage, and read
                // it after it.
                //
                // We may be able to read content of each vCard file during copying them
                // to local storage, but currently vCard code does not allow us to do so.
                int cache_index = 0;
                for (Uri sourceUri : mSourceUris) {
                    String filename = null;
                    // Note: caches are removed by VCardService.
                    while (true) {
                        filename = VCardService.CACHE_FILE_PREFIX + cache_index + ".vcf";
                        final File file = context.getFileStreamPath(filename);
                        if (!file.exists()) {
                            break;
                        } else {
                            if (cache_index == Integer.MAX_VALUE) {
                                throw new RuntimeException("Exceeded cache limit");
                            }
                            cache_index++;
                        }
                    }
                    final Uri localDataUri = copyTo(sourceUri, filename);
                    if (mCanceled) {
                        Log.i(LOG_TAG, "vCard cache operation is canceled.");
                        break;
                    }
                    if (localDataUri == null) {
                        Log.w(LOG_TAG, "destUri is null");
                        break;
                    }
                    final ImportRequest parameter = constructImportRequest(
                            localDataUri, sourceUri);
                    if (mCanceled) {
                        Log.i(LOG_TAG, "vCard cache operation is canceled.");
                        return;
                    }
                    mConnection.sendImportRequest(parameter);
                }
            } catch (OutOfMemoryError e) {
                Log.e(LOG_TAG, "OutOfMemoryError occured during caching vCard");
                System.gc();
                runOnUiThread(new DialogDisplayer(
                        getString(R.string.fail_reason_low_memory_during_import)));
            } catch (IOException e) {
                Log.e(LOG_TAG, "IOException during caching vCard", e);
                runOnUiThread(new DialogDisplayer(
                        getString(R.string.fail_reason_io_error)));
            } finally {
                Log.i(LOG_TAG, "Finished caching vCard.");
                mWakeLock.release();
                unbindService(mConnection);
                mProgressDialogForCachingVCard.dismiss();
                mProgressDialogForCachingVCard = null;
                finish();
            }
        }

        /**
         * Copy the content of sourceUri to the destination.
         */
        private Uri copyTo(final Uri sourceUri, String filename) throws IOException {
            Log.i(LOG_TAG, String.format("Copy a Uri to app local storage (%s -> %s)",
                    sourceUri, filename));
            final Context context = ImportVCardActivity.this;
            final ContentResolver resolver = context.getContentResolver();
            ReadableByteChannel inputChannel = null;
            WritableByteChannel outputChannel = null;
            Uri destUri = null;
            try {
                inputChannel = Channels.newChannel(resolver.openInputStream(sourceUri));
                destUri = Uri.parse(context.getFileStreamPath(filename).toURI().toString());
                outputChannel = context.openFileOutput(filename, Context.MODE_PRIVATE).getChannel();
                final ByteBuffer buffer = ByteBuffer.allocateDirect(8192);
                while (inputChannel.read(buffer) != -1) {
                    if (mCanceled) {
                        Log.d(LOG_TAG, "Canceled during caching " + sourceUri);
                        return null;
                    }
                    buffer.flip();
                    outputChannel.write(buffer);
                    buffer.compact();
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    outputChannel.write(buffer);
                }
            } finally {
                if (inputChannel != null) {
                    try {
                        inputChannel.close();
                    } catch (IOException e) {
                        Log.w(LOG_TAG, "Failed to close inputChannel.");
                    }
                }
                if (outputChannel != null) {
                    try {
                        outputChannel.close();
                    } catch(IOException e) {
                        Log.w(LOG_TAG, "Failed to close outputChannel");
                    }
                }
            }
            return destUri;
        }

        /**
         * Reads localDataUri (possibly multiple times) and constructs {@link ImportRequest} from
         * its content.
         *
         * @arg localDataUri Uri actually used for the import. Should be stored in
         * app local storage, as we cannot guarantee other types of Uris can be read
         * multiple times. This variable populates {@link ImportRequest#uri}.
         * @arg originalUri Uri requested to be imported. Used mainly for displaying
         * information. This variable populates {@link ImportRequest#originalUri}.
         */
        private ImportRequest constructImportRequest(
                final Uri localDataUri, final Uri originalUri) {
            final ContentResolver resolver = ImportVCardActivity.this.getContentResolver();
            VCardEntryCounter counter = null;
            VCardSourceDetector detector = null;
            VCardInterpreterCollection interpreter = null;
            int vcardVersion = VCARD_VERSION_V21;
            try {
                boolean shouldUseV30 = false;
                InputStream is = resolver.openInputStream(localDataUri);
                mVCardParser = new VCardParser_V21();
                try {
                    counter = new VCardEntryCounter();
                    detector = new VCardSourceDetector();
                    interpreter = new VCardInterpreterCollection(
                            Arrays.asList(counter, detector));
                    mVCardParser.parse(is, interpreter);
                } catch (VCardVersionException e1) {
                    try {
                        is.close();
                    } catch (IOException e) {
                    }

                    shouldUseV30 = true;
                    is = resolver.openInputStream(localDataUri);
                    mVCardParser = new VCardParser_V30();
                    try {
                        counter = new VCardEntryCounter();
                        detector = new VCardSourceDetector();
                        interpreter = new VCardInterpreterCollection(
                                Arrays.asList(counter, detector));
                        mVCardParser.parse(is, interpreter);
                    } catch (VCardVersionException e2) {
                        throw new VCardException("vCard with unspported version.");
                    }
                } finally {
                    if (is != null) {
                        try {
                            is.close();
                        } catch (IOException e) {
                        }
                    }
                }

                vcardVersion = shouldUseV30 ? VCARD_VERSION_V30 : VCARD_VERSION_V21;
            } catch (VCardNestedException e) {
                Log.w(LOG_TAG, "Nested Exception is found (it may be false-positive).");
                // Go through without returning null.
            } catch (VCardException e) {
                Log.e(LOG_TAG, "VCardException during constructing ImportRequest", e);
                return null;
            } catch (IOException e) {
                Log.e(LOG_TAG, "IOException during constructing ImportRequest", e);
                return null;
            }
            return new ImportRequest(mAccount,
                    localDataUri, originalUri,
                    detector.getEstimatedType(),
                    detector.getEstimatedCharset(),
                    vcardVersion, counter.getCount());
        }

        public Uri[] getSourceUris() {
            return mSourceUris;
        }

        public void cancel() {
            mCanceled = true;
            if (mVCardParser != null) {
                mVCardParser.cancel();
            }
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            Log.i(LOG_TAG, "Cancel request has come. Abort caching vCard.");
            cancel();
        }
    }

    private class ImportTypeSelectedListener implements
            DialogInterface.OnClickListener {
        public static final int IMPORT_ONE = 0;
        public static final int IMPORT_MULTIPLE = 1;
        public static final int IMPORT_ALL = 2;
        public static final int IMPORT_TYPE_SIZE = 3;

        private int mCurrentIndex;

        public void onClick(DialogInterface dialog, int which) {
            if (which == DialogInterface.BUTTON_POSITIVE) {
                switch (mCurrentIndex) {
                case IMPORT_ALL:
                    importVCardFromSDCard(mAllVCardFileList);
                    break;
                case IMPORT_MULTIPLE:
                    showDialog(R.id.dialog_select_multiple_vcard);
                    break;
                default:
                    showDialog(R.id.dialog_select_one_vcard);
                    break;
                }
            } else if (which == DialogInterface.BUTTON_NEGATIVE) {
                finish();
            } else {
                mCurrentIndex = which;
            }
        }
    }

    private class VCardSelectedListener implements
            DialogInterface.OnClickListener, DialogInterface.OnMultiChoiceClickListener {
        private int mCurrentIndex;
        private Set<Integer> mSelectedIndexSet;

        public VCardSelectedListener(boolean multipleSelect) {
            mCurrentIndex = 0;
            if (multipleSelect) {
                mSelectedIndexSet = new HashSet<Integer>();
            }
        }

        public void onClick(DialogInterface dialog, int which) {
            if (which == DialogInterface.BUTTON_POSITIVE) {
                if (mSelectedIndexSet != null) {
                    List<VCardFile> selectedVCardFileList = new ArrayList<VCardFile>();
                    final int size = mAllVCardFileList.size();
                    // We'd like to sort the files by its index, so we do not use Set iterator.
                    for (int i = 0; i < size; i++) {
                        if (mSelectedIndexSet.contains(i)) {
                            selectedVCardFileList.add(mAllVCardFileList.get(i));
                        }
                    }
                    importVCardFromSDCard(selectedVCardFileList);
                } else {
                    importVCardFromSDCard(mAllVCardFileList.get(mCurrentIndex));
                }
            } else if (which == DialogInterface.BUTTON_NEGATIVE) {
                finish();
            } else {
                // Some file is selected.
                mCurrentIndex = which;
                if (mSelectedIndexSet != null) {
                    if (mSelectedIndexSet.contains(which)) {
                        mSelectedIndexSet.remove(which);
                    } else {
                        mSelectedIndexSet.add(which);
                    }
                }
            }
        }

        public void onClick(DialogInterface dialog, int which, boolean isChecked) {
            if (mSelectedIndexSet == null || (mSelectedIndexSet.contains(which) == isChecked)) {
                Log.e(LOG_TAG, String.format("Inconsist state in index %d (%s)", which,
                        mAllVCardFileList.get(which).getCanonicalPath()));
            } else {
                onClick(dialog, which);
            }
        }
    }

    /**
     * Thread scanning VCard from SDCard. After scanning, the dialog which lets a user select
     * a vCard file is shown. After the choice, VCardReadThread starts running.
     */
    private class VCardScanThread extends Thread implements OnCancelListener, OnClickListener {
        private boolean mCanceled;
        private boolean mGotIOException;
        private File mRootDirectory;

        // To avoid recursive link.
        private Set<String> mCheckedPaths;
        private PowerManager.WakeLock mWakeLock;

        private class CanceledException extends Exception {
        }

        public VCardScanThread(File sdcardDirectory) {
            mCanceled = false;
            mGotIOException = false;
            mRootDirectory = sdcardDirectory;
            mCheckedPaths = new HashSet<String>();
            PowerManager powerManager = (PowerManager)ImportVCardActivity.this.getSystemService(
                    Context.POWER_SERVICE);
            mWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK |
                    PowerManager.ON_AFTER_RELEASE, LOG_TAG);
        }

        @Override
        public void run() {
            mAllVCardFileList = new Vector<VCardFile>();
            try {
                mWakeLock.acquire();
                getVCardFileRecursively(mRootDirectory);
            } catch (CanceledException e) {
                mCanceled = true;
            } catch (IOException e) {
                mGotIOException = true;
            } finally {
                mWakeLock.release();
            }

            if (mCanceled) {
                mAllVCardFileList = null;
            }

            mProgressDialogForScanVCard.dismiss();
            mProgressDialogForScanVCard = null;

            if (mGotIOException) {
                runOnUiThread(new DialogDisplayer(R.id.dialog_io_exception));
            } else if (mCanceled) {
                finish();
            } else {
                int size = mAllVCardFileList.size();
                final Context context = ImportVCardActivity.this;
                if (size == 0) {
                    runOnUiThread(new DialogDisplayer(R.id.dialog_vcard_not_found));
                } else {
                    startVCardSelectAndImport();
                }
            }
        }

        private void getVCardFileRecursively(File directory)
                throws CanceledException, IOException {
            if (mCanceled) {
                throw new CanceledException();
            }

            // e.g. secured directory may return null toward listFiles().
            final File[] files = directory.listFiles();
            if (files == null) {
                final String currentDirectoryPath = directory.getCanonicalPath();
                final String secureDirectoryPath =
                        mRootDirectory.getCanonicalPath().concat(SECURE_DIRECTORY_NAME);
                if (!TextUtils.equals(currentDirectoryPath, secureDirectoryPath)) {
                    Log.w(LOG_TAG, "listFiles() returned null (directory: " + directory + ")");
                }
                return;
            }
            for (File file : directory.listFiles()) {
                if (mCanceled) {
                    throw new CanceledException();
                }
                String canonicalPath = file.getCanonicalPath();
                if (mCheckedPaths.contains(canonicalPath)) {
                    continue;
                }

                mCheckedPaths.add(canonicalPath);

                if (file.isDirectory()) {
                    getVCardFileRecursively(file);
                } else if (canonicalPath.toLowerCase().endsWith(".vcf") &&
                        file.canRead()){
                    String fileName = file.getName();
                    VCardFile vcardFile = new VCardFile(
                            fileName, canonicalPath, file.lastModified());
                    mAllVCardFileList.add(vcardFile);
                }
            }
        }

        public void onCancel(DialogInterface dialog) {
            mCanceled = true;
        }

        public void onClick(DialogInterface dialog, int which) {
            if (which == DialogInterface.BUTTON_NEGATIVE) {
                mCanceled = true;
            }
        }
    }

    private void startVCardSelectAndImport() {
        int size = mAllVCardFileList.size();
        if (getResources().getBoolean(R.bool.config_import_all_vcard_from_sdcard_automatically) ||
                size == 1) {
            importVCardFromSDCard(mAllVCardFileList);
        } else if (getResources().getBoolean(R.bool.config_allow_users_select_all_vcard_import)) {
            runOnUiThread(new DialogDisplayer(R.id.dialog_select_import_type));
        } else {
            runOnUiThread(new DialogDisplayer(R.id.dialog_select_one_vcard));
        }
    }

    private void importVCardFromSDCard(final List<VCardFile> selectedVCardFileList) {
        final int size = selectedVCardFileList.size();
        String[] uriStrings = new String[size];
        int i = 0;
        for (VCardFile vcardFile : selectedVCardFileList) {
            uriStrings[i] = "file://" + vcardFile.getCanonicalPath();
            i++;
        }
        importVCard(uriStrings);
    }
    
    private void importVCardFromSDCard(final VCardFile vcardFile) {
        importVCard(new Uri[] {Uri.parse("file://" + vcardFile.getCanonicalPath())});
    }

    private void importVCard(final Uri uri) {
        importVCard(new Uri[] {uri});
    }

    private void importVCard(final String[] uriStrings) {
        final int length = uriStrings.length;
        final Uri[] uris = new Uri[length];
        for (int i = 0; i < length; i++) {
            uris[i] = Uri.parse(uriStrings[i]);
        }
        importVCard(uris);
    }

    private void importVCard(final Uri[] uris) {
        runOnUiThread(new Runnable() {
            public void run() {
                mVCardCacheThread = new VCardCacheThread(uris);
                showDialog(R.id.dialog_cache_vcard);
            }
        });
    }

    private Dialog getSelectImportTypeDialog() {
        final DialogInterface.OnClickListener listener = new ImportTypeSelectedListener();
        final AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.select_vcard_title)
                .setPositiveButton(android.R.string.ok, listener)
                .setOnCancelListener(mCancelListener)
                .setNegativeButton(android.R.string.cancel, mCancelListener);

        final String[] items = new String[ImportTypeSelectedListener.IMPORT_TYPE_SIZE];
        items[ImportTypeSelectedListener.IMPORT_ONE] =
                getString(R.string.import_one_vcard_string);
        items[ImportTypeSelectedListener.IMPORT_MULTIPLE] =
                getString(R.string.import_multiple_vcard_string);
        items[ImportTypeSelectedListener.IMPORT_ALL] =
                getString(R.string.import_all_vcard_string);
        builder.setSingleChoiceItems(items, ImportTypeSelectedListener.IMPORT_ONE, listener);
        return builder.create();
    }

    private Dialog getVCardFileSelectDialog(boolean multipleSelect) {
        final int size = mAllVCardFileList.size();
        final VCardSelectedListener listener = new VCardSelectedListener(multipleSelect);
        final AlertDialog.Builder builder =
                new AlertDialog.Builder(this)
                        .setTitle(R.string.select_vcard_title)
                        .setPositiveButton(android.R.string.ok, listener)
                        .setOnCancelListener(mCancelListener)
                        .setNegativeButton(android.R.string.cancel, mCancelListener);

        CharSequence[] items = new CharSequence[size];
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (int i = 0; i < size; i++) {
            VCardFile vcardFile = mAllVCardFileList.get(i);
            SpannableStringBuilder stringBuilder = new SpannableStringBuilder();
            stringBuilder.append(vcardFile.getName());
            stringBuilder.append('\n');
            int indexToBeSpanned = stringBuilder.length();
            // Smaller date text looks better, since each file name becomes easier to read.
            // The value set to RelativeSizeSpan is arbitrary. You can change it to any other
            // value (but the value bigger than 1.0f would not make nice appearance :)
            stringBuilder.append(
                        "(" + dateFormat.format(new Date(vcardFile.getLastModified())) + ")");
            stringBuilder.setSpan(
                    new RelativeSizeSpan(0.7f), indexToBeSpanned, stringBuilder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items[i] = stringBuilder;
        }
        if (multipleSelect) {
            builder.setMultiChoiceItems(items, (boolean[])null, listener);
        } else {
            builder.setSingleChoiceItems(items, 0, listener);
        }
        return builder.create();
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        String accountName = null;
        String accountType = null;
        final Intent intent = getIntent();
        if (intent != null) {
            accountName = intent.getStringExtra(SelectAccountActivity.ACCOUNT_NAME);
            accountType = intent.getStringExtra(SelectAccountActivity.ACCOUNT_TYPE);
            mAction = intent.getAction();
            mUri = intent.getData();
        } else {
            Log.e(LOG_TAG, "intent does not exist");
        }

        if (!TextUtils.isEmpty(accountName) && !TextUtils.isEmpty(accountType)) {
            mAccount = new Account(accountName, accountType);
        } else {
            final AccountTypeManager accountTypes = AccountTypeManager.getInstance(this);
            final List<Account> accountList = accountTypes.getAccounts(true);
            if (accountList.size() == 0) {
                mAccount = null;
            } else if (accountList.size() == 1) {
                mAccount = accountList.get(0);
            } else {
                startActivityForResult(new Intent(this, SelectAccountActivity.class),
                        SELECT_ACCOUNT);
                return;
            }
        }

        startImport(mAction, mUri);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == SELECT_ACCOUNT) {
            if (resultCode == RESULT_OK) {
                mAccount = new Account(
                        intent.getStringExtra(SelectAccountActivity.ACCOUNT_NAME),
                        intent.getStringExtra(SelectAccountActivity.ACCOUNT_TYPE));
                startImport(mAction, mUri);
            } else {
                if (resultCode != RESULT_CANCELED) {
                    Log.w(LOG_TAG, "Result code was not OK nor CANCELED: " + resultCode);
                }
                finish();
            }
        }
    }

    private void startImport(String action, Uri uri) {
        if (uri != null) {
            Log.i(LOG_TAG, "Starting vCard import using Uri " + uri);
            importVCard(uri);
        } else {
            Log.i(LOG_TAG, "Start vCard without Uri. The user will select vCard manually.");
            doScanExternalStorageAndImportVCard();
        }
    }

    @Override
    protected Dialog onCreateDialog(int resId, Bundle bundle) {
        switch (resId) {
            case R.string.import_from_sdcard: {
                if (mAccountSelectionListener == null) {
                    throw new NullPointerException(
                            "mAccountSelectionListener must not be null.");
                }
                return AccountSelectionUtil.getSelectAccountDialog(this, resId,
                        mAccountSelectionListener, mCancelListener);
            }
            case R.id.dialog_searching_vcard: {
                if (mProgressDialogForScanVCard == null) {
                    String title = getString(R.string.searching_vcard_title);
                    String message = getString(R.string.searching_vcard_message);
                    mProgressDialogForScanVCard =
                        ProgressDialog.show(this, title, message, true, false);
                    mProgressDialogForScanVCard.setOnCancelListener(mVCardScanThread);
                    mVCardScanThread.start();
                }
                return mProgressDialogForScanVCard;
            }
            case R.id.dialog_sdcard_not_found: {
                AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle(R.string.no_sdcard_title)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(R.string.no_sdcard_message)
                    .setOnCancelListener(mCancelListener)
                    .setPositiveButton(android.R.string.ok, mCancelListener);
                return builder.create();
            }
            case R.id.dialog_vcard_not_found: {
                final String message = getString(R.string.import_failure_no_vcard_file);
                AlertDialog.Builder builder = new AlertDialog.Builder(this)
                        .setTitle(R.string.scanning_sdcard_failed_title)
                        .setMessage(message)
                        .setOnCancelListener(mCancelListener)
                        .setPositiveButton(android.R.string.ok, mCancelListener);
                return builder.create();
            }
            case R.id.dialog_select_import_type: {
                return getSelectImportTypeDialog();
            }
            case R.id.dialog_select_multiple_vcard: {
                return getVCardFileSelectDialog(true);
            }
            case R.id.dialog_select_one_vcard: {
                return getVCardFileSelectDialog(false);
            }
            case R.id.dialog_cache_vcard: {
                if (mProgressDialogForCachingVCard == null) {
                    final String title = getString(R.string.caching_vcard_title);
                    final String message = getString(R.string.caching_vcard_message);
                    mProgressDialogForCachingVCard = new ProgressDialog(this);
                    mProgressDialogForCachingVCard.setTitle(title);
                    mProgressDialogForCachingVCard.setMessage(message);
                    mProgressDialogForCachingVCard.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                    mProgressDialogForCachingVCard.setOnCancelListener(mVCardCacheThread);
                    mConnection = new ImportRequestConnection();

                    Log.i(LOG_TAG, "Bind to VCardService.");
                    // We don't want the service finishes itself just after this connection.
                    startService(new Intent(this, VCardService.class));
                    bindService(new Intent(this, VCardService.class),
                            mConnection, Context.BIND_AUTO_CREATE);
                }
                return mProgressDialogForCachingVCard;
            }
            case R.id.dialog_io_exception: {
                String message = (getString(R.string.scanning_sdcard_failed_message,
                        getString(R.string.fail_reason_io_error)));
                AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle(R.string.scanning_sdcard_failed_title)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(message)
                    .setOnCancelListener(mCancelListener)
                    .setPositiveButton(android.R.string.ok, mCancelListener);
                return builder.create();
            }
            case R.id.dialog_error_with_message: {
                String message = mErrorMessage;
                if (TextUtils.isEmpty(message)) {
                    Log.e(LOG_TAG, "Error message is null while it must not.");
                    message = getString(R.string.fail_reason_unknown);
                }
                final AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.reading_vcard_failed_title))
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(message)
                    .setOnCancelListener(mCancelListener)
                    .setPositiveButton(android.R.string.ok, mCancelListener);
                return builder.create();
            }
        }

        return super.onCreateDialog(resId, bundle);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (mProgressDialogForCachingVCard != null) {
            Log.i(LOG_TAG, "Cache thread is still running. Show progress dialog again.");
            showDialog(R.id.dialog_cache_vcard);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // This Activity should finish itself on orientation change, and give the main screen back
        // to the caller Activity.
        finish();
    }

    /**
     * Scans vCard in external storage (typically SDCard) and tries to import it.
     * - When there's no SDCard available, an error dialog is shown.
     * - When multiple vCard files are available, asks a user to select one.
     */
    private void doScanExternalStorageAndImportVCard() {
        // TODO: should use getExternalStorageState().
        final File file = Environment.getExternalStorageDirectory();
        if (!file.exists() || !file.isDirectory() || !file.canRead()) {
            showDialog(R.id.dialog_sdcard_not_found);
        } else {
            mVCardScanThread = new VCardScanThread(file);
            showDialog(R.id.dialog_searching_vcard);
        }
    }
}
