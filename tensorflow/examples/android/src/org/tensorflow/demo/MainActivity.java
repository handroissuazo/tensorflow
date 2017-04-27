package org.tensorflow.demo;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity implements DownloadCallback {

    // Reference to NetworkFragment that executes network operations.
    private static String serverUrl = "http://192.168.1.102";
    private NetworkFragment mNetworkFragment;

    // Flag that is set when a download is in progress to prevent overlapping downloads
    // triggered by consecutive calls.
    private boolean mDownloading = false;


    String internalPath = Environment.getExternalStorageDirectory() + "/eLeaf";  // Your application path

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        copyAssets();
        mNetworkFragment = NetworkFragment.getInstance(getSupportFragmentManager(), serverUrl);
    }


    public void instructions(View view) {
        Intent intent = new Intent(this, InstructionActivity.class);
        startActivity(intent);
    }

    private void copyAssets()
    {
        File fileExtracted = new File(internalPath + "/extracted.txt");
        if (!fileExtracted.exists())
        {
            AssetManager assetManager = getAssets();
            copyAssetFolder(assetManager, "us_north", internalPath + "/us_north");
            copyAssetFolder(assetManager, "us_east", internalPath + "/us_east");
            copyAssetFolder(assetManager, "us_west", internalPath + "/us_west");
            copyAssetFolder(assetManager, "us_south", internalPath + "/us_south");
            fileExtracted.mkdirs();
        }
    }

    private static boolean copyAssetFolder(AssetManager assetManager,
                                           String fromAssetPath, String toPath) {
        try {
            String[] files = assetManager.list(fromAssetPath);
            new File(toPath).mkdirs();
            boolean res = true;
            for (String file : files)
                if (file.contains("."))
                    res &= copyAsset(assetManager,
                            fromAssetPath + "/" + file,
                            toPath + "/" + file);
                else
                    res &= copyAssetFolder(assetManager,
                            fromAssetPath + "/" + file,
                            toPath + "/" + file);
            return res;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean copyAsset(AssetManager assetManager,
                                     String fromAssetPath, String toPath) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = assetManager.open(fromAssetPath);
            new File(toPath).createNewFile();
            out = new FileOutputStream(toPath);
            copyFile(in, out);
            in.close();
            in = null;
            out.flush();
            out.close();
            out = null;
            return true;
        } catch(Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while((read = in.read(buffer)) != -1){
            out.write(buffer, 0, read);
        }
    }

    /*
     * Change to the Classify activity when the button is pressed.
     */
    public void classify(View view) {
        Intent intent = new Intent(this, ClassifierActivity.class);
        startActivity(intent);
    }

    /*
     * Change to the History activity when the button is pressed.
     */
    public void history(View view) {
        Intent intent = new Intent(this, ClassifyHistoryActivity.class);
        startActivity(intent);
    }

    /*
     * Change to the Capture activity when the button is pressed.
     */
    public void capture(View view) {
        Intent intent = new Intent(this, CaptureActivity.class);
        startActivity(intent);
    }

    /*
     * Add a menu to the view.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.camera_menu, menu);
        return true;
    }

    /*
     * Handle menu click events.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.update_model:
                updateModel();
                return true;
            case R.id.about:
                about();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Check if the server has a new update model update available and download it.
     */
    public void updateModel() {
        // Check if update exists.

        // Download updates if they exist.
        startDownload();
    }

    /*
     * Display the about view.
     */
    public void about() {
        // Change to about view
        Intent intent = new Intent(this, AboutActivity.class);
        startActivity(intent);
    }

    /*
     * Initiate the download.
     */
    private void startDownload() {
        if (!mDownloading && mNetworkFragment != null) {
            // Execute the async download.
            mNetworkFragment.startDownload();
            mDownloading = true;
        }
    }

    @Override
    public void updateFromDownload(Object result) {
        // Update UI based on result from download
        // result.toString() contains whether there is an update or not.
        // true means that there is an update
        // false means that there is not an update
        Log.d("updateFromDownload", "Model Update Available: " + result.toString());
    }

    @Override
    public NetworkInfo getActiveNetworkInfo() {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        return networkInfo;
    }

    @Override
    public void onProgressUpdate(int progressCode, int percentComplete) {
        Log.d("Model update progress percentComplete", Integer.toString(percentComplete));
        switch (progressCode) {
            // You can add UI behavior for progress updates here.
            case Progress.ERROR:
                Log.d("Model update progress", "ERROR");
                break;
            case Progress.CONNECT_SUCCESS:
                Log.d("Model update progress", "CONNECT_SUCCESS");
                break;
            case Progress.GET_INPUT_STREAM_SUCCESS:
                Log.d("Model update progress", "GET_INPUT_STREAM_SUCCESS");
                break;
            case Progress.PROCESS_INPUT_STREAM_IN_PROGRESS:
                Log.d("Model update progress", "PROCESS_INPUT_STREAM_IN_PROGRESS");
                break;
            case Progress.PROCESS_INPUT_STREAM_SUCCESS:
                Log.d("Model update progress", "PROCESS_INPUT_STREAM_SUCCESS");
                break;
        }
    }

    @Override
    public void finishDownloading() {
        Log.d("Model update", "Finished downloading.");
        mDownloading = false;
        if (mNetworkFragment != null) {
            mNetworkFragment.cancelDownload();
        }
    }
}
