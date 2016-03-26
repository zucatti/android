/**
 *   ownCloud Android client application
 *
 *   Copyright (C) 2012 Bartek Przybylski
 *   Copyright (C) 2012-2015 ownCloud Inc.
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License version 2,
 *   as published by the Free Software Foundation.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.cerema.cloud.files.services;

import java.io.File;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.support.v4.app.NotificationCompat;
import android.util.Pair;
import android.webkit.MimeTypeMap;

import com.cerema.cloud.R;
import com.cerema.cloud.authentication.AccountUtils;
import com.cerema.cloud.authentication.AuthenticatorActivity;
import com.cerema.cloud.datamodel.FileDataStorageManager;
import com.cerema.cloud.datamodel.OCFile;
import com.cerema.cloud.db.DbHandler;
import com.cerema.cloud.lib.common.OwnCloudAccount;
import com.cerema.cloud.lib.common.OwnCloudClient;
import com.cerema.cloud.lib.common.OwnCloudClientManagerFactory;
import com.cerema.cloud.lib.common.network.OnDatatransferProgressListener;
import com.cerema.cloud.lib.common.operations.RemoteOperation;
import com.cerema.cloud.lib.common.operations.RemoteOperationResult;
import com.cerema.cloud.lib.common.operations.RemoteOperationResult.ResultCode;
import com.cerema.cloud.lib.common.utils.Log_OC;
import com.cerema.cloud.lib.resources.files.ExistenceCheckRemoteOperation;
import com.cerema.cloud.lib.resources.files.FileUtils;
import com.cerema.cloud.lib.resources.files.ReadRemoteFileOperation;
import com.cerema.cloud.lib.resources.files.RemoteFile;
import com.cerema.cloud.lib.resources.status.OwnCloudVersion;
import com.cerema.cloud.notifications.NotificationBuilderWithProgressBar;
import com.cerema.cloud.notifications.NotificationDelayer;
import com.cerema.cloud.operations.CreateFolderOperation;
import com.cerema.cloud.operations.UploadFileOperation;
import com.cerema.cloud.operations.common.SyncOperation;
import com.cerema.cloud.ui.activity.FileActivity;
import com.cerema.cloud.ui.activity.FileDisplayActivity;
import com.cerema.cloud.utils.ErrorMessageAdapter;
import com.cerema.cloud.utils.UriUtils;


public class FileUploader extends Service
        implements OnDatatransferProgressListener, OnAccountsUpdateListener {

    private static final String UPLOAD_FINISH_MESSAGE = "UPLOAD_FINISH";
    public static final String EXTRA_UPLOAD_RESULT = "RESULT";
    public static final String EXTRA_REMOTE_PATH = "REMOTE_PATH";
    public static final String EXTRA_OLD_REMOTE_PATH = "OLD_REMOTE_PATH";
    public static final String EXTRA_OLD_FILE_PATH = "OLD_FILE_PATH";
    public static final String EXTRA_LINKED_TO_PATH = "LINKED_TO";
    public static final String ACCOUNT_NAME = "ACCOUNT_NAME";

    public static final String KEY_FILE = "FILE";
    public static final String KEY_LOCAL_FILE = "LOCAL_FILE";
    public static final String KEY_REMOTE_FILE = "REMOTE_FILE";
    public static final String KEY_MIME_TYPE = "MIME_TYPE";

    public static final String KEY_ACCOUNT = "ACCOUNT";

    public static final String KEY_UPLOAD_TYPE = "UPLOAD_TYPE";
    public static final String KEY_FORCE_OVERWRITE = "KEY_FORCE_OVERWRITE";
    public static final String KEY_INSTANT_UPLOAD = "INSTANT_UPLOAD";
    public static final String KEY_LOCAL_BEHAVIOUR = "BEHAVIOUR";

    public static final String KEY_CANCEL_ALL = "CANCEL_ALL";

    public static final int LOCAL_BEHAVIOUR_COPY = 0;
    public static final int LOCAL_BEHAVIOUR_MOVE = 1;
    public static final int LOCAL_BEHAVIOUR_FORGET = 2;

    public static final int UPLOAD_SINGLE_FILE = 0;
    public static final int UPLOAD_MULTIPLE_FILES = 1;

    private static final String TAG = FileUploader.class.getSimpleName();

    private Looper mServiceLooper;
    private ServiceHandler mServiceHandler;
    private IBinder mBinder;
    private OwnCloudClient mUploadClient = null;
    private Account mCurrentAccount = null;
    private FileDataStorageManager mStorageManager;

    private IndexedForest<UploadFileOperation> mPendingUploads = new IndexedForest<UploadFileOperation>();
    private UploadFileOperation mCurrentUpload = null;

    private NotificationManager mNotificationManager;
    private NotificationCompat.Builder mNotificationBuilder;
    private int mLastPercent;

    private static final String MIME_TYPE_PDF = "application/pdf";
    private static final String FILE_EXTENSION_PDF = ".pdf";


    public static String getUploadFinishMessage() {
        return FileUploader.class.getName() + UPLOAD_FINISH_MESSAGE;
    }

    /**
     * Checks if an ownCloud server version should support chunked uploads.
     *
     * @param version OwnCloud version instance corresponding to an ownCloud
     *            server.
     * @return 'True' if the ownCloud server with version supports chunked
     *         uploads.
     *
     * TODO - move to OCClient
     */
    private static boolean chunkedUploadIsSupported(OwnCloudVersion version) {
        return (version != null && version.compareTo(OwnCloudVersion.owncloud_v4_5) >= 0);
    }

    /**
     * Service initialization
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log_OC.d(TAG, "Creating service");
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        HandlerThread thread = new HandlerThread("FileUploaderThread",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper, this);
        mBinder = new FileUploaderBinder();

        // add AccountsUpdatedListener
        AccountManager am = AccountManager.get(getApplicationContext());
        am.addOnAccountsUpdatedListener(this, null, false);
    }

    /**
     * Service clean up
     */
    @Override
    public void onDestroy() {
        Log_OC.v(TAG, "Destroying service" );
        mBinder = null;
        mServiceHandler = null;
        mServiceLooper.quit();
        mServiceLooper = null;
        mNotificationManager = null;

        // remove AccountsUpdatedListener
        AccountManager am = AccountManager.get(getApplicationContext());
        am.removeOnAccountsUpdatedListener(this);

        super.onDestroy();
    }


    /**
     * Entry point to add one or several files to the queue of uploads.
     *
     * New uploads are added calling to startService(), resulting in a call to
     * this method. This ensures the service will keep on working although the
     * caller activity goes away.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log_OC.d(TAG, "Starting command with id " + startId);

        if (intent.hasExtra(KEY_CANCEL_ALL) && intent.hasExtra(KEY_ACCOUNT)){
            Account account = intent.getParcelableExtra(KEY_ACCOUNT);

            if (mCurrentUpload != null) {
                FileUploaderBinder fub = (FileUploaderBinder) mBinder;
                fub.cancel(account);
                return Service.START_NOT_STICKY;
            }
        }

        if (!intent.hasExtra(KEY_ACCOUNT) || !intent.hasExtra(KEY_UPLOAD_TYPE)
                || !(intent.hasExtra(KEY_LOCAL_FILE) || intent.hasExtra(KEY_FILE))) {
            Log_OC.e(TAG, "Not enough information provided in intent");
            return Service.START_NOT_STICKY;
        }
        int uploadType = intent.getIntExtra(KEY_UPLOAD_TYPE, -1);
        if (uploadType == -1) {
            Log_OC.e(TAG, "Incorrect upload type provided");
            return Service.START_NOT_STICKY;
        }
        Account account = intent.getParcelableExtra(KEY_ACCOUNT);
        if (!AccountUtils.exists(account, getApplicationContext())) {
            return Service.START_NOT_STICKY;
        }

        String[] localPaths = null, remotePaths = null, mimeTypes = null;
        OCFile[] files = null;
        if (uploadType == UPLOAD_SINGLE_FILE) {

            if (intent.hasExtra(KEY_FILE)) {
                files = new OCFile[] { intent.getParcelableExtra(KEY_FILE) };

            } else {
                localPaths = new String[] { intent.getStringExtra(KEY_LOCAL_FILE) };
                remotePaths = new String[] { intent.getStringExtra(KEY_REMOTE_FILE) };
                mimeTypes = new String[] { intent.getStringExtra(KEY_MIME_TYPE) };
            }

        } else { // mUploadType == UPLOAD_MULTIPLE_FILES

            if (intent.hasExtra(KEY_FILE)) {
                files = (OCFile[]) intent.getParcelableArrayExtra(KEY_FILE); // TODO
                                                                             // will
                                                                             // this
                                                                             // casting
                                                                             // work
                                                                             // fine?

            } else {
                localPaths = intent.getStringArrayExtra(KEY_LOCAL_FILE);
                remotePaths = intent.getStringArrayExtra(KEY_REMOTE_FILE);
                mimeTypes = intent.getStringArrayExtra(KEY_MIME_TYPE);
            }
        }

        FileDataStorageManager storageManager = new FileDataStorageManager(account,
                getContentResolver());

        boolean forceOverwrite = intent.getBooleanExtra(KEY_FORCE_OVERWRITE, false);
        boolean isInstant = intent.getBooleanExtra(KEY_INSTANT_UPLOAD, false);
        int localAction = intent.getIntExtra(KEY_LOCAL_BEHAVIOUR, LOCAL_BEHAVIOUR_FORGET);

        if (intent.hasExtra(KEY_FILE) && files == null) {
            Log_OC.e(TAG, "Incorrect array for OCFiles provided in upload intent");
            return Service.START_NOT_STICKY;

        } else if (!intent.hasExtra(KEY_FILE)) {
            if (localPaths == null) {
                Log_OC.e(TAG, "Incorrect array for local paths provided in upload intent");
                return Service.START_NOT_STICKY;
            }
            if (remotePaths == null) {
                Log_OC.e(TAG, "Incorrect array for remote paths provided in upload intent");
                return Service.START_NOT_STICKY;
            }
            if (localPaths.length != remotePaths.length) {
                Log_OC.e(TAG, "Different number of remote paths and local paths!");
                return Service.START_NOT_STICKY;
            }

            files = new OCFile[localPaths.length];
            for (int i = 0; i < localPaths.length; i++) {
                files[i] = obtainNewOCFileToUpload(remotePaths[i], localPaths[i],
                        ((mimeTypes != null) ? mimeTypes[i] : null));
                if (files[i] == null) {
                    // TODO @andomaex add failure Notification
                    return Service.START_NOT_STICKY;
                }
            }
        }

        OwnCloudVersion ocv = AccountUtils.getServerVersion(account);

        boolean chunked = FileUploader.chunkedUploadIsSupported(ocv);
        AbstractList<String> requestedUploads = new Vector<String>();
        String uploadKey = null;
        UploadFileOperation newUpload = null;
        try {
            for (int i = 0; i < files.length; i++) {
                newUpload = new UploadFileOperation(
                        account,
                        files[i],
                        chunked,
                        isInstant,
                        forceOverwrite, localAction,
                        getApplicationContext()
                );
                if (isInstant) {
                    newUpload.setRemoteFolderToBeCreated();
                }
                newUpload.addDatatransferProgressListener(this);
                newUpload.addDatatransferProgressListener((FileUploaderBinder) mBinder);
                Pair<String, String> putResult = mPendingUploads.putIfAbsent(
                        account, files[i].getRemotePath(), newUpload
                );
                if (putResult != null) {
                    uploadKey = putResult.first;
                    requestedUploads.add(uploadKey);
                }   // else, file already in the queue of uploads; don't repeat the request
            }

        } catch (IllegalArgumentException e) {
            Log_OC.e(TAG, "Not enough information provided in intent: " + e.getMessage());
            return START_NOT_STICKY;

        } catch (IllegalStateException e) {
            Log_OC.e(TAG, "Bad information provided in intent: " + e.getMessage());
            return START_NOT_STICKY;

        } catch (Exception e) {
            Log_OC.e(TAG, "Unexpected exception while processing upload intent", e);
            return START_NOT_STICKY;

        }

        if (requestedUploads.size() > 0) {
            Message msg = mServiceHandler.obtainMessage();
            msg.arg1 = startId;
            msg.obj = requestedUploads;
            mServiceHandler.sendMessage(msg);
        }
        return Service.START_NOT_STICKY;
    }

    /**
     * Provides a binder object that clients can use to perform operations on
     * the queue of uploads, excepting the addition of new files.
     *
     * Implemented to perform cancellation, pause and resume of existing
     * uploads.
     */
    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    /**
     * Called when ALL the bound clients were onbound.
     */
    @Override
    public boolean onUnbind(Intent intent) {
        ((FileUploaderBinder)mBinder).clearListeners();
        return false;   // not accepting rebinding (default behaviour)
    }

    @Override
    public void onAccountsUpdated(Account[] accounts) {
        // Review current upload, and cancel it if its account doen't exist
        if (mCurrentUpload != null &&
                !AccountUtils.exists(mCurrentUpload.getAccount(), getApplicationContext())) {
            mCurrentUpload.cancel();
        }
        // The rest of uploads are cancelled when they try to start
    }

    /**
     * Binder to let client components to perform operations on the queue of
     * uploads.
     *
     * It provides by itself the available operations.
     */
    public class FileUploaderBinder extends Binder implements OnDatatransferProgressListener {

        /**
         * Map of listeners that will be reported about progress of uploads from a
         * {@link FileUploaderBinder} instance
         */
        private Map<String, OnDatatransferProgressListener> mBoundListeners =
                new HashMap<String, OnDatatransferProgressListener>();

        /**
         * Cancels a pending or current upload of a remote file.
         *
         * @param account   ownCloud account where the remote file will be stored.
         * @param file      A file in the queue of pending uploads
         */
        public void cancel(Account account, OCFile file) {
            Pair<UploadFileOperation, String> removeResult = mPendingUploads.remove(account, file.getRemotePath());
            UploadFileOperation upload = removeResult.first;
            if (upload != null) {
                upload.cancel();
            } else {
                if (mCurrentUpload != null && mCurrentAccount != null &&
                        mCurrentUpload.getRemotePath().startsWith(file.getRemotePath()) &&
                        account.name.equals(mCurrentAccount.name)) {
                    mCurrentUpload.cancel();
                }
            }
        }

        /**
         * Cancels all the uploads for an account
         *
         * @param account   ownCloud account.
         */
        public void cancel(Account account) {
            Log_OC.d(TAG, "Account= " + account.name);

            if (mCurrentUpload != null) {
                Log_OC.d(TAG, "Current Upload Account= " + mCurrentUpload.getAccount().name);
                if (mCurrentUpload.getAccount().name.equals(account.name)) {
                    mCurrentUpload.cancel();
                }
            }
            // Cancel pending uploads
            cancelUploadsForAccount(account);
        }

        public void clearListeners() {
            mBoundListeners.clear();
        }


        /**
         * Returns True when the file described by 'file' is being uploaded to
         * the ownCloud account 'account' or waiting for it
         *
         * If 'file' is a directory, returns 'true' if some of its descendant files
         * is uploading or waiting to upload.
         *
         * @param account   ownCloud account where the remote file will be stored.
         * @param file      A file that could be in the queue of pending uploads
         */
        public boolean isUploading(Account account, OCFile file) {
            if (account == null || file == null) return false;
            return (mPendingUploads.contains(account, file.getRemotePath()));
        }


        /**
         * Adds a listener interested in the progress of the upload for a concrete file.
         *
         * @param listener      Object to notify about progress of transfer.    
         * @param account       ownCloud account holding the file of interest.
         * @param file          {@link OCFile} of interest for listener.
         */
        public void addDatatransferProgressListener (OnDatatransferProgressListener listener,
                                                     Account account, OCFile file) {
            if (account == null || file == null || listener == null) return;
            String targetKey = buildRemoteName(account, file);
            mBoundListeners.put(targetKey, listener);
        }



        /**
         * Removes a listener interested in the progress of the upload for a concrete file.
         *
         * @param listener      Object to notify about progress of transfer.    
         * @param account       ownCloud account holding the file of interest.
         * @param file          {@link OCFile} of interest for listener.
         */
        public void removeDatatransferProgressListener (OnDatatransferProgressListener listener,
                                                        Account account, OCFile file) {
            if (account == null || file == null || listener == null) return;
            String targetKey = buildRemoteName(account, file);
            if (mBoundListeners.get(targetKey) == listener) {
                mBoundListeners.remove(targetKey);
            }
        }


        @Override
        public void onTransferProgress(long progressRate, long totalTransferredSoFar,
                                       long totalToTransfer, String fileName) {
            String key = buildRemoteName(mCurrentUpload.getAccount(), mCurrentUpload.getFile());
            OnDatatransferProgressListener boundListener = mBoundListeners.get(key);
            if (boundListener != null) {
                boundListener.onTransferProgress(progressRate, totalTransferredSoFar,
                        totalToTransfer, fileName);
            }
        }

        /**
         * Builds a key for the map of listeners.
         *
         * TODO remove and replace key with file.getFileId() after changing current policy (upload file, then
         * add to local database) to better policy (add to local database, then upload)
         *
         * @param account       ownCloud account where the file to upload belongs.
         * @param file          File to upload
         * @return              Key
         */
        private String buildRemoteName(Account account, OCFile file) {
            return account.name + file.getRemotePath();
        }

    }

    /**
     * Upload worker. Performs the pending uploads in the order they were
     * requested.
     *
     * Created with the Looper of a new thread, started in
     * {@link FileUploader#onCreate()}.
     */
    private static class ServiceHandler extends Handler {
        // don't make it a final class, and don't remove the static ; lint will
        // warn about a possible memory leak
        FileUploader mService;

        public ServiceHandler(Looper looper, FileUploader service) {
            super(looper);
            if (service == null)
                throw new IllegalArgumentException("Received invalid NULL in parameter 'service'");
            mService = service;
        }

        @Override
        public void handleMessage(Message msg) {
            @SuppressWarnings("unchecked")
            AbstractList<String> requestedUploads = (AbstractList<String>) msg.obj;
            if (msg.obj != null) {
                Iterator<String> it = requestedUploads.iterator();
                while (it.hasNext()) {
                    mService.uploadFile(it.next());
                }
            }
            Log_OC.d(TAG, "Stopping command after id " + msg.arg1);
            mService.stopSelf(msg.arg1);
        }
    }

    /**
     * Core upload method: sends the file(s) to upload
     *
     * @param uploadKey Key to access the upload to perform, contained in mPendingUploads
     */
    public void uploadFile(String uploadKey) {

        mCurrentUpload = mPendingUploads.get(uploadKey);

        if (mCurrentUpload != null) {
            // Detect if the account exists
            if (AccountUtils.exists(mCurrentUpload.getAccount(), getApplicationContext())) {
                Log_OC.d(TAG, "Account " + mCurrentUpload.getAccount().name + " exists");

                notifyUploadStart(mCurrentUpload);

                RemoteOperationResult uploadResult = null, grantResult;

                try {
                    /// prepare client object to send the request to the ownCloud server
                    if (mCurrentAccount == null || !mCurrentAccount.equals(mCurrentUpload.getAccount())) {
                        mCurrentAccount = mCurrentUpload.getAccount();
                        mStorageManager = new FileDataStorageManager(
                                mCurrentAccount,
                                getContentResolver()
                        );
                    }   // else, reuse storage manager from previous operation

                    // always get client from client manager, to get fresh credentials in case of update
                    OwnCloudAccount ocAccount = new OwnCloudAccount(mCurrentAccount, this);
                    mUploadClient = OwnCloudClientManagerFactory.getDefaultSingleton().
                            getClientFor(ocAccount, this);


                    /// check the existence of the parent folder for the file to upload
                    String remoteParentPath = new File(mCurrentUpload.getRemotePath()).getParent();
                    remoteParentPath = remoteParentPath.endsWith(OCFile.PATH_SEPARATOR) ?
                            remoteParentPath : remoteParentPath + OCFile.PATH_SEPARATOR;
                    grantResult = grantFolderExistence(remoteParentPath);

                    /// perform the upload
                    if (grantResult.isSuccess()) {
                        OCFile parent = mStorageManager.getFileByPath(remoteParentPath);
                        mCurrentUpload.getFile().setParentId(parent.getFileId());
                        uploadResult = mCurrentUpload.execute(mUploadClient);
                        if (uploadResult.isSuccess()) {
                            saveUploadedFile();

                        } else if (uploadResult.getCode() == ResultCode.SYNC_CONFLICT) {
                            mStorageManager.saveConflict(mCurrentUpload.getFile(),
                                    mCurrentUpload.getFile().getEtagInConflict());
                        }
                    } else {
                        uploadResult = grantResult;
                    }

                } catch (Exception e) {
                    Log_OC.e(TAG, "Error uploading", e);
                    uploadResult = new RemoteOperationResult(e);

                } finally {
                    Pair<UploadFileOperation, String> removeResult;
                    if (mCurrentUpload.wasRenamed()) {
                        removeResult = mPendingUploads.removePayload(
                                mCurrentAccount,
                                mCurrentUpload.getOldFile().getRemotePath()
                        );
                    } else {
                        removeResult = mPendingUploads.removePayload(
                                mCurrentAccount,
                                mCurrentUpload.getRemotePath()
                        );
                    }

                    /// notify result
                    notifyUploadResult(mCurrentUpload, uploadResult);

                    sendBroadcastUploadFinished(mCurrentUpload, uploadResult, removeResult.second);
                }

            } else {
                // Cancel the transfer
                Log_OC.d(TAG, "Account " + mCurrentUpload.getAccount().toString() +
                        " doesn't exist");
                cancelUploadsForAccount(mCurrentUpload.getAccount());

            }
        }

    }

    /**
     * Checks the existence of the folder where the current file will be uploaded both
     * in the remote server and in the local database.
     *
     * If the upload is set to enforce the creation of the folder, the method tries to
     * create it both remote and locally.
     *
     *  @param  pathToGrant     Full remote path whose existence will be granted.
     *  @return  An {@link OCFile} instance corresponding to the folder where the file
     *  will be uploaded.
     */
    private RemoteOperationResult grantFolderExistence(String pathToGrant) {
        RemoteOperation operation = new ExistenceCheckRemoteOperation(pathToGrant, this, false);
        RemoteOperationResult result = operation.execute(mUploadClient);
        if (!result.isSuccess() && result.getCode() == ResultCode.FILE_NOT_FOUND &&
                mCurrentUpload.isRemoteFolderToBeCreated()) {
            SyncOperation syncOp = new CreateFolderOperation( pathToGrant, true);
            result = syncOp.execute(mUploadClient, mStorageManager);
        }
        if (result.isSuccess()) {
            OCFile parentDir = mStorageManager.getFileByPath(pathToGrant);
            if (parentDir == null) {
                parentDir = createLocalFolder(pathToGrant);
            }
            if (parentDir != null) {
                result = new RemoteOperationResult(ResultCode.OK);
            } else {
                result = new RemoteOperationResult(ResultCode.UNKNOWN_ERROR);
            }
        }
        return result;
    }


    private OCFile createLocalFolder(String remotePath) {
        String parentPath = new File(remotePath).getParent();
        parentPath = parentPath.endsWith(OCFile.PATH_SEPARATOR) ?
                parentPath : parentPath + OCFile.PATH_SEPARATOR;
        OCFile parent = mStorageManager.getFileByPath(parentPath);
        if (parent == null) {
            parent = createLocalFolder(parentPath);
        }
        if (parent != null) {
            OCFile createdFolder = new OCFile(remotePath);
            createdFolder.setMimetype("DIR");
            createdFolder.setParentId(parent.getFileId());
            mStorageManager.saveFile(createdFolder);
            return createdFolder;
        }
        return null;
    }


    /**
     * Saves a OC File after a successful upload.
     *
     * A PROPFIND is necessary to keep the props in the local database
     * synchronized with the server, specially the modification time and Etag
     * (where available)
     *
     * TODO move into UploadFileOperation
     */
    private void saveUploadedFile() {
        OCFile file = mCurrentUpload.getFile();
        if (file.fileExists()) {
            file = mStorageManager.getFileById(file.getFileId());
        }
        long syncDate = System.currentTimeMillis();
        file.setLastSyncDateForData(syncDate);

        // new PROPFIND to keep data consistent with server 
        // in theory, should return the same we already have
        ReadRemoteFileOperation operation =
                new ReadRemoteFileOperation(mCurrentUpload.getRemotePath());
        RemoteOperationResult result = operation.execute(mUploadClient);
        if (result.isSuccess()) {
            updateOCFile(file, (RemoteFile) result.getData().get(0));
            file.setLastSyncDateForProperties(syncDate);
        } else {
            Log_OC.e(TAG, "Error reading properties of file after successful upload; this is gonna hurt...");
        }

        // / maybe this would be better as part of UploadFileOperation... or
        // maybe all this method
        if (mCurrentUpload.wasRenamed()) {
            OCFile oldFile = mCurrentUpload.getOldFile();
            if (oldFile.fileExists()) {
                oldFile.setStoragePath(null);
                mStorageManager.saveFile(oldFile);
                mStorageManager.saveConflict(oldFile, null);

            } // else: it was just an automatic renaming due to a name
            // coincidence; nothing else is needed, the storagePath is right
            // in the instance returned by mCurrentUpload.getFile()
        }
        file.setNeedsUpdateThumbnail(true);
        mStorageManager.saveFile(file);
        mStorageManager.saveConflict(file, null);
        
        mStorageManager.triggerMediaScan(file.getStoragePath());

    }

    private void updateOCFile(OCFile file, RemoteFile remoteFile) {
        file.setCreationTimestamp(remoteFile.getCreationTimestamp());
        file.setFileLength(remoteFile.getLength());
        file.setMimetype(remoteFile.getMimeType());
        file.setModificationTimestamp(remoteFile.getModifiedTimestamp());
        file.setModificationTimestampAtLastSyncForData(remoteFile.getModifiedTimestamp());
        file.setEtag(remoteFile.getEtag());
        file.setRemoteId(remoteFile.getRemoteId());
    }

    private OCFile obtainNewOCFileToUpload(String remotePath, String localPath, String mimeType) {

        // MIME type
        if (mimeType == null || mimeType.length() <= 0) {
            try {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        remotePath.substring(remotePath.lastIndexOf('.') + 1));
            } catch (IndexOutOfBoundsException e) {
                Log_OC.e(TAG, "Trying to find out MIME type of a file without extension: " +
                        remotePath);
            }
        }
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }

        if (isPdfFileFromContentProviderWithoutExtension(localPath, mimeType)){
            remotePath += FILE_EXTENSION_PDF;
        }

        OCFile newFile = new OCFile(remotePath);
        newFile.setStoragePath(localPath);
        newFile.setLastSyncDateForProperties(0);
        newFile.setLastSyncDateForData(0);

        // size
        if (localPath != null && localPath.length() > 0) {
            File localFile = new File(localPath);
            newFile.setFileLength(localFile.length());
            newFile.setLastSyncDateForData(localFile.lastModified());
        } // don't worry about not assigning size, the problems with localPath
        // are checked when the UploadFileOperation instance is created


        newFile.setMimetype(mimeType);

        return newFile;
    }

    /**
     * Creates a status notification to show the upload progress
     *
     * @param upload Upload operation starting.
     */
    private void notifyUploadStart(UploadFileOperation upload) {
        // / create status notification with a progress bar
        mLastPercent = 0;
        mNotificationBuilder =
                NotificationBuilderWithProgressBar.newNotificationBuilderWithProgressBar(this);
        mNotificationBuilder
                .setOngoing(true)
                .setSmallIcon(R.drawable.notification_icon)
                .setTicker(getString(R.string.uploader_upload_in_progress_ticker))
                .setContentTitle(getString(R.string.uploader_upload_in_progress_ticker))
                .setProgress(100, 0, false)
                .setContentText(
                        String.format(getString(R.string.uploader_upload_in_progress_content), 0, upload.getFileName()));

        /// includes a pending intent in the notification showing the details view of the file
        Intent showDetailsIntent = new Intent(this, FileDisplayActivity.class);
        showDetailsIntent.putExtra(FileActivity.EXTRA_FILE, upload.getFile());
        showDetailsIntent.putExtra(FileActivity.EXTRA_ACCOUNT, upload.getAccount());
        showDetailsIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mNotificationBuilder.setContentIntent(PendingIntent.getActivity(
                this, (int) System.currentTimeMillis(), showDetailsIntent, 0
        ));

        mNotificationManager.notify(R.string.uploader_upload_in_progress_ticker, mNotificationBuilder.build());
    }

    /**
     * Callback method to update the progress bar in the status notification
     */
    @Override
    public void onTransferProgress(long progressRate, long totalTransferredSoFar,
                                   long totalToTransfer, String filePath) {
        int percent = (int) (100.0 * ((double) totalTransferredSoFar) / ((double) totalToTransfer));
        if (percent != mLastPercent) {
            mNotificationBuilder.setProgress(100, percent, false);
            String fileName = filePath.substring(
                    filePath.lastIndexOf(FileUtils.PATH_SEPARATOR) + 1);
            String text = String.format(getString(R.string.uploader_upload_in_progress_content), percent, fileName);
            mNotificationBuilder.setContentText(text);
            mNotificationManager.notify(R.string.uploader_upload_in_progress_ticker, mNotificationBuilder.build());
        }
        mLastPercent = percent;
    }

    /**
     * Updates the status notification with the result of an upload operation.
     *
     * @param uploadResult  Result of the upload operation.
     * @param upload        Finished upload operation
     */
    private void notifyUploadResult(UploadFileOperation upload,
                                    RemoteOperationResult uploadResult) {
        Log_OC.d(TAG, "NotifyUploadResult with resultCode: " + uploadResult.getCode());
        // / cancelled operation or success -> silent removal of progress notification
        mNotificationManager.cancel(R.string.uploader_upload_in_progress_ticker);

        // Show the result: success or fail notification
        if (!uploadResult.isCancelled()) {
            int tickerId = (uploadResult.isSuccess()) ? R.string.uploader_upload_succeeded_ticker :
                    R.string.uploader_upload_failed_ticker;

            String content;

            // check credentials error
            boolean needsToUpdateCredentials = (
                    uploadResult.getCode() == ResultCode.UNAUTHORIZED ||
                            uploadResult.isIdPRedirection()
            );
            tickerId = (needsToUpdateCredentials) ?
                    R.string.uploader_upload_failed_credentials_error : tickerId;

            mNotificationBuilder
                    .setTicker(getString(tickerId))
                    .setContentTitle(getString(tickerId))
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setProgress(0, 0, false);

            content =  ErrorMessageAdapter.getErrorCauseMessage(
                    uploadResult, upload, getResources()
            );

            if (needsToUpdateCredentials) {
                // let the user update credentials with one click
                Intent updateAccountCredentials = new Intent(this, AuthenticatorActivity.class);
                updateAccountCredentials.putExtra(
                        AuthenticatorActivity.EXTRA_ACCOUNT, upload.getAccount()
                );
                updateAccountCredentials.putExtra(
                        AuthenticatorActivity.EXTRA_ACTION,
                        AuthenticatorActivity.ACTION_UPDATE_EXPIRED_TOKEN
                );
                updateAccountCredentials.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                updateAccountCredentials.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                updateAccountCredentials.addFlags(Intent.FLAG_FROM_BACKGROUND);
                mNotificationBuilder.setContentIntent(PendingIntent.getActivity(
                        this,
                        (int) System.currentTimeMillis(),
                        updateAccountCredentials,
                        PendingIntent.FLAG_ONE_SHOT
                ));

                mUploadClient = null;
                // grant that future retries on the same account will get the fresh credentials
            } else {
                mNotificationBuilder.setContentText(content);

                if (upload.isInstant()) {
                    DbHandler db = null;
                    try {
                        db = new DbHandler(this.getBaseContext());
                        String message = uploadResult.getLogMessage() + " errorCode: " +
                                uploadResult.getCode();
                        Log_OC.e(TAG, message + " Http-Code: " + uploadResult.getHttpCode());
                        if (uploadResult.getCode() == ResultCode.QUOTA_EXCEEDED) {
                            //message = getString(R.string.failed_upload_quota_exceeded_text);
                            if (db.updateFileState(
                                    upload.getOriginalStoragePath(),
                                    DbHandler.UPLOAD_STATUS_UPLOAD_FAILED,
                                    message) == 0) {
                                db.putFileForLater(
                                        upload.getOriginalStoragePath(),
                                        upload.getAccount().name,
                                        message
                                );
                            }
                        }
                    } finally {
                        if (db != null) {
                            db.close();
                        }
                    }
                }
            }

            mNotificationBuilder.setContentText(content);
            mNotificationManager.notify(tickerId, mNotificationBuilder.build());

            if (uploadResult.isSuccess()) {

                DbHandler db = new DbHandler(this.getBaseContext());
                db.removeIUPendingFile(mCurrentUpload.getOriginalStoragePath());
                db.close();

                // remove success notification, with a delay of 2 seconds
                NotificationDelayer.cancelWithDelay(
                        mNotificationManager,
                        R.string.uploader_upload_succeeded_ticker,
                        2000);

            }
        }
    }

    /**
     * Sends a broadcast in order to the interested activities can update their
     * view
     *
     * @param upload                    Finished upload operation
     * @param uploadResult              Result of the upload operation
     * @param unlinkedFromRemotePath    Path in the uploads tree where the upload was unlinked from
     */
    private void sendBroadcastUploadFinished(
            UploadFileOperation upload,
            RemoteOperationResult uploadResult,
            String unlinkedFromRemotePath) {

        Intent end = new Intent(getUploadFinishMessage());
        end.putExtra(EXTRA_REMOTE_PATH, upload.getRemotePath()); // real remote
        // path, after
        // possible
        // automatic
        // renaming
        if (upload.wasRenamed()) {
            end.putExtra(EXTRA_OLD_REMOTE_PATH, upload.getOldFile().getRemotePath());
        }
        end.putExtra(EXTRA_OLD_FILE_PATH, upload.getOriginalStoragePath());
        end.putExtra(ACCOUNT_NAME, upload.getAccount().name);
        end.putExtra(EXTRA_UPLOAD_RESULT, uploadResult.isSuccess());
        if (unlinkedFromRemotePath != null) {
            end.putExtra(EXTRA_LINKED_TO_PATH, unlinkedFromRemotePath);
        }

        sendStickyBroadcast(end);
    }

    /**
     * Checks if content provider, using the content:// scheme, returns a file with mime-type 
     * 'application/pdf' but file has not extension
     * @param localPath         Full path to a file in the local file system.
     * @param mimeType          MIME type of the file.
     * @return true if is needed to add the pdf file extension to the file
     *
     * TODO - move to OCFile or Utils class
     */
    private boolean isPdfFileFromContentProviderWithoutExtension(String localPath,
                                                                 String mimeType) {
        return localPath.startsWith(UriUtils.URI_CONTENT_SCHEME) &&
                mimeType.equals(MIME_TYPE_PDF) &&
                !localPath.endsWith(FILE_EXTENSION_PDF);
    }

    /**
     * Remove uploads of an account
     *
     * @param account       Downloads account to remove
     */
    private void cancelUploadsForAccount(Account account){
        // Cancel pending uploads
        mPendingUploads.remove(account);
    }
}
