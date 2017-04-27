/*
 * Copyright 2016 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.graphics.Rect;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.hardware.camera2.CaptureFailure;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.design.widget.FloatingActionButton;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.tensorflow.demo.env.Logger;
import org.tensorflow.demo.R;

public class CameraConnectionFragment extends Fragment {
  private static final Logger LOGGER = new Logger();

  /**
   * The parent view containing most of the elements. It is set onViewCreated.
   */
  private View parentView;

  /**
   * The camera preview size will be chosen to be the smallest frame by pixel size capable of
   * containing a DESIRED_SIZE x DESIRED_SIZE square.
   */
  private static final int MINIMUM_PREVIEW_SIZE = 320;

  /**
   * Conversion from screen rotation to JPEG orientation.
   */
  private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
  private static final String FRAGMENT_DIALOG = "dialog";

  static {
    ORIENTATIONS.append(Surface.ROTATION_0, 90);
    ORIENTATIONS.append(Surface.ROTATION_90, 0);
    ORIENTATIONS.append(Surface.ROTATION_180, 270);
    ORIENTATIONS.append(Surface.ROTATION_270, 180);
  }

  /**
   * {@link android.view.TextureView.SurfaceTextureListener} handles several lifecycle events on a
   * {@link TextureView}.
   */
  private final TextureView.SurfaceTextureListener surfaceTextureListener =
      new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(
            final SurfaceTexture texture, final int width, final int height) {
          openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(
            final SurfaceTexture texture, final int width, final int height) {
          configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
          return true;
        }

        @Override
        public void onSurfaceTextureUpdated(final SurfaceTexture texture) {}
      };

  /**
   * Callback for Activities to use to initialize their data once the
   * selected preview size is known.
   */
  public interface ConnectionCallback {
    void onPreviewSizeChosen(Size size, int cameraRotation);
    void onModelLabelSelected(int index);
  }

  /**
   * ID of the current {@link CameraDevice}.
   */
  private String cameraId;

  /**
   * An {@link AutoFitTextureView} for camera preview.
   */
  private AutoFitTextureView textureView;

  /**
   * A {@link CameraCaptureSession } for camera preview.
   */
  private CameraCaptureSession captureSession;

  /**
   * A reference to the opened {@link CameraDevice}.
   */
  private CameraDevice cameraDevice;

  /**
   * The rotation in degrees of the camera sensor from the display.
   */
  private Integer sensorOrientation;

  /**
   * The {@link android.util.Size} of camera preview.
   */
  private Size previewSize;

  /**
   * {@link android.hardware.camera2.CameraDevice.StateCallback}
   * is called when {@link CameraDevice} changes its state.
   */
  private final CameraDevice.StateCallback stateCallback =
      new CameraDevice.StateCallback() {
        @Override
        public void onOpened(final CameraDevice cd) {
          // This method is called when the camera is opened.  We start camera preview here.
          cameraOpenCloseLock.release();
          cameraDevice = cd;
          createCameraPreviewSession();
          configureCameraFloatingActionButton();
          configureModelLabelButton();
	      configureTouchFocus();
        }

        @Override
        public void onDisconnected(final CameraDevice cd) {
          cameraOpenCloseLock.release();
          cd.close();
          cameraDevice = null;
        }

        @Override
        public void onError(final CameraDevice cd, final int error) {
          cameraOpenCloseLock.release();
          cd.close();
          cameraDevice = null;
          final Activity activity = getActivity();
          if (null != activity) {
            activity.finish();
          }
        }
      };

  /**
   * An additional thread for running tasks that shouldn't block the UI.
   */
  private HandlerThread backgroundThread;

  /**
   * A {@link Handler} for running tasks in the background.
   */
  private Handler backgroundHandler;

  /**
   * {@link android.hardware.camera2.CaptureRequest.Builder} for the camera preview
   */
  private CaptureRequest.Builder previewRequestBuilder;

  /**
   * {@link CaptureRequest} generated by {@link #previewRequestBuilder}
   */
  private CaptureRequest previewRequest;

  /**
   * A {@link Semaphore} to prevent the app from exiting before closing the camera.
   */
  private final Semaphore cameraOpenCloseLock = new Semaphore(1);

  /**
   * A {@link OnImageAvailableListener} to receive frames as they are available.
   */
  private final OnImageAvailableListener imageListener;

  /**
   * The input size in pixels desired by TensorFlow (width and height of a square bitmap).
   */
  private final int inputSize;

  /**
   * The layout identifier to inflate for this Fragment.
   */
  private final int layout;


  private final ConnectionCallback cameraConnectionCallback;

  private CameraConnectionFragment(
      final ConnectionCallback connectionCallback,
      final OnImageAvailableListener imageListener,
      final int layout, final int inputSize) {
    this.cameraConnectionCallback = connectionCallback;
    this.imageListener = imageListener;
    this.layout = layout;
    this.inputSize = inputSize;
  }

  /**
   * Shows a {@link Toast} on the UI thread.
   *
   * @param text The message to show
   */
  private void showToast(final String text) {
    final Activity activity = getActivity();
    if (activity != null) {
      activity.runOnUiThread(
          new Runnable() {
            @Override
            public void run() {
              Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
            }
          });
    }
  }

  /**
   * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
   * width and height are at least as large as the respective requested values, and whose aspect
   * ratio matches with the specified value.
   *
   * @param choices     The list of sizes that the camera supports for the intended output class
   * @param width       The minimum desired width
   * @param height      The minimum desired height
   * @param aspectRatio The aspect ratio
   * @return The optimal {@code Size}, or an arbitrary one if none were big enough
   */
  private static Size chooseOptimalSize(
      final Size[] choices, final int width, final int height, final Size aspectRatio) {
    // Collect the supported resolutions that are at least as big as the preview Surface
    final List<Size> bigEnough = new ArrayList<Size>();

    final int minWidth = Math.max(width, MINIMUM_PREVIEW_SIZE);
    final int minHeight = Math.max(height, MINIMUM_PREVIEW_SIZE);

    for (final Size option : choices) {
      if (option.getHeight() >= minHeight && option.getWidth() >= minWidth) {
        LOGGER.i("Adding size: " + option.getWidth() + "x" + option.getHeight());
        bigEnough.add(option);
      } else {
        LOGGER.i("Not adding size: " + option.getWidth() + "x" + option.getHeight());
      }
    }

    // Pick the smallest of those, assuming we found any
    if (bigEnough.size() > 0) {
      final Size chosenSize = Collections.min(bigEnough, new CompareSizesByArea());
      LOGGER.i("Chosen size: " + chosenSize.getWidth() + "x" + chosenSize.getHeight());
      return chosenSize;
    } else {
      LOGGER.e("Couldn't find any suitable preview size");
      return choices[0];
    }
  }

  public static CameraConnectionFragment newInstance(
      final ConnectionCallback callback,
      final OnImageAvailableListener imageListener, final int layout, final int inputSize) {
    return new CameraConnectionFragment(callback, imageListener, layout, inputSize);
  }

  @Override
  public View onCreateView(
      final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
    return inflater.inflate(R.layout.camera_connection_fragment, container, false);
  }

  @Override
  public void onViewCreated(final View view, final Bundle savedInstanceState) {
    textureView = (AutoFitTextureView) view.findViewById(R.id.texture);
    parentView = view;
  }

  @Override
  public void onActivityCreated(final Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
  }

  @Override
  public void onResume() {
    super.onResume();
    startBackgroundThread();

    // When the screen is turned off and turned back on, the SurfaceTexture is already
    // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
    // a camera and start preview from here (otherwise, we wait until the surface is ready in
    // the SurfaceTextureListener).
    if (textureView.isAvailable()) {
      openCamera(textureView.getWidth(), textureView.getHeight());
    } else {
      textureView.setSurfaceTextureListener(surfaceTextureListener);
    }
  }

  @Override
  public void onPause() {
    closeCamera();
    stopBackgroundThread();
    super.onPause();
  }

  /**
   * Sets up member variables related to camera.
   *
   * @param width  The width of available size for camera preview
   * @param height The height of available size for camera preview
   */
  private void setUpCameraOutputs(final int width, final int height) {
    final Activity activity = getActivity();
    final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    try {
      for (final String cameraId : manager.getCameraIdList()) {
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        // We don't use a front facing camera in this sample.
        final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          continue;
        }

        final StreamConfigurationMap map =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (map == null) {
          continue;
        }

        // For still image captures, we use the largest available size.
        final Size largest =
            Collections.max(
                Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)),
                new CompareSizesByArea());

        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

        // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
        // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
        // garbage capture data.
        previewSize =
            chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                inputSize, inputSize, largest);

        // We fit the aspect ratio of TextureView to the size of preview we picked.
        final int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
          textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
        } else {
          textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
        }

        CameraConnectionFragment.this.cameraId = cameraId;

        cameraConnectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation);
        return;
      }
    } catch (final CameraAccessException e) {
      LOGGER.e(e, "Exception!");
    } catch (final NullPointerException e) {
      // Currently an NPE is thrown when the Camera2API is used but not supported on the
      // device this code runs.
      ErrorDialog.newInstance(getString(R.string.camera_error))
          .show(getChildFragmentManager(), FRAGMENT_DIALOG);
    }
  }

  /**
   * Opens the camera specified by {@link CameraConnectionFragment#cameraId}.
   */
  private void openCamera(final int width, final int height) {
    setUpCameraOutputs(width, height);
    configureTransform(width, height);
    final Activity activity = getActivity();
    final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    try {
      if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
        throw new RuntimeException("Time out waiting to lock camera opening.");
      }
      manager.openCamera(cameraId, stateCallback, backgroundHandler);
    } catch (final CameraAccessException e) {
      LOGGER.e(e, "Exception!");
    } catch (final InterruptedException e) {
      throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
    }
  }

  /**
   * Closes the current {@link CameraDevice}.
   */
  private void closeCamera() {
    try {
      cameraOpenCloseLock.acquire();
      if (null != captureSession) {
        captureSession.close();
        captureSession = null;
      }
      if (null != cameraDevice) {
        cameraDevice.close();
        cameraDevice = null;
      }
    } catch (final InterruptedException e) {
      throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
    } finally {
      cameraOpenCloseLock.release();
    }
  }

  /**
   * Starts a background thread and its {@link Handler}.
   */
  private void startBackgroundThread() {
    backgroundThread = new HandlerThread("ImageListener");
    backgroundThread.start();
    backgroundHandler = new Handler(backgroundThread.getLooper());
  }

  /**
   * Stops the background thread and its {@link Handler}.
   */
  private void stopBackgroundThread() {
    backgroundThread.quitSafely();
    try {
      backgroundThread.join();
      backgroundThread = null;
      backgroundHandler = null;
    } catch (final InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }
  }

  private final CameraCaptureSession.CaptureCallback captureCallback =
      new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(
            final CameraCaptureSession session,
            final CaptureRequest request,
            final CaptureResult partialResult) {}

        @Override
        public void onCaptureCompleted(
            final CameraCaptureSession session,
            final CaptureRequest request,
            final TotalCaptureResult result) {

            createCameraPreviewSession();
                //this is where to swtich screen if need to later.
                //change the comment out the above call and switch screen
            }
      };

  /**
   * Creates a new {@link CameraCaptureSession} for camera preview.
   */
  private void createCameraPreviewSession() {
    try {
      final SurfaceTexture texture = textureView.getSurfaceTexture();
      assert texture != null;

      // We configure the size of default buffer to be the size of camera preview we want.
      texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

      // This is the output Surface we need to start preview.
      final Surface surface = new Surface(texture);

      // We set up a CaptureRequest.Builder with the output Surface.
      previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      previewRequestBuilder.addTarget(surface);

      LOGGER.i("Opening camera preview: " + previewSize.getWidth() + "x" + previewSize.getHeight());

      // Here, we create a CameraCaptureSession for camera preview.
      cameraDevice.createCaptureSession(
          Arrays.asList(surface),
          new CameraCaptureSession.StateCallback() {

            @Override
            public void onConfigured(final CameraCaptureSession cameraCaptureSession) {
              // The camera is already closed
              if (null == cameraDevice) {
                return;
              }

              // When the session is ready, we start displaying the preview.
              captureSession = cameraCaptureSession;

              // Configure the preview screen and show the camera footage.
              updatePreview();
            }

            @Override
            public void onConfigureFailed(final CameraCaptureSession cameraCaptureSession) {
              showToast("Failed");
            }
          },
          null);
    } catch (final CameraAccessException e) {
      LOGGER.e(e, "Exception!");
    }
  }

  /**
   * Update the preview object with the correct configurations.
   */
  private void updatePreview()
  {
    // The camera is already closed
    if (null == cameraDevice) {
      LOGGER.e("Preview:", "Camera close before update was called");
      return;
    }
    try {
      // Auto focus should be continuous for camera preview.
      previewRequestBuilder.set(
              CaptureRequest.CONTROL_AF_MODE,
              CaptureRequest.CONTROL_AF_MODE_OFF);
              //CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

      // Flash is automatically enabled when necessary.
      previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, null);
      previewRequestBuilder.set(
              CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

      // Finally, we start displaying the camera preview.
      previewRequest = previewRequestBuilder.build();

      // This initiates the camera preview.
      captureSession.setRepeatingRequest(
              previewRequest, null, backgroundHandler);

    }
    catch (final CameraAccessException e) {
      LOGGER.e(e, "Camera Access Exception during preview!");
    }
  }

    /**
     * This implements the manual touch focus
     */
    private boolean focusInOnTouch(View view, MotionEvent motionEvent)
    {
        //showToast("Manual Focus in development");

        final Activity activity = getActivity();
        final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);;

            Rect sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

            final int y = (int)((motionEvent.getX() / (float)view.getWidth())  * (float)sensorArraySize.height());
            final int x = (int)((motionEvent.getY() / (float)view.getHeight()) * (float)sensorArraySize.width());
            final int halfTouchWidth  = 100;
            final int halfTouchHeight = 100;
            MeteringRectangle focusAreaTouch = new MeteringRectangle(Math.max(x - halfTouchWidth,  0),
                    Math.max(y - halfTouchHeight, 0),
                    halfTouchWidth,
                    halfTouchHeight,
                    MeteringRectangle.METERING_WEIGHT_MAX - 1);


            //first stop the existing repeating request
            captureSession.stopRepeating();

            //cancel any existing AF trigger (repeated touches, etc.)
            //previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
           // previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);

            //Now add a new AF trigger with focus region
            if (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) >= 1) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusAreaTouch});
            }
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            previewRequestBuilder.setTag("FOCUS_TAG"); //we'll capture this later for resuming the preview

            //then we ask for a single request (not repeating!)
            previewRequest = previewRequestBuilder.build();

            // This initiates the camera preview.
            captureSession.setRepeatingRequest(previewRequest, null, backgroundHandler);
            return true;
        }
        catch (final CameraAccessException e) {
            LOGGER.e(e, "Exception!");
        } catch (final NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
        return true;
    }
  /**
   * This creates a new capture session to actually take the photo and analyze it.
   */
  private void takePhoto()
  {
    try {
      final SurfaceTexture texture = textureView.getSurfaceTexture();
      assert texture != null;

      // We configure the size of default buffer to be the size of camera preview we want.
      texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

      // This is the output Surface we need to start preview.
      final Surface surface = new Surface(texture);

      // We set up a CaptureRequest.Builder with the output Surface.
      final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
      //captureBuilder.addTarget(surface);

      LOGGER.i("Taking a photo: " + previewSize.getWidth() + "x" + previewSize.getHeight());

      // Create the reader for the preview frames.
      ImageReader reader =
              ImageReader.newInstance(
                      previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

      reader.setOnImageAvailableListener(imageListener, backgroundHandler);
      captureBuilder.addTarget(reader.getSurface());
      captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

      // Here, we create a CameraCaptureSession for camera preview.
      cameraDevice.createCaptureSession(
              Arrays.asList(surface, reader.getSurface()),
              new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(final CameraCaptureSession cameraCaptureSession) {
                  // The camera is already closed
                  if (null == cameraDevice) {
                    return;
                  }
                  try {
                    cameraCaptureSession.capture(captureBuilder.build(), captureCallback, backgroundHandler);
                  }
                  catch (final CameraAccessException e) {
                    LOGGER.e(e, "Camera Access Exception: ");
                  }
                }

                @Override
                public void onConfigureFailed(final CameraCaptureSession cameraCaptureSession) {
                  showToast("Failed");
                }
              },
              null);
    } catch (final CameraAccessException e) {
      LOGGER.e(e, "Exception!");
    }
  }

  /**
   * Configures the Camera Floating Action Button to associate a press with a camera capture.
   */
  private void configureCameraFloatingActionButton()
  {
    FloatingActionButton myFab = (FloatingActionButton) parentView.findViewById(R.id.cameraFloatingActionButton);
    myFab.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        takePhoto();
      }

    });
  }

  /**
   * Configures the Model Selection Button
   */
  private void configureModelLabelButton()
  {
    final Context mContext = getActivity().getApplicationContext();
    final Activity mActivity = getActivity();

    final Button modelSelect = (Button) parentView.findViewById(R.id.model_select_button);

    modelSelect.setOnClickListener(new View.OnClickListener()
       {
         @Override
         public void onClick(View view) {
                /*
                    public PopupMenu (Context context, View anchor)
                        Constructor to create a new popup menu with an anchor view.

                    Parameters
                        context : Context the popup menu is running in, through which it can access
                                  the current theme, resources, etc.
                        anchor : Anchor view for this popup. The popup will appear below the anchor
                                 if there is room, or above it if there is not.
                */
           // Initialize a new instance of popup menu
           PopupMenu popupMenu = new PopupMenu(mContext,modelSelect);

                /*
                    public MenuInflater getMenuInflater ()

                    Returns
                        a MenuInflater that can be used to inflate menu items from XML into
                        the menu returned by getMenu().
                */
                /*
                    public void inflate (int menuRes)
                        Inflate a menu resource into this PopupMenu. This is equivalent to calling
                        popupMenu.getMenuInflater().inflate(menuRes, popupMenu.getMenu()).

                    Parameters
                        menuRes : Menu resource to inflate
                */
           // Inflate the popup menu
           popupMenu.getMenuInflater().inflate(R.menu.model_selection_popup,popupMenu.getMenu());

                /*
                    public void setOnMenuItemClickListener (PopupMenu.OnMenuItemClickListener listener)
                        Set a listener that will be notified when the user selects an item from the menu.

                    Parameters
                        listener : Listener to notify
                */
                /*
                    public abstract boolean onMenuItemClick (MenuItem item)
                        This method will be invoked when a menu item is clicked if the item itself
                        did not already handle the event.

                    Parameters
                        item : MenuItem that was clicked
                    Returns
                        true : if the event was handled, false otherwise.
                */
           // Set a click listener for menu item click
           popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
             @Override
             public boolean onMenuItemClick(MenuItem menuItem) {
               switch(menuItem.getItemId()){
                 case R.id.one:
                   cameraConnectionCallback.onModelLabelSelected(0);
                   return true;
                 case R.id.two:
                   cameraConnectionCallback.onModelLabelSelected(1);
                   return true;
                 case R.id.three:
                   cameraConnectionCallback.onModelLabelSelected(2);
                   return true;
                 case R.id.four:
                   cameraConnectionCallback.onModelLabelSelected(3);
                   return true;
                 default:
                   return false;
               }
             }
           });

           // Finally, show the popup menu
           popupMenu.show();
         }
       }
    );
  }

  private void configureTouchFocus()
  {
      textureView.setOnTouchListener(new View.OnTouchListener() {
          @Override
          public boolean onTouch(View view, MotionEvent motionEvent)
          {
              return focusInOnTouch(view, motionEvent);
          }
      });
  }

  /**
   * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
   * This method should be called after the camera preview size is determined in
   * setUpCameraOutputs and also the size of `mTextureView` is fixed.
   *
   * @param viewWidth  The width of `mTextureView`
   * @param viewHeight The height of `mTextureView`
   */
  private void configureTransform(final int viewWidth, final int viewHeight) {
    final Activity activity = getActivity();
    if (null == textureView || null == previewSize || null == activity) {
      return;
    }
    final int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
    final Matrix matrix = new Matrix();
    final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
    final RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
    final float centerX = viewRect.centerX();
    final float centerY = viewRect.centerY();
    if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
      bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
      matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
      final float scale =
          Math.max(
              (float) viewHeight / previewSize.getHeight(),
              (float) viewWidth / previewSize.getWidth());
      matrix.postScale(scale, scale, centerX, centerY);
      matrix.postRotate(90 * (rotation - 2), centerX, centerY);
    } else if (Surface.ROTATION_180 == rotation) {
      matrix.postRotate(180, centerX, centerY);
    }
    textureView.setTransform(matrix);
  }

  /**
   * Compares two {@code Size}s based on their areas.
   */
  static class CompareSizesByArea implements Comparator<Size> {
    @Override
    public int compare(final Size lhs, final Size rhs) {
      // We cast here to ensure the multiplications won't overflow
      return Long.signum(
          (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
    }
  }

  /**
   * Shows an error message dialog.
   */
  public static class ErrorDialog extends DialogFragment {
    private static final String ARG_MESSAGE = "message";

    public static ErrorDialog newInstance(final String message) {
      final ErrorDialog dialog = new ErrorDialog();
      final Bundle args = new Bundle();
      args.putString(ARG_MESSAGE, message);
      dialog.setArguments(args);
      return dialog;
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
      final Activity activity = getActivity();
      return new AlertDialog.Builder(activity)
          .setMessage(getArguments().getString(ARG_MESSAGE))
          .setPositiveButton(
              android.R.string.ok,
              new DialogInterface.OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialogInterface, final int i) {
                  activity.finish();
                }
              })
          .create();
    }
  }
}
