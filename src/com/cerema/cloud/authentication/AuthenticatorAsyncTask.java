/**
 *   ownCloud Android client application
 *
 *   @author masensio on 09/02/2015.
 *   Copyright (C) 2015 ownCloud Inc.
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
package com.cerema.cloud.authentication;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;

import com.cerema.cloud.lib.common.OwnCloudClient;
import com.cerema.cloud.lib.common.OwnCloudClientFactory;
import com.cerema.cloud.lib.common.OwnCloudCredentials;
import com.cerema.cloud.lib.common.network.RedirectionPath;
import com.cerema.cloud.lib.common.operations.RemoteOperationResult;
import com.cerema.cloud.lib.resources.files.ExistenceCheckRemoteOperation;

import java.lang.ref.WeakReference;


/**
 * Async Task to verify the credentials of a user
 */
public class AuthenticatorAsyncTask  extends AsyncTask<Object, Void, RemoteOperationResult> {

    private static String REMOTE_PATH = "/";
    private static boolean SUCCESS_IF_ABSENT = false;

    private Context mContext;
    private final WeakReference<OnAuthenticatorTaskListener> mListener;
    protected Activity mActivity;

    public AuthenticatorAsyncTask(Activity activity) {
        mContext = activity.getApplicationContext();
        mListener = new WeakReference<OnAuthenticatorTaskListener>((OnAuthenticatorTaskListener)activity);
    }

    @Override
    protected RemoteOperationResult doInBackground(Object... params) {

        RemoteOperationResult result;
        if (params!= null && params.length==2) {
            String url = (String)params[0];
            OwnCloudCredentials credentials = (OwnCloudCredentials)params[1];

            // Client
            Uri uri = Uri.parse(url);
            OwnCloudClient client = OwnCloudClientFactory.createOwnCloudClient(uri, mContext, true);
            client.setCredentials(credentials);

            // Operation
            ExistenceCheckRemoteOperation operation = new ExistenceCheckRemoteOperation(
                    REMOTE_PATH,
                    mContext,
                    SUCCESS_IF_ABSENT
            );
            result = operation.execute(client);

            if (operation.wasRedirected()) {
                RedirectionPath redirectionPath = operation.getRedirectionPath();
                String permanentLocation = redirectionPath.getLastPermanentLocation();
                result.setLastPermanentLocation(permanentLocation);
            }

        } else {
            result = new RemoteOperationResult(RemoteOperationResult.ResultCode.UNKNOWN_ERROR);
        }

        return result;
    }

    @Override
    protected void onPostExecute(RemoteOperationResult result) {

        if (result!= null)
        {
            OnAuthenticatorTaskListener listener = mListener.get();
            if (listener!= null)
            {
                listener.onAuthenticatorTaskCallback(result);
            }
        }
    }
    /*
     * Interface to retrieve data from recognition task
     */
    public interface OnAuthenticatorTaskListener{

        void onAuthenticatorTaskCallback(RemoteOperationResult result);
    }
}
