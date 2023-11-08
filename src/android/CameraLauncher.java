/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/
package org.apache.cordova.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import android.util.Base64;

import org.apache.cordova.BuildHelper;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

/**
 * This class launches the camera view, allows the user to take a picture, closes the camera view,
 * and returns the captured image.  When the camera view is closed, the screen displayed before
 * the camera view was shown is redisplayed.
 */
public class CameraLauncher extends CordovaPlugin {

    private static final int DATA_URL = 0;              // Return base64 encoded string
    private static final int FILE_URI = 1;              // Return file uri (content://media/external/images/media/2 for Android)

    private static final int PHOTOLIBRARY = 0;          // Choose image from picture library (same as SAVEDPHOTOALBUM for Android)
    private static final int CAMERA = 1;                // Take picture from camera
    private static final int SAVEDPHOTOALBUM = 2;       // Choose image from picture library (same as PHOTOLIBRARY for Android)

    private static final int PICTURE = 0;               // allow selection of still pictures only. DEFAULT. Will return format specified via DestinationType
    private static final int VIDEO = 1;                 // allow selection of video only, ONLY RETURNS URL
    private static final int ALLMEDIA = 2;              // allow selection from all media types

    private static final int JPEG = 0;                  // Take a picture of type JPEG
    private static final int PNG = 1;                   // Take a picture of type PNG
    private static final String JPEG_TYPE = "jpg";
    private static final String PNG_TYPE = "png";
    private static final String JPEG_EXTENSION = "." + JPEG_TYPE;
    private static final String PNG_EXTENSION = "." + PNG_TYPE;
    private static final String PNG_MIME_TYPE = "image/png";
    private static final String JPEG_MIME_TYPE = "image/jpeg";
    private static final String HEIC_MIME_TYPE = "image/heic";
    private static final String GET_PICTURE = "Get Picture";
    private static final String GET_VIDEO = "Get Video";
    private static final String GET_All = "Get All";
    private static final String CROPPED_URI_KEY = "croppedUri";
    private static final String IMAGE_URI_KEY = "imageUri";
    private static final String IMAGE_FILE_PATH_KEY = "imageFilePath";

    private static final String TAKE_PICTURE_ACTION = "takePicture";

    public static final int PERMISSION_DENIED_ERROR = 20;
    public static final int TAKE_PIC_SEC = 0;

    private static final String LOG_TAG = "CameraLauncher";

    //Where did this come from?
    private static final int CROP_CAMERA = 100;

    private static final String TIME_FORMAT = "yyyyMMdd_HHmmss";

    private int mQuality;                   // Compression quality hint (0-100: 0=low quality & high compression, 100=compress of max quality)
    private int targetWidth;                // desired width of the image
    private int targetHeight;               // desired height of the image
    private Uri imageUri;                   // Uri of captured image
    private String imageFilePath;           // File where the image is stored
    private int encodingType;               // Type of encoding to use
    private int mediaType;                  // What type of media to retrieve
    private int destType;                   // Source type (needs to be saved for the permission handling)
    private int srcType;                    // Destination type (needs to be saved for permission handling)
    private boolean correctOrientation;     // Should the pictures orientation be corrected
    private boolean orientationCorrected;   // Has the picture's orientation been corrected
    private boolean allowEdit;              // Should we allow the user to crop the image.

    public CallbackContext callbackContext;
    private int numPics;

    private MediaScannerConnection conn;    // Used to update gallery app with newly-written files
    private Uri scanMe;                     // Uri of image to be added to content store
    private Uri croppedUri;
    private String croppedFilePath;
    private ExifHelper exifData;            // Exif data from source
    private String applicationId;


    /**
     * Executes the request and returns PluginResult.
     *
     * @param action            The action to execute.
     * @param args              JSONArry of arguments for the plugin.
     * @param callbackContext   The callback id used when calling back into JavaScript.
     * @return                  A PluginResult object with a status and message.
     */
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;

        this.applicationId = cordova.getContext().getPackageName();
        this.applicationId = preferences.getString("applicationId", this.applicationId);

