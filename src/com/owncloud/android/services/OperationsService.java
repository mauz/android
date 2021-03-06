/* ownCloud Android client application
 *   Copyright (C) 2012-2014 ownCloud Inc.
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

package com.owncloud.android.services;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import com.owncloud.android.R;
import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.lib.common.OwnCloudClientFactory;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.operations.OnRemoteOperationListener;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.resources.files.ExistenceCheckRemoteOperation;
import com.owncloud.android.lib.resources.shares.ShareType;
import com.owncloud.android.lib.resources.users.GetRemoteUserNameOperation;
import com.owncloud.android.operations.common.SyncOperation;
import com.owncloud.android.operations.CreateFolderOperation;
import com.owncloud.android.operations.CreateShareOperation;
import com.owncloud.android.operations.GetServerInfoOperation;
import com.owncloud.android.operations.OAuth2GetAccessToken;
import com.owncloud.android.operations.RemoveFileOperation;
import com.owncloud.android.operations.RenameFileOperation;
import com.owncloud.android.operations.SynchronizeFileOperation;
import com.owncloud.android.operations.UnshareLinkOperation;
import com.owncloud.android.utils.Log_OC;

import android.accounts.Account;
import android.accounts.AccountsException;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Pair;

public class OperationsService extends Service {
    
    private static final String TAG = OperationsService.class.getSimpleName();
    
    public static final String EXTRA_ACCOUNT = "ACCOUNT";
    public static final String EXTRA_SERVER_URL = "SERVER_URL";
    public static final String EXTRA_AUTH_TOKEN_TYPE = "AUTH_TOKEN_TYPE";
    public static final String EXTRA_OAUTH2_QUERY_PARAMETERS = "OAUTH2_QUERY_PARAMETERS";
    public static final String EXTRA_REMOTE_PATH = "REMOTE_PATH";
    public static final String EXTRA_SEND_INTENT = "SEND_INTENT";
    public static final String EXTRA_NEWNAME = "NEWNAME";
    public static final String EXTRA_REMOVE_ONLY_LOCAL = "REMOVE_LOCAL_COPY";
    public static final String EXTRA_CREATE_FULL_PATH = "CREATE_FULL_PATH";
    public static final String EXTRA_SYNC_FILE_CONTENTS = "SYNC_FILE_CONTENTS";
    public static final String EXTRA_RESULT = "RESULT";
    
    // TODO review if ALL OF THEM are necessary
    public static final String EXTRA_WEBDAV_PATH = "WEBDAV_PATH";
    public static final String EXTRA_SUCCESS_IF_ABSENT = "SUCCESS_IF_ABSENT";
    public static final String EXTRA_USERNAME = "USERNAME";
    public static final String EXTRA_PASSWORD = "PASSWORD";
    public static final String EXTRA_AUTH_TOKEN = "AUTH_TOKEN";
    public static final String EXTRA_FOLLOW_REDIRECTS = "FOLLOW_REDIRECTS";
    public static final String EXTRA_COOKIE = "COOKIE";
    
    public static final String ACTION_CREATE_SHARE = "CREATE_SHARE";
    public static final String ACTION_UNSHARE = "UNSHARE";
    public static final String ACTION_GET_SERVER_INFO = "GET_SERVER_INFO";
    public static final String ACTION_OAUTH2_GET_ACCESS_TOKEN = "OAUTH2_GET_ACCESS_TOKEN";
    public static final String ACTION_EXISTENCE_CHECK = "EXISTENCE_CHECK";
    public static final String ACTION_GET_USER_NAME = "GET_USER_NAME";
    public static final String ACTION_RENAME = "RENAME";
    public static final String ACTION_REMOVE = "REMOVE";
    public static final String ACTION_CREATE_FOLDER = "CREATE_FOLDER";
    public static final String ACTION_SYNC_FILE = "SYNC_FILE";
    
    public static final String ACTION_OPERATION_ADDED = OperationsService.class.getName() + ".OPERATION_ADDED";
    public static final String ACTION_OPERATION_FINISHED = OperationsService.class.getName() + ".OPERATION_FINISHED";

    private ConcurrentLinkedQueue<Pair<Target, RemoteOperation>> mPendingOperations = 
            new ConcurrentLinkedQueue<Pair<Target, RemoteOperation>>();

    private ConcurrentMap<Integer, Pair<RemoteOperation, RemoteOperationResult>> 
        mUndispatchedFinishedOperations =
            new ConcurrentHashMap<Integer, Pair<RemoteOperation, RemoteOperationResult>>();
    
    private static class Target {
        public Uri mServerUrl = null;
        public Account mAccount = null;
        public String mWebDavUrl = null;
        public String mUsername = null;
        public String mPassword = null;
        public String mAuthToken = null;
        public boolean mFollowRedirects = true;
        public String mCookie = null;
        
        public Target(Account account, Uri serverUrl, String webdavUrl, String username, String password, String authToken,
                boolean followRedirects, String cookie) {
            mAccount = account;
            mServerUrl = serverUrl;
            mWebDavUrl = webdavUrl;
            mUsername = username;
            mPassword = password;
            mAuthToken = authToken;
            mFollowRedirects = followRedirects;
            mCookie = cookie;
        }
    }

    private Looper mServiceLooper;
    private ServiceHandler mServiceHandler;
    private OperationsServiceBinder mBinder;
    private OwnCloudClient mOwnCloudClient = null;
    private Target mLastTarget = null;
    private FileDataStorageManager mStorageManager;
    private RemoteOperation mCurrentOperation = null;
    
    
    /**
     * Service initialization
     */
    @Override
    public void onCreate() {
        super.onCreate();
        HandlerThread thread = new HandlerThread("Operations service thread", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper, this);
        mBinder = new OperationsServiceBinder();
    }

    
    /**
     * Entry point to add a new operation to the queue of operations.
     * 
     * New operations are added calling to startService(), resulting in a call to this method. 
     * This ensures the service will keep on working although the caller activity goes away.
     * 
     * IMPORTANT: the only operations performed here right now is {@link GetSharedFilesOperation}. The class
     * is taking advantage of it due to time constraints.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //Log_OC.wtf(TAG, "onStartCommand init" );
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = startId;
        mServiceHandler.sendMessage(msg);
        //Log_OC.wtf(TAG, "onStartCommand end" );
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        //Log_OC.wtf(TAG, "onDestroy init" );
        //Log_OC.wtf(TAG, "Clear mUndispatchedFinisiedOperations" );
        mUndispatchedFinishedOperations.clear();
        //Log_OC.wtf(TAG, "onDestroy end" );
        super.onDestroy();
    }


    /**
     * Provides a binder object that clients can use to perform actions on the queue of operations, 
     * except the addition of new operations. 
     */
    @Override
    public IBinder onBind(Intent intent) {
        //Log_OC.wtf(TAG, "onBind" );
        return mBinder;
    }

    
    /**
     * Called when ALL the bound clients were unbound.
     */
    @Override
    public boolean onUnbind(Intent intent) {
        ((OperationsServiceBinder)mBinder).clearListeners();
        return false;   // not accepting rebinding (default behaviour)
    }

    
    /**
     *  Binder to let client components to perform actions on the queue of operations.
     * 
     *  It provides by itself the available operations.
     */
    public class OperationsServiceBinder extends Binder /* implements OnRemoteOperationListener */ {
        
        /** 
         * Map of listeners that will be reported about the end of operations from a {@link OperationsServiceBinder} instance 
         */
        private ConcurrentMap<OnRemoteOperationListener, Handler> mBoundListeners = 
                new ConcurrentHashMap<OnRemoteOperationListener, Handler>();
        
        /**
         * Cancels an operation
         *
         * TODO
         */
        public void cancel() {
            // TODO
        }
        
        
        public void clearListeners() {
            
            mBoundListeners.clear();
        }

        
        /**
         * Adds a listener interested in being reported about the end of operations.
         * 
         * @param listener          Object to notify about the end of operations.    
         * @param callbackHandler   {@link Handler} to access the listener without breaking Android threading protection.
         */
        public void addOperationListener (OnRemoteOperationListener listener, Handler callbackHandler) {
            synchronized (mBoundListeners) {
                mBoundListeners.put(listener, callbackHandler);
            }
        }
        
        
        /**
         * Removes a listener from the list of objects interested in the being reported about the end of operations.
         * 
         * @param listener      Object to notify about progress of transfer.    
         */
        public void removeOperationListener (OnRemoteOperationListener listener) {
            synchronized (mBoundListeners) {
                mBoundListeners.remove(listener);
            }
        }


        /**
         * TODO - IMPORTANT: update implementation when more operations are moved into the service 
         * 
         * @return  'True' when an operation that enforces the user to wait for completion is in process.
         */
        public boolean isPerformingBlockingOperation() {
            return (!mPendingOperations.isEmpty());
        }


        /**
         * Creates and adds to the queue a new operation, as described by operationIntent
         * 
         * @param operationIntent       Intent describing a new operation to queue and execute.
         * @return                      Identifier of the operation created, or null if failed.
         */
        public long newOperation(Intent operationIntent) {
            RemoteOperation operation = null;
            Target target = null;
            try {
                if (!operationIntent.hasExtra(EXTRA_ACCOUNT) && 
                        !operationIntent.hasExtra(EXTRA_SERVER_URL)) {
                    Log_OC.e(TAG, "Not enough information provided in intent");
                    
                } else {
                    Account account = operationIntent.getParcelableExtra(EXTRA_ACCOUNT);
                    String serverUrl = operationIntent.getStringExtra(EXTRA_SERVER_URL);
                    String webDavPath = operationIntent.getStringExtra(EXTRA_WEBDAV_PATH);
                    String webDavUrl = serverUrl + webDavPath;
                    String username = operationIntent.getStringExtra(EXTRA_USERNAME);
                    String password = operationIntent.getStringExtra(EXTRA_PASSWORD);
                    String authToken = operationIntent.getStringExtra(EXTRA_AUTH_TOKEN);
                    boolean followRedirects = operationIntent.getBooleanExtra(EXTRA_FOLLOW_REDIRECTS, true);
                    String cookie = operationIntent.getStringExtra(EXTRA_COOKIE);
                    target = new Target(
                            account, 
                            (serverUrl == null) ? null : Uri.parse(serverUrl),
                            ((webDavPath == null) || (serverUrl == null)) ? null : webDavUrl,
                            username,
                            password,
                            authToken,
                            followRedirects,
                            cookie
                    );
                    
                    String action = operationIntent.getAction();
                    if (action.equals(ACTION_CREATE_SHARE)) {  // Create Share
                        String remotePath = operationIntent.getStringExtra(EXTRA_REMOTE_PATH);
                        Intent sendIntent = operationIntent.getParcelableExtra(EXTRA_SEND_INTENT);
                        if (remotePath.length() > 0) {
                            operation = new CreateShareOperation(remotePath, ShareType.PUBLIC_LINK, 
                                    "", false, "", 1, sendIntent);
                        }
                        
                    } else if (action.equals(ACTION_UNSHARE)) {  // Unshare file
                        String remotePath = operationIntent.getStringExtra(EXTRA_REMOTE_PATH);
                        if (remotePath.length() > 0) {
                            operation = new UnshareLinkOperation(
                                    remotePath, 
                                    OperationsService.this);
                        }
                        
                    } else if (action.equals(ACTION_GET_SERVER_INFO)) { 
                        // check OC server and get basic information from it
                        String authTokenType = 
                                operationIntent.getStringExtra(EXTRA_AUTH_TOKEN_TYPE);
                        operation = new GetServerInfoOperation(
                                serverUrl, authTokenType, OperationsService.this);
                        
                    } else if (action.equals(ACTION_OAUTH2_GET_ACCESS_TOKEN)) {
                        /// GET ACCESS TOKEN to the OAuth server
                        String oauth2QueryParameters =
                                operationIntent.getStringExtra(EXTRA_OAUTH2_QUERY_PARAMETERS);
                        operation = new OAuth2GetAccessToken(
                                getString(R.string.oauth2_client_id), 
                                getString(R.string.oauth2_redirect_uri),       
                                getString(R.string.oauth2_grant_type),
                                oauth2QueryParameters);
                        
                    } else if (action.equals(ACTION_EXISTENCE_CHECK)) {
                        // Existence Check 
                        String remotePath = operationIntent.getStringExtra(EXTRA_REMOTE_PATH);
                        boolean successIfAbsent = operationIntent.getBooleanExtra(EXTRA_SUCCESS_IF_ABSENT, true);
                        operation = new ExistenceCheckRemoteOperation(remotePath, OperationsService.this, successIfAbsent);
                        
                    } else if (action.equals(ACTION_GET_USER_NAME)) {
                        // Get User Name
                        operation = new GetRemoteUserNameOperation();
                        
                    } else if (action.equals(ACTION_RENAME)) {
                        // Rename file or folder
                        String remotePath = operationIntent.getStringExtra(EXTRA_REMOTE_PATH);
                        String newName = operationIntent.getStringExtra(EXTRA_NEWNAME);
                        operation = new RenameFileOperation(remotePath, account, newName);
                        
                    } else if (action.equals(ACTION_REMOVE)) {
                        // Remove file or folder
                        String remotePath = operationIntent.getStringExtra(EXTRA_REMOTE_PATH);
                        boolean onlyLocalCopy = operationIntent.getBooleanExtra(EXTRA_REMOVE_ONLY_LOCAL, false);
                        operation = new RemoveFileOperation(remotePath, onlyLocalCopy);
                        
                    } else if (action.equals(ACTION_CREATE_FOLDER)) {
                        // Create Folder
                        String remotePath = operationIntent.getStringExtra(EXTRA_REMOTE_PATH);
                        boolean createFullPath = operationIntent.getBooleanExtra(EXTRA_CREATE_FULL_PATH, true);
                        operation = new CreateFolderOperation(remotePath, createFullPath);
                        
                    } else if (action.equals(ACTION_SYNC_FILE)) {
                        // Sync file
                        String remotePath = operationIntent.getStringExtra(EXTRA_REMOTE_PATH);
                        boolean syncFileContents = operationIntent.getBooleanExtra(EXTRA_SYNC_FILE_CONTENTS, true);
                        operation = new SynchronizeFileOperation(remotePath, account, syncFileContents, getApplicationContext());
                    }
                    
                }
                    
            } catch (IllegalArgumentException e) {
                Log_OC.e(TAG, "Bad information provided in intent: " + e.getMessage());
                operation = null;
            }

            if (operation != null) {
                mPendingOperations.add(new Pair<Target , RemoteOperation>(target, operation));
                startService(new Intent(OperationsService.this, OperationsService.class));
                //Log_OC.wtf(TAG, "New operation added, opId: " + operation.hashCode());
                // better id than hash? ; should be good enough by the time being
                return operation.hashCode();
                
            } else {
                //Log_OC.wtf(TAG, "New operation failed, returned Long.MAX_VALUE");
                return Long.MAX_VALUE;
            }
        }

        public boolean dispatchResultIfFinished(int operationId, OnRemoteOperationListener listener) {
            Pair<RemoteOperation, RemoteOperationResult> undispatched = 
                    mUndispatchedFinishedOperations.remove(operationId);
            if (undispatched != null) {
                listener.onRemoteOperationFinish(undispatched.first, undispatched.second);
                return true;
                //Log_OC.wtf(TAG, "Sending callback later");
            } else {
                if (!mPendingOperations.isEmpty()) {
                    return true;
                } else {
                    return false;
                }
                //Log_OC.wtf(TAG, "Not finished yet");
            }
        }

    }
    
    
    /** 
     * Operations worker. Performs the pending operations in the order they were requested. 
     * 
     * Created with the Looper of a new thread, started in {@link OperationsService#onCreate()}. 
     */
    private static class ServiceHandler extends Handler {
        // don't make it a final class, and don't remove the static ; lint will warn about a possible memory leak
        OperationsService mService;
        public ServiceHandler(Looper looper, OperationsService service) {
            super(looper);
            if (service == null) {
                throw new IllegalArgumentException("Received invalid NULL in parameter 'service'");
            }
            mService = service;
        }

        @Override
        public void handleMessage(Message msg) {
            mService.nextOperation();
            mService.stopSelf(msg.arg1);
        }
    }
    

    /**
     * Performs the next operation in the queue
     */
    private void nextOperation() {
        
        //Log_OC.wtf(TAG, "nextOperation init" );
        
        Pair<Target, RemoteOperation> next = null;
        synchronized(mPendingOperations) {
            next = mPendingOperations.peek();
        }

        if (next != null) {
            
            mCurrentOperation = next.second;
            RemoteOperationResult result = null;
            try {
                /// prepare client object to send the request to the ownCloud server
                if (mLastTarget == null || !mLastTarget.equals(next.first)) {
                    mLastTarget = next.first;
                    if (mLastTarget.mAccount != null) {
                        mOwnCloudClient = OwnCloudClientFactory.createOwnCloudClient(mLastTarget.mAccount, getApplicationContext());
                        mStorageManager = new FileDataStorageManager(mLastTarget.mAccount, getContentResolver());
                    } else {
                        mOwnCloudClient = OwnCloudClientFactory.createOwnCloudClient(mLastTarget.mServerUrl, getApplicationContext(), 
                                mLastTarget.mFollowRedirects);    // this is not good enough
                        if (mLastTarget.mWebDavUrl != null) {
                            mOwnCloudClient.setWebdavUri(Uri.parse(mLastTarget.mWebDavUrl));
                        }
                        if (mLastTarget.mUsername != null && mLastTarget.mPassword != null) {
                            mOwnCloudClient.setBasicCredentials(mLastTarget.mUsername, mLastTarget.mPassword);
                        } else if (mLastTarget.mAuthToken != null) {
                            mOwnCloudClient.setBearerCredentials(mLastTarget.mAuthToken);
                        } else if (mLastTarget.mCookie != null) {
                            mOwnCloudClient.setSsoSessionCookie(mLastTarget.mCookie);
                        }
                        mStorageManager = null;
                    }
                }

                /// perform the operation
                if (mCurrentOperation instanceof SyncOperation) {
                    result = ((SyncOperation)mCurrentOperation).execute(mOwnCloudClient, mStorageManager);
                } else {
                    result = mCurrentOperation.execute(mOwnCloudClient);
                }
                
            } catch (AccountsException e) {
                if (mLastTarget.mAccount == null) {
                    Log_OC.e(TAG, "Error while trying to get authorization for a NULL account", e);
                } else {
                    Log_OC.e(TAG, "Error while trying to get authorization for " + mLastTarget.mAccount.name, e);
                }
                result = new RemoteOperationResult(e);
                
            } catch (IOException e) {
                if (mLastTarget.mAccount == null) {
                    Log_OC.e(TAG, "Error while trying to get authorization for a NULL account", e);
                } else {
                    Log_OC.e(TAG, "Error while trying to get authorization for " + mLastTarget.mAccount.name, e);
                }
                result = new RemoteOperationResult(e);
            } catch (Exception e) {
                if (mLastTarget.mAccount == null) {
                    Log_OC.e(TAG, "Unexpected error for a NULL account", e);
                } else {
                    Log_OC.e(TAG, "Unexpected error for " + mLastTarget.mAccount.name, e);
                }
                result = new RemoteOperationResult(e);
            
            } finally {
                synchronized(mPendingOperations) {
                    mPendingOperations.poll();
                }
            }
            
            //sendBroadcastOperationFinished(mLastTarget, mCurrentOperation, result);
            dispatchResultToOperationListeners(mLastTarget, mCurrentOperation, result);
        }
    }


    /**
     * Sends a broadcast when a new operation is added to the queue.
     * 
     * Local broadcasts are only delivered to activities in the same process, but can't be done sticky :\
     * 
     * @param target            Account or URL pointing to an OC server.
     * @param operation         Added operation.
     */
    private void sendBroadcastNewOperation(Target target, RemoteOperation operation) {
        Intent intent = new Intent(ACTION_OPERATION_ADDED);
        if (target.mAccount != null) {
            intent.putExtra(EXTRA_ACCOUNT, target.mAccount);    
        } else {
            intent.putExtra(EXTRA_SERVER_URL, target.mServerUrl);    
        }
        //LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        //lbm.sendBroadcast(intent);
        sendStickyBroadcast(intent);
    }

    
    // TODO - maybe add a notification for real start of operations
    
    /**
     * Sends a LOCAL broadcast when an operations finishes in order to the interested activities can update their view
     * 
     * Local broadcasts are only delivered to activities in the same process.
     * 
     * @param target            Account or URL pointing to an OC server.
     * @param operation         Finished operation.
     * @param result            Result of the operation.
     */
    private void sendBroadcastOperationFinished(Target target, RemoteOperation operation, RemoteOperationResult result) {
        Intent intent = new Intent(ACTION_OPERATION_FINISHED);
        intent.putExtra(EXTRA_RESULT, result);
        if (target.mAccount != null) {
            intent.putExtra(EXTRA_ACCOUNT, target.mAccount);    
        } else {
            intent.putExtra(EXTRA_SERVER_URL, target.mServerUrl);    
        }
        //LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        //lbm.sendBroadcast(intent);
        sendStickyBroadcast(intent);
    }

    
    /**
     * Notifies the currently subscribed listeners about the end of an operation.
     * 
     * @param target            Account or URL pointing to an OC server.
     * @param operation         Finished operation.
     * @param result            Result of the operation.
     */
    private void dispatchResultToOperationListeners(
            Target target, final RemoteOperation operation, final RemoteOperationResult result) {
        int count = 0;
        Iterator<OnRemoteOperationListener> listeners = mBinder.mBoundListeners.keySet().iterator();
        while (listeners.hasNext()) {
            final OnRemoteOperationListener listener = listeners.next();
            final Handler handler = mBinder.mBoundListeners.get(listener);
            if (handler != null) { 
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onRemoteOperationFinish(operation, result);
                    }
                });
                count += 1;
            }
        }
        if (count == 0) {
            //mOperationResults.put(operation.hashCode(), result);
            Pair<RemoteOperation, RemoteOperationResult> undispatched = 
                    new Pair<RemoteOperation, RemoteOperationResult>(operation, result);
            mUndispatchedFinishedOperations.put(operation.hashCode(), undispatched);
        }
        Log_OC.d(TAG, "Called " + count + " listeners");
    }
    

}
