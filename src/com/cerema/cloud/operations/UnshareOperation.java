/**
 *   ownCloud Android client application
 *
 *   @author masensio
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

package com.cerema.cloud.operations;

import android.content.Context;

import com.cerema.cloud.datamodel.OCFile;

import com.cerema.cloud.lib.common.OwnCloudClient;
import com.cerema.cloud.lib.common.operations.RemoteOperationResult;
import com.cerema.cloud.lib.common.operations.RemoteOperationResult.ResultCode;
import com.cerema.cloud.lib.common.utils.Log_OC;
import com.cerema.cloud.lib.resources.files.ExistenceCheckRemoteOperation;
import com.cerema.cloud.lib.resources.shares.OCShare;
import com.cerema.cloud.lib.resources.shares.RemoveRemoteShareOperation;
import com.cerema.cloud.lib.resources.shares.ShareType;

import com.cerema.cloud.operations.common.SyncOperation;

import java.util.ArrayList;

/**
 * Unshare file/folder
 * Save the data in Database
 */
public class UnshareOperation extends SyncOperation {

    private static final String TAG = UnshareOperation.class.getSimpleName();
    
    private String mRemotePath;
    private ShareType mShareType;
    private String mShareWith;
    private Context mContext;
    
    public UnshareOperation(String remotePath, ShareType shareType, String shareWith,
                                Context context) {
        mRemotePath = remotePath;
        mShareType = shareType;
        mShareWith = shareWith;
        mContext = context;
    }

    @Override
    protected RemoteOperationResult run(OwnCloudClient client) {
        RemoteOperationResult result  = null;
        
        // Get Share for a file
        OCShare share = getStorageManager().getFirstShareByPathAndType(mRemotePath,
                mShareType, mShareWith);
        
        if (share != null) {
            OCFile file = getStorageManager().getFileByPath(mRemotePath);
            RemoveRemoteShareOperation operation =
                    new RemoveRemoteShareOperation((int) share.getRemoteId());
            result = operation.execute(client);

            if (result.isSuccess()) {
                Log_OC.d(TAG, "Share id = " + share.getRemoteId() + " deleted");

                if (ShareType.PUBLIC_LINK.equals(mShareType)) {
                    file.setShareViaLink(false);
                    file.setPublicLink("");
                } else if (ShareType.USER.equals(mShareType) || ShareType.GROUP.equals(mShareType)
                    || ShareType.FEDERATED.equals(mShareType)){
                    // Check if it is the last share
                    ArrayList <OCShare> sharesWith = getStorageManager().
                            getSharesWithForAFile(mRemotePath,
                            getStorageManager().getAccount().name);
                    if (sharesWith.size() == 1) {
                        file.setShareWithSharee(false);
                    }
                }

                getStorageManager().saveFile(file);
                getStorageManager().removeShare(share);
                
            } else if (!existsFile(client, file.getRemotePath())) {
                // unshare failed because file was deleted before
                getStorageManager().removeFile(file, true, true);
            }

        } else {
            result = new RemoteOperationResult(ResultCode.SHARE_NOT_FOUND);
        }

        return result;
    }
    
    private boolean existsFile(OwnCloudClient client, String remotePath){
        ExistenceCheckRemoteOperation existsOperation =
                new ExistenceCheckRemoteOperation(remotePath, mContext, false);
        RemoteOperationResult result = existsOperation.execute(client);
        return result.isSuccess();
    }

}
