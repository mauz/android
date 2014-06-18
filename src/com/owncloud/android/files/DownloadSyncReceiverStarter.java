package com.owncloud.android.files;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.owncloud.android.services.DownloadSyncService;

public class DownloadSyncReceiverStarter extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("DownloadSyncReceiverStarter", "###### DownloadSyncReceiverStarter event received");
        Intent service = new Intent(context, DownloadSyncService.class);
        context.startService(service);
    }

}
