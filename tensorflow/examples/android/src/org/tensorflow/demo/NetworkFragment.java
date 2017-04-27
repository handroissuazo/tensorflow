package org.tensorflow.demo;

import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * Created by ovidio on 4/10/17.
 */

public class NetworkFragment extends Fragment {
    public static final String TAG = "NetworkFragment";
    private static final String URL_KEY = "UrlKey";

    private DownloadCallback mCallback;
    private DownloadTask mDownloadTask;
    private String mUrlString;

    /*
     * Static initializer for NetworkFragment that sets the URL of the host it will be downloading
     * from.
     */
    public static NetworkFragment getInstance(FragmentManager fragmentManager, String urls) {
        NetworkFragment networkFragment = (NetworkFragment) fragmentManager
                .findFragmentByTag(NetworkFragment.TAG);
        if (networkFragment == null) {
            networkFragment = new NetworkFragment();
            Bundle args = new Bundle();
            args.putString(URL_KEY, urls);
            networkFragment.setArguments(args);
            fragmentManager.beginTransaction().add(networkFragment, TAG).commit();
        }
        return networkFragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUrlString = getArguments().getString(URL_KEY);

        // Retain this Fragment across configuration changes in the host Activity.
        setRetainInstance(true);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        // Host Activity will handle callbacks from task.
        mCallback = (DownloadCallback) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        // Clear reference to host Activity to avoid memory leak.
        mCallback = null;
    }

    @Override
    public void onDestroy() {
        // Cancel task when Fragment is destroyed.
        cancelDownload();
        super.onDestroy();
    }

    /**
     * Start non-blocking execution of DownloadTask.
     */
    public void startDownload() {
        cancelDownload();
        mDownloadTask = new DownloadTask(mCallback);

        // Grab epoch times from model files
        String northModel = getModelFileName("us_north", ".pb");
        String southModel = getModelFileName("us_south", ".pb");
        String eastModel = getModelFileName("us_east", ".pb");
        String westModel = getModelFileName("us_west", ".pb");

        String northTime = northModel.split("_")[2].split(".")[0];
        String southTime = southModel.split("_")[2].split(".")[0];
        String eastTime = eastModel.split("_")[2].split(".")[0];
        String westTime = westModel.split("_")[2].split(".")[0];

        // Create urls
        String[] urls = new String[8];
        urls[0] = mUrlString + "/update-model?model-key=ModelPathNorth&model-time=" + northTime;
        urls[1] = mUrlString + "/update-model?model-key=ModelPathSouth&model-time=" + southTime;
        urls[2] = mUrlString + "/update-model?model-key=ModelPathEast&model-time=" + eastTime;
        urls[3] = mUrlString + "/update-model?model-key=ModelPathWest&model-time=" + westTime;
        urls[4] = mUrlString + "/update-label?model-key=ModelPathNorth&model-time=" + northTime;
        urls[5] = mUrlString + "/update-label?model-key=ModelPathSouth&model-time=" + southTime;
        urls[6] = mUrlString + "/update-label?model-key=ModelPathEast&model-time=" + eastTime;
        urls[7] = mUrlString + "/update-label?model-key=ModelPathWest&model-time=" + westTime;
        mDownloadTask.execute(urls);
    }

    /**
     * Cancel (and interrupt if necessary) any ongoing DownloadTask execution.
     */
    public void cancelDownload() {
        if (mDownloadTask != null) {
            mDownloadTask.cancel(true);
        }
    }

    /**
     * Implementation of AsyncTask designed to fetch data from the network.
     */
    private class DownloadTask extends AsyncTask<String, Integer, DownloadTask.Result> {

        private DownloadCallback<String> mCallback;

        DownloadTask(DownloadCallback<String> callback) {
            setCallback(callback);
        }

        void setCallback(DownloadCallback<String> callback) {
            mCallback = callback;
        }

        /**
         * Wrapper class that serves as a union of a result value and an exception. When the download
         * task has completed, either the result value or exception can be a non-null value.
         * This allows you to pass exceptions to the UI thread that were thrown during doInBackground().
         */
        class Result {
            public boolean mResultValue;
            public ArrayList<String> failedDownloads;
            public Result(boolean resultValue) {
                mResultValue = resultValue;
            }
        }

        /**
         * Cancel background network operation if we do not have network connectivity.
         */
        @Override
        protected void onPreExecute() {
            if (mCallback != null) {
                NetworkInfo networkInfo = mCallback.getActiveNetworkInfo();
                if (networkInfo == null || !networkInfo.isConnected() ||
                        (networkInfo.getType() != ConnectivityManager.TYPE_WIFI
                                && networkInfo.getType() != ConnectivityManager.TYPE_MOBILE)) {
                    // If no connectivity, cancel task and update Callback with null data.
                    mCallback.updateFromDownload(null);
                    cancel(true);
                }
            }
        }

        /**
         * Defines work to perform on the background thread.
         */
        @Override
        protected DownloadTask.Result doInBackground(String... urls) {
            Result result = new Result(false);
            if (!isCancelled() && urls != null && urls.length > 0) {
                int count = urls.length;

                for (int i = 0; i < count; i++) {
                    try {
                        URL url = new URL(urls[i]);
                        boolean success = downloadUrl(url);

                        if (!result.mResultValue) {
                            result.mResultValue = success;
                        }

                        if (!success) {
                            result.failedDownloads.add(urls[i]);
                        }
                    }
                    catch (Exception e) {
                        Log.e("doInBackground", e.getMessage());
                    }
                }
            }
            return result;
        }

