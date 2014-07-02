package com.owncloud.android.services;

import java.io.File;
import java.util.Vector;

import android.accounts.Account;
import android.annotation.TargetApi;
import android.app.Service;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.StrictMode;
import android.util.Log;

import com.owncloud.android.authentication.AccountUtils;
import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.files.services.FileDownloader;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.operations.SynchronizeFolderOperation;

public class DownloadSyncService extends Service {

    // need refactor
    private static final String FIRST_LEVEL_SYNC_DIR = "/Shared";
    private static final String SECOND_LEVEL_SYNC_DIR = "/Shared/MobileSync/";
    
    private IBinder mBinder = new DownloadSyncServiceBinder();

    private FileDataStorageManager mFileDataStorageManager = null;
    
    private Account mAccount = null;
    
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("DownloadSyncService", "###### Received start id " + startId + ": " + intent);
        mAccount = AccountUtils.getCurrentOwnCloudAccount(getApplicationContext());
        if(mAccount != null) {
            //togglePolicy();
            syncLocalTree();
        }
        return Service.START_NOT_STICKY;
    }
    
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private void togglePolicy(){
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitNetwork().build();
        StrictMode.setThreadPolicy(policy);

    }

    public void onCreate() {
        Log.i("DownloadSyncService", "###### onCreate @@@@@@@@@");
    }

    public class DownloadSyncServiceBinder extends Binder {
        DownloadSyncService getService() {
            return DownloadSyncService.this;
        }
    }
    
    private void syncLocalTree() {
        Log.i("DownloadSyncService", "###### Starting default sync");


        Account account = AccountUtils.getCurrentOwnCloudAccount(getApplicationContext());
        
        mFileDataStorageManager = new FileDataStorageManager(account, getApplicationContext().getContentResolver());
        //mFileDataStorageManager = new FileDataStorageManager(account, getApplicationContext().getContentResolver());


        // init
        OCFile firstLevelsyncDir = mFileDataStorageManager.getFileByPath(FIRST_LEVEL_SYNC_DIR);
        OCFile secondLevelsyncDir = mFileDataStorageManager.getFileByPath(SECOND_LEVEL_SYNC_DIR);
        if(secondLevelsyncDir != null) { 
            Log.i("DownloadSyncService", "###### got '/Shared/'");
            //browseTo(sharedDir);
        }
        if(firstLevelsyncDir != null) {
            Log.i("DownloadSyncService", "###### got '" + SECOND_LEVEL_SYNC_DIR + "'");
            //browseTo(syncDir);
        }


        Log.i("DownloadSyncService", "###### alive");

        Vector<OCFile> firstLevelSyncFiles = mFileDataStorageManager.getFolderContent(firstLevelsyncDir);
        Vector<OCFile> secondLevelSyncFiles = mFileDataStorageManager.getFolderContent(secondLevelsyncDir);

        Log.i("DownloadSyncService", "###### Found '" + secondLevelSyncFiles.size() + "' files in folder '" + SECOND_LEVEL_SYNC_DIR + "'");


        if(secondLevelSyncFiles.size() == 0) {
            initDefaultSync();
        }

        // awaked?
        secondLevelsyncDir = mFileDataStorageManager.getFileByPath(SECOND_LEVEL_SYNC_DIR);

        for (OCFile ocFile : secondLevelSyncFiles) {
            Log.i("DownloadSyncService", "###### Processing '" + SECOND_LEVEL_SYNC_DIR + ocFile.getFileName() + "'");

            //OCFile localFile = getStorageManager().getFileById(ocFile.getFileId());
            //OCFile localFile = new OCFile(SYNC_DIR + ocFile.getFileName());
            //Log.i("FileSyncService", "###### '" + SYNC_DIR + ocFile.getFileName() + "' locally instantiated");

            File localFile = null;

            //File localFile = getBaseContext().getFileStreamPath(ocFile.getStoragePath());
            if(ocFile.getStoragePath() != null && !ocFile.getStoragePath().isEmpty()) {
                localFile = new File(ocFile.getStoragePath());
            }

            Log.i("DownloadSyncService", "###### local filename getStoragePath is '" + ocFile.getStoragePath() + "'");
            Log.i("DownloadSyncService", "###### getLocalModificationTimestamp is '" + ocFile.getLocalModificationTimestamp() + "' getModificationTimestamp() is '" + ocFile.getModificationTimestamp() +"'");
            Log.i("DownloadSyncService", "###### getLastSyncDateForData is '" + ocFile.getLastSyncDateForData() + "' getCreationTimestamp is '" + ocFile.getCreationTimestamp() +"'");

            /*
             * timestampUpdateRequired true if:
             * 1) local file has modification time after last sync time so must be locally overwritten
             * 2) server creation time is newer than local file modification time
             * 
             * Note : if server modification time is in future,
             * we need avoid indefinitely download that file.
             */
            boolean localModificationSyncRequired /*AfterLastSync*/ = ocFile.getLocalModificationTimestamp() > ocFile.getLastSyncDateForData();
            Log.i("DownloadSyncService", "###### localModificationSyncRequired is '" + localModificationSyncRequired + "'");

            boolean remoteModificationIsNewer = ocFile.getModificationTimestamp() > ocFile.getLocalModificationTimestamp();
            Log.i("DownloadSyncService", "###### remoteModificationIsNewer is '" + remoteModificationIsNewer + "'");
            boolean remoteModificationTimestampIsInFuture = ocFile.getModificationTimestamp() > System.currentTimeMillis();
            Log.i("DownloadSyncService", "###### remoteModificationTimestampIsInFuture is '" + remoteModificationTimestampIsInFuture + "'");
            boolean remoteModificationSyncRequired = remoteModificationIsNewer && !remoteModificationTimestampIsInFuture;
            Log.i("DownloadSyncService", "###### remoteModificationSyncRequired is '" + remoteModificationSyncRequired + "'");

            boolean timestampUpdateRequired = localModificationSyncRequired || remoteModificationSyncRequired;
            Log.i("DownloadSyncService", "###### timestampUpdateRequired is '" + timestampUpdateRequired + "'");


            if(localFile != null && localFile.exists() && !timestampUpdateRequired) {
                //if(localFile==null || (localFile.getCreationTimestamp() != ocFile.getCreationTimestamp() ) ) {
                //if (!mDownloaderBinder.isDownloading(account, ocFile)) {
                Log.i("DownloadSyncService", "###### '" + ocFile.getFileName() + "' already in sync");
                //}
            } else {
                Log.i("DownloadSyncService", "###### '" + ocFile.getFileName() + "' does not locally exist, going to sync...");
                Intent i = new Intent(this, FileDownloader.class);
                i.putExtra(FileDownloader.EXTRA_ACCOUNT, account);
                i.putExtra(FileDownloader.EXTRA_FILE, ocFile);
                startService(i);
            }
        }

    }

    
    private void initDefaultSync() {
//        OCFile secondLevelsyncDir = mFileDataStorageManager.getFileByPath(SECOND_LEVEL_SYNC_DIR);
//        Vector<OCFile> secondLevelSyncFiles = mFileDataStorageManager.getFolderContent(secondLevelsyncDir);
//        if(secondLevelSyncFiles.size() == 0) {
            OCFile firstLevelsyncDir = new OCFile(FIRST_LEVEL_SYNC_DIR);
            firstLevelsyncDir.setMimetype("DIR");
            firstLevelsyncDir.setParentId(FileDataStorageManager.ROOT_PARENT_ID);
            startSyncFolderOperation(firstLevelsyncDir);
            //}
            // mauz added, is this really needed ?
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
            }
            
            OCFile secondLevelsyncDir = new OCFile(SECOND_LEVEL_SYNC_DIR);
            secondLevelsyncDir.setMimetype("DIR");
            secondLevelsyncDir.setParentId(firstLevelsyncDir.getFileId());
            startSyncFolderOperation(secondLevelsyncDir);
//        }
    }
    
    public void startSyncFolderOperation(OCFile folder) {
        new DownloadSyncTask(folder).execute();
        
    }
    
    private class DownloadSyncTask extends AsyncTask<Void, Void, Void> {

        private OCFile mFolder = null;
        
        public DownloadSyncTask(OCFile folder) {
            mFolder = folder;
        }
        @Override
        protected Void doInBackground(Void... params) {
            startSyncFolderOperation(mFolder);
            return null;
        }
        public void startSyncFolderOperation(OCFile folder) {
            long currentSyncTime = System.currentTimeMillis(); 

            // perform folder synchronization
            RemoteOperation synchFolderOp = new SynchronizeFolderOperation( folder,  
                    currentSyncTime,
                    false,
                    true,
                    mFileDataStorageManager, 
                    mAccount, 
                    getApplicationContext()
                    );
            synchFolderOp.execute(mAccount, getApplicationContext());

        }

    }
    
}