        if (action.equals(TAKE_PICTURE_ACTION)) {
            this.srcType = CAMERA;
            this.destType = FILE_URI;
            this.targetHeight = 0;
            this.targetWidth = 0;
            this.encodingType = JPEG;
            this.mediaType = PICTURE;
            this.mQuality = 50;

            //Take the values from the arguments if they're not already defined (this is tricky)
            this.destType = args.getInt(1);
            this.srcType = args.getInt(2);
            this.mQuality = args.getInt(0);
            this.targetWidth = args.getInt(3);
            this.targetHeight = args.getInt(4);
            this.encodingType = args.getInt(5);
            this.mediaType = args.getInt(6);
            this.correctOrientation = args.getBoolean(8);

            // If the user specifies a 0 or smaller width/height
            // make it -1 so later comparisons succeed
            if (this.targetWidth < 1) {
                this.targetWidth = -1;
            }
            if (this.targetHeight < 1) {
                this.targetHeight = -1;
            }

            // We don't return full-quality PNG files. The camera outputs a JPEG
            // so requesting it as a PNG provides no actual benefit
            if (this.targetHeight == -1 && this.targetWidth == -1 && this.mQuality == 100 &&
                    !this.correctOrientation && this.encodingType == PNG && this.srcType == CAMERA) {
                this.encodingType = JPEG;
            }

            try {
                if (this.srcType == CAMERA) {
                    this.callTakePicture(destType, encodingType);
                }
            }
            catch (IllegalArgumentException e)
            {
                callbackContext.error("Illegal Argument Exception");
                PluginResult r = new PluginResult(PluginResult.Status.ERROR);
                callbackContext.sendPluginResult(r);
                return true;
            }

            PluginResult r = new PluginResult(PluginResult.Status.NO_RESULT);
            r.setKeepCallback(true);
            callbackContext.sendPluginResult(r);

            return true;
        }
        return false;
    }

    /**
     * Take a picture with the camera.
     * When an image is captured or the camera view is cancelled, the result is returned
     * in CordovaActivity.onActivityResult, which forwards the result to this.onActivityResult.
     *
     * The image can either be returned as a base64 string or a URI that points to the file.
     * To display base64 string in an img tag, set the source to:
     *      img.src="data:image/jpeg;base64,"+result;
     * or to display URI in an img tag
     *      img.src=result;
     *
     * @param returnType        Set the type of image to return.
     * @param encodingType           Compression quality hint (0-100: 0=low quality & high compression, 100=compress of max quality)
     */
    public void callTakePicture(int returnType, int encodingType) {
        boolean takePicturePermission = PermissionHelper.hasPermission(this, Manifest.permission.CAMERA);

        // CB-10120: The CAMERA permission does not need to be requested unless it is declared
        // in AndroidManifest.xml. This plugin does not declare it, but others may and so we must
        // check the package info to determine if the permission is present.

        if (!takePicturePermission) {
            takePicturePermission = true;
            try {
                PackageManager packageManager = this.cordova.getActivity().getPackageManager();
                String[] permissionsInPackage = packageManager.getPackageInfo(this.cordova.getActivity().getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
                if (permissionsInPackage != null) {
                    for (String permission : permissionsInPackage) {
                        if (permission.equals(Manifest.permission.CAMERA)) {
                            takePicturePermission = false;
                            break;
                        }
                    }
                }
            } catch (NameNotFoundException e) {
                // We are requesting the info for our package, so this should
                // never be caught
            }
        }

        if (takePicturePermission) {
            takePicture(returnType, encodingType);
        } else {
            PermissionHelper.requestPermission(this, TAKE_PIC_SEC, Manifest.permission.CAMERA);
        } 
    }

    public void takePicture(int returnType, int encodingType)
    {
        // Let's use the intent and see what happens
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        if (this.cordova != null) {
            // Let's check to make sure the camera is actually installed. (Legacy Nexus 7 code)
            PackageManager mPm = this.cordova.getActivity().getPackageManager();
            if(intent.resolveActivity(mPm) != null)
            {
                this.cordova.startActivityForResult((CordovaPlugin) this, intent, (CAMERA + 1) * 16 + returnType + 1);
            }
            else
            {
                LOG.d(LOG_TAG, "Error: You don't have a default camera.  Your device may not be CTS complaint.");
            }
        }
//        else
//            LOG.d(LOG_TAG, "ERROR: You must use the CordovaInterface for this to work correctly. Please implement it in your activity");
    }

    /**
     * Applies all needed transformation to the image received from the camera.
     *
     * @param destType          In which form should we return the image
     * @param intent            An Intent, which can return result data to the caller (various data can be attached to Intent "extras").
     */
    private void processResultFromCamera(int destType, Intent intent) throws IOException {
        int rotate = 0;

        Bitmap bitmap = null;
        Uri galleryUri = null;

        // If sending base64 image back
        if (destType == DATA_URL) {
            if (bitmap == null) {
                // Try to get the bitmap from intent.
                bitmap = (Bitmap) intent.getExtras().get("data");
            }

            // Double-check the bitmap.
            if (bitmap == null) {
                LOG.d(LOG_TAG, "I either have a null image path or bitmap");
                this.failPicture("Unable to create bitmap!");
                return;
            }

            this.processPicture(bitmap, this.encodingType);
        }
         else {
            throw new IllegalStateException();
        }

        this.cleanup(FILE_URI, this.imageUri, galleryUri, bitmap);
        bitmap = null;
    }

    private CompressFormat getCompressFormatForEncodingType(int encodingType) {
        return encodingType == JPEG ? CompressFormat.JPEG : CompressFormat.PNG;
    }

    /**
     * Called when the camera view exits.
     *
     * @param requestCode The request code originally supplied to startActivityForResult(),
     *                    allowing you to identify who this result came from.
     * @param resultCode  The integer result code returned by the child activity through its setResult().
     * @param intent      An Intent, which can return result data to the caller (various data can be attached to Intent "extras").
     */
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {

        // Get src and dest types from request code for a Camera Activity
        int srcType = (requestCode / 16) - 1;
        int destType = (requestCode % 16) - 1;

        if (srcType == CAMERA) {
            // If image available
            if (resultCode == Activity.RESULT_OK) {
                try {
                    this.processResultFromCamera(destType, intent);
                } catch (IOException e) {
                    e.printStackTrace();
                    this.failPicture("Error capturing image: "+e.getLocalizedMessage());
                }
            }

            // If cancelled
            else if (resultCode == Activity.RESULT_CANCELED) {
                this.failPicture("No Image Selected");
            }

            // If something else
            else {
                this.failPicture("Did not complete!");
            }
        }
    }

    /**
     * Cleans up after picture taking. Checking for duplicates and that kind of stuff.
     *
     * @param newImage
     */
    private void cleanup(int imageType, Uri oldImage, Uri newImage, Bitmap bitmap) {
        if (bitmap != null) {
            bitmap.recycle();
        }

        System.gc();
    }

    /**
     * Compress bitmap using jpeg, convert to Base64 encoded string, and return to JavaScript.
     *
     * @param bitmap
     */
    public void processPicture(Bitmap bitmap, int encodingType) {
        ByteArrayOutputStream jpeg_data = new ByteArrayOutputStream();
        CompressFormat compressFormat = getCompressFormatForEncodingType(encodingType);

        try {
            if (bitmap.compress(compressFormat, mQuality, jpeg_data)) {
                byte[] code = jpeg_data.toByteArray();
                byte[] output = Base64.encode(code, Base64.NO_WRAP);
                String js_out = new String(output);
                this.callbackContext.success(js_out);
                js_out = null;
                output = null;
                code = null;
            }
        } catch (Exception e) {
            this.failPicture("Error compressing image: "+e.getLocalizedMessage());
        }
        jpeg_data = null;
    }

    /**
     * Send error message to JavaScript.
     *
     * @param err
     */
    public void failPicture(String err) {
        this.callbackContext.error(err);
    }

    /**
     * Taking or choosing a picture launches another Activity, so we need to implement the
     * save/restore APIs to handle the case where the CordovaActivity is killed by the OS
     * before we get the launched Activity's result.
     */
    public Bundle onSaveInstanceState() {
        Bundle state = new Bundle();
        state.putInt("destType", this.destType);
        state.putInt("srcType", this.srcType);
        state.putInt("mQuality", this.mQuality);
        state.putInt("targetWidth", this.targetWidth);
        state.putInt("targetHeight", this.targetHeight);
        state.putInt("encodingType", this.encodingType);
        state.putInt("mediaType", this.mediaType);
        state.putBoolean("correctOrientation", this.correctOrientation);

        if (this.croppedUri != null) {
            state.putString(CROPPED_URI_KEY, this.croppedFilePath);
        }

        if (this.imageUri != null) {
            state.putString(IMAGE_URI_KEY, this.imageFilePath);
        }

        if (this.imageFilePath != null) {
            state.putString(IMAGE_FILE_PATH_KEY, this.imageFilePath);
        }

        return state;
    }

    public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {
        this.destType = state.getInt("destType");
        this.srcType = state.getInt("srcType");
        this.mQuality = state.getInt("mQuality");
        this.targetWidth = state.getInt("targetWidth");
        this.targetHeight = state.getInt("targetHeight");
        this.encodingType = state.getInt("encodingType");
        this.mediaType = state.getInt("mediaType");
        this.correctOrientation = state.getBoolean("correctOrientation");

        if (state.containsKey(CROPPED_URI_KEY)) {
            this.croppedUri = Uri.parse(state.getString(CROPPED_URI_KEY));
        }

        if (state.containsKey(IMAGE_URI_KEY)) {
            //I have no idea what type of URI is being passed in
            this.imageUri = Uri.parse(state.getString(IMAGE_URI_KEY));
        }

        if (state.containsKey(IMAGE_FILE_PATH_KEY)) {
            this.imageFilePath = state.getString(IMAGE_FILE_PATH_KEY);
        }

        this.callbackContext = callbackContext;
    }

    private boolean hasPermissions(String[] permissions) {
        for (String permission: permissions) {
            if (!PermissionHelper.hasPermission(this, permission)) {
                return false;
            }
        }
        return true;
    }
}