        /**
         * Updates the DownloadCallback with the result.
         */
        @Override
        protected void onPostExecute(Result result) {
            if (result != null && mCallback != null) {
                for (int i = 0; i < result.failedDownloads.size(); ++i) {
                    Log.i("onPostExecute", "failed url:" + result.failedDownloads.get(i));
                }

                mCallback.updateFromDownload(Boolean.toString(result.mResultValue));
                mCallback.finishDownloading();
            }
        }

        /**
         * Override to add special behavior for cancelled AsyncTask.
         */
        @Override
        protected void onCancelled(Result result) {
            Log.d("DownloadTask", "onCancelled called");
        }

        /**
         * Given a URL, sets up a connection and gets the HTTP response body from the server.
         * If the network request is successful, it returns the response body in String form. Otherwise,
         * it will throw an IOException.
         */
        private boolean downloadUrl(URL url) throws IOException {
            InputStream stream = null;
            HttpsURLConnection connection = null;
            boolean success = false;
            try {
                connection = (HttpsURLConnection) url.openConnection();
                // Timeout for reading InputStream arbitrarily set to 3000ms.
                connection.setReadTimeout(3000);
                // Timeout for connection.connect() arbitrarily set to 3000ms.
                connection.setConnectTimeout(3000);
                // For this use case, set HTTP method to GET.
                connection.setRequestMethod("GET");
                // Already true by default but setting just in case; needs to be true since this request
                // is carrying an input (response) body.
                connection.setDoInput(true);
                // Open communications link (network traffic occurs here).
                connection.connect();
                publishProgress(DownloadCallback.Progress.CONNECT_SUCCESS);
                int responseCode = connection.getResponseCode();
                if (responseCode != HttpsURLConnection.HTTP_OK) {
                    return success;
                }
                // Retrieve the response body as an InputStream.
                stream = connection.getInputStream();
                publishProgress(DownloadCallback.Progress.GET_INPUT_STREAM_SUCCESS, 0);
                if (stream != null) {
                    // Save to file
                    String raw = connection.getHeaderField("Content-Disposition"); // raw = "attachment; filename=abc.jpg"
                    if(raw != null && raw.indexOf("=") != -1) {
                        String fileName = raw.split("=")[1]; //getting value after '='
                        success = saveFile(stream, fileName, url);
                    }
                }
            } finally {
                // Close Stream and disconnect HTTPS connection.
                if (stream != null) {
                    stream.close();
                }
                if (connection != null) {
                    connection.disconnect();
                }
            }
            return success;
        }

        /*
         * Writes the stream to file
         */
        private boolean saveFile(InputStream stream, String fileName, URL url) throws IOException {
            File targetFile = null;

            if (fileName == "retrained_labels.txt") {
                // Parse URL
                String query = url.getQuery();
                String[] params = query.split("&");
                Map<String, String> map = new HashMap<String, String>();
                for (String param : params)
                {
                    String name = param.split("=")[0];
                    String value = param.split("=")[1];
                    map.put(name, value);
                }

                if (url.getPath().contains("label")) {
                    String modelKey = map.get("model-key");
                    switch (modelKey) {
                        case "ModelPathNorth":
                            targetFile = new File("file:///android_asset/us_north/retrained_labels.txt");
                            break;
                        case "ModelPathSouth":
                            targetFile = new File("file:///android_asset/us_south/retrained_labels.txt");
                            break;
                        case "ModelPathEast":
                            targetFile = new File("file:///android_asset/us_east/retrained_labels.txt");
                            break;
                        case "ModelPathWest":
                            targetFile = new File("file:///android_asset/us_west/retrained_labels.txt");
                            break;
                        default:
                            break;
                    }
                }
            } else {
                // Split file name into components
                String[] fileComp = fileName.split("_");
                String[] fileComp2 = fileComp[2].split(".");
                String region = fileComp[0] + "_" + fileComp[1];
                String time = fileComp2[0];
                String extension = fileComp2[1];

                switch (region) {
                    case "us_north":
                        if (extension == "pb") {
                            //String model = getModelFileName("us_north", ".pb");
                            targetFile = new File("file:///android_asset/us_north/us_north_" + time + ".pb");
                        }
                        break;
                    case "us_south":
                        if (extension == "pb") {
                            targetFile = new File("file:///android_asset/us_south/us_south_" + time + ".pb");
                        }
                        break;
                    case "us_east":
                        if (extension == "pb") {
                            targetFile = new File("file:///android_asset/us_east/us_east_" + time + ".pb");
                        }
                        break;
                    case "us_west":
                        if (extension == "pb") {
                            targetFile = new File("file:///android_asset/us_west/us_west_" + time + ".pb");
                        }
                        break;
                    default:

                        break;
                }
            }

            /*if (url.getPath().contains("model")) {
                String modelKey = map.get("model-key");
                switch (modelKey) {
                    case "ModelPathNorth":
                        targetFile = new File("file:///android_asset/us_north/us_north_.pb");
                        break;
                    case "ModelPathSouth":
                        break;
                    case "ModelPathEast":
                        break;
                    case "ModelPathWest":
                        break;
                    default:
                        break;
                }*/


            // Create file

            OutputStream outStream = new FileOutputStream(targetFile);

            // Write to file
            byte[] buffer = new byte[stream.available()];
            outStream.write(buffer);

            return true;
        }
    }

    public String getModelFileName(String path, String ext) {
        String [] list;
        try {
            list = getActivity().getAssets().list(path);
            if (list.length > 0) {
                for (String file : list)
                {
                    if (file.endsWith(ext))
                    {
                        return "file:///android_asset/" + path + "/" + file;
                    }
                }
            }
        }
        catch (IOException e) {
            return "";
        }

        return "";
    }
}
