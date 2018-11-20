package com.jiangdg.usbcamera.connect;

import android.content.Context;
import android.os.AsyncTask;

import java.io.File;
import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class UploadImage {
    public void doUpdateProfiles(final Context context, final String filePath, final String url, final String token, final boolean isShowDialog){

        class UploadPic extends AsyncTask<String, Integer, String> {
            @Override
            protected String doInBackground(String... strings) {
                OkHttpClient ok = new OkHttpClient();
                File sourceFileGallery = new File(filePath);

                Request.Builder builder = new Request.Builder();
                RequestBody body = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("image", sourceFileGallery.getName(),
                                RequestBody.create(MediaType.parse("image/png"), sourceFileGallery))
                        .build();
                Request request = builder
                        .url(url)
                        .post(body)
                        .addHeader("content-type", "multipart/form-data")
                        .addHeader("Authorization", token)
                        .addHeader("Accept", "application/json")
                        .build();

                okhttp3.Response response;
                try {
                    response = ok.newCall(request).execute();
                    if ((response.isSuccessful())) {
                        return response.body().string();
                    } else {
                        return null;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    return e.getCause().toString();
                } }

            @Override
            protected void onPreExecute() {
                super.onPreExecute();
//                if (isShowDialog)
//                    Utils.showProgressDialog(context, "กำลังอัปโหลดรูปภาพ...");
            }

            @Override
            protected void onPostExecute(final String s) {
                super.onPostExecute(s);

            }

            @Override
            protected void onProgressUpdate(Integer... progress) {
            }
        }
        new UploadPic().execute();
    }
}
