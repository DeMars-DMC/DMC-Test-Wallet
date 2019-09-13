package io.demars.stellarwallet.activities

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import io.demars.stellarwallet.R
import io.demars.stellarwallet.enums.CameraMode
import io.demars.stellarwallet.enums.FlashMode
import io.demars.stellarwallet.api.firebase.Firebase
import io.demars.stellarwallet.views.AutoFitTextureView
import kotlinx.android.synthetic.main.activity_camera.*
import org.jetbrains.anko.cameraManager
import timber.log.Timber
import java.lang.Long.signum
import java.util.*
import java.util.Collections.max
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class Camera2Activity : AppCompatActivity() {
  /**
   * [TextureView.SurfaceTextureListener] handles several lifecycle events on a
   * [TextureView].
   */
  private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
      openCamera(width, height)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
      configureTransform(width, height)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

  }

  /**
   * ID of the current [CameraDevice].
   */
  private lateinit var cameraId: String
  private var cameraMode = CameraMode.ID_FRONT
  private var flashMode = FlashMode.OFF

  /**
   * An [AutoFitTextureView] for camera preview.
   */
  private lateinit var textureView: AutoFitTextureView

  /**
   * A [CameraCaptureSession] for camera preview.
   */
  private var captureSession: CameraCaptureSession? = null

  /**
   * A reference to the opened [CameraDevice].
   */
  private var cameraDevice: CameraDevice? = null

  /**
   * The [android.util.Size] of camera preview.
   */
  private var previewSize = Size(512, 512)

  /**
   * [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.
   */
  private val stateCallback = object : CameraDevice.StateCallback() {

    override fun onOpened(cameraDevice: CameraDevice) {
      cameraOpenCloseLock.release()
      this@Camera2Activity.cameraDevice = cameraDevice
      createCameraPreviewSession()
    }

    override fun onDisconnected(cameraDevice: CameraDevice) {
      cameraOpenCloseLock.release()
      cameraDevice.close()
      this@Camera2Activity.cameraDevice = null
    }

    override fun onError(cameraDevice: CameraDevice, error: Int) {
      onDisconnected(cameraDevice)
      finish()
    }
  }

  /**
   * An additional thread for running tasks that shouldn't block the UI.
   */
  private var backgroundThread: HandlerThread? = null

  /**
   * A [Handler] for running tasks in the background.
   */
  private var backgroundHandler: Handler? = null
  private var mainHandler = Handler(Looper.getMainLooper())
  private var autoFocusSupported = false
  /**
   * An [ImageReader] that handles still image capture.
   */
  private var imageReader: ImageReader? = null

  /**
   * This a callback object for the [ImageReader]. "onImageAvailable" will be called when a
   * still image is ready to be saved.
   */
  private val onImageAvailableListener = ImageReader.OnImageAvailableListener {
    if (it != null) {
      val image = it.acquireLatestImage()
      val buffer = image.planes[0].buffer
      pictureBytes = ByteArray(buffer.capacity())
      buffer.get(pictureBytes)
      image.close()
      mainHandler.post { updateView() }
    }
  }

  /**
   * [CaptureRequest.Builder] for the camera preview
   */
  private lateinit var previewRequestBuilder: CaptureRequest.Builder

  /**
   * [CaptureRequest] generated by [.previewRequestBuilder]
   */
  private lateinit var previewRequest: CaptureRequest

  /**
   * The current state of camera state for taking pictures.
   *
   * @see .captureCallback
   */
  private var state = STATE_PREVIEW

  /**
   * A [Semaphore] to prevent the app from exiting before closing the camera.
   */
  private val cameraOpenCloseLock = Semaphore(1)

  /**
   * Whether the current camera device supports Flash or not.
   */
  private var flashSupported = false

  /**
   * Orientation of the camera sensor
   */
  private var sensorOrientation = 0

  /**
   * A [CameraCaptureSession.CaptureCallback] that handles events related to JPEG capture.
   */
  private val captureCallback = object : CameraCaptureSession.CaptureCallback() {

    private fun process(result: CaptureResult) {
      when (state) {
        STATE_PREVIEW -> Unit // Do nothing when the camera preview is working normally.
        STATE_WAITING_LOCK -> capturePicture(result)
        STATE_WAITING_PRECAPTURE -> {
          // CONTROL_AE_STATE can be null on some devices
          val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
          if (aeState == null ||
            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED
          ) {
            state =
              STATE_WAITING_NON_PRECAPTURE
          }
        }
        STATE_WAITING_NON_PRECAPTURE -> {
          // CONTROL_AE_STATE can be null on some devices
          val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
          if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
            state = STATE_PICTURE_TAKEN
            captureStillPicture()
          }
        }
      }
    }

    private fun capturePicture(result: CaptureResult) {
      val afState = result.get(CaptureResult.CONTROL_AF_STATE)
      if (afState == null) {
        captureStillPicture()
      } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
      ) {
        // CONTROL_AE_STATE can be null on some devices
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
          state = STATE_PICTURE_TAKEN
          captureStillPicture()
        } else {
          runPrecaptureSequence()
        }
      }
    }

    override fun onCaptureProgressed(
      session: CameraCaptureSession,
      request: CaptureRequest,
      partialResult: CaptureResult
    ) {
      process(partialResult)
    }

    override fun onCaptureCompleted(
      session: CameraCaptureSession,
      request: CaptureRequest,
      result: TotalCaptureResult
    ) {
      process(result)
    }

  }

  override fun onResume() {
    super.onResume()
    startBackgroundThread()

    // When the screen is turned off and turned back on, the SurfaceTexture is already
    // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
    // a camera and start preview from here (otherwise, we wait until the surface is ready in
    // the SurfaceTextureListener).
    if (textureView.isAvailable) {
      openCamera(textureView.width, textureView.height)
    } else {
      textureView.surfaceTextureListener = surfaceTextureListener
    }
  }

  override fun onPause() {
    closeCamera()
    stopBackgroundThread()
    super.onPause()
  }

  private fun requestCameraPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    if (requestCode == REQUEST_CAMERA_PERMISSION) {
      if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
        onError("Permission should be granted", true)
      }
    } else {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
  }

  /**
   * Sets up member variables related to camera.
   *
   * @param width  The width of available size for camera preview
   * @param height The height of available size for camera preview
   */
  private fun setUpCameraOutputs(width: Int, height: Int) {
    val manager = cameraManager
    try {
      for (cameraId in manager.cameraIdList) {
        val characteristics = manager.getCameraCharacteristics(cameraId)

        val map = characteristics.get(
          CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: continue

        var cameraAutoFocus = false
        val afAvailableModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        afAvailableModes?.let {
          cameraAutoFocus = !(afAvailableModes.isEmpty() || (afAvailableModes.size == 1
            && afAvailableModes[0] == CameraMetadata.CONTROL_AF_MODE_OFF))
        }

        // For still image captures, we use the largest available size.
        val largest = max(
          listOf(*map.getOutputSizes(ImageFormat.JPEG)),
          CompareSizesByArea()
        )
        imageReader = ImageReader.newInstance(
          largest.width, largest.height,
          ImageFormat.JPEG, /*maxImages*/ 1
        ).apply {
          setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
        }

        // Find out if we need to swap dimension to get the preview size relative to sensor
        // coordinate.
        val displayRotation = windowManager?.defaultDisplay?.rotation ?: 0

        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        val swappedDimensions = areDimensionsSwapped(displayRotation)

        val displaySize = Point()
        windowManager?.defaultDisplay?.getSize(displaySize)
        val rotatedPreviewWidth = if (swappedDimensions) height else width
        val rotatedPreviewHeight = if (swappedDimensions) width else height
        var maxPreviewWidth = if (swappedDimensions) displaySize.y else displaySize.x
        var maxPreviewHeight = if (swappedDimensions) displaySize.x else displaySize.y

        if (maxPreviewWidth > MAX_PREVIEW_WIDTH) maxPreviewWidth =
          MAX_PREVIEW_WIDTH
        if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) maxPreviewHeight =
          MAX_PREVIEW_HEIGHT

        // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
        // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
        // garbage capture data.
        previewSize = chooseOptimalSize(
          map.getOutputSizes(SurfaceTexture::class.java),
          rotatedPreviewWidth, rotatedPreviewHeight,
          maxPreviewWidth, maxPreviewHeight,
          largest
        )

        // We fit the aspect ratio of TextureView to the size of preview we picked.
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
          textureView.setAspectRatio(previewSize.width, previewSize.height)
        } else {
          textureView.setAspectRatio(previewSize.height, previewSize.width)
        }

        // Check if the flash is supported.
        flashSupported =
          characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

        // We don't use a front facing camera in this sample.
        val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
        if (cameraDirection != null &&
          cameraDirection == CameraCharacteristics.LENS_FACING_FRONT) {
          if (cameraMode == CameraMode.ID_SELFIE) {
            this.autoFocusSupported = cameraAutoFocus
            this.cameraId = cameraId
            return
          } else {
            continue
          }
        } else {
          this.autoFocusSupported = cameraAutoFocus
          this.cameraId = cameraId
        }
      }
    } catch (e: CameraAccessException) {
      Timber.e(e.toString())
    } catch (e: NullPointerException) {
      // Currently an NPE is thrown when the Camera2API is used but not supported on the
      // device this code runs.f
      onError("Camera error", true)
    }

  }

  /**
   * Determines if the dimensions are swapped given the phone's current rotation.
   *
   * @param displayRotation The current rotation of the display
   *
   * @return true if the dimensions are swapped, false otherwise.
   */
  private fun areDimensionsSwapped(displayRotation: Int): Boolean {
    var swappedDimensions = false
    when (displayRotation) {
      Surface.ROTATION_0, Surface.ROTATION_180 -> {
        if (sensorOrientation == 90 || sensorOrientation == 270) {
          swappedDimensions = true
        }
      }
      Surface.ROTATION_90, Surface.ROTATION_270 -> {
        if (sensorOrientation == 0 || sensorOrientation == 180) {
          swappedDimensions = true
        }
      }
      else -> {
        Timber.e("Display rotation is invalid: $displayRotation")
      }
    }
    return swappedDimensions
  }

  /**
   * Opens the camera specified by [Camera2Activity.cameraId].
   */
  private fun openCamera(width: Int, height: Int) {
    val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
    if (permission != PackageManager.PERMISSION_GRANTED) {
      requestCameraPermission()
      return
    }
    setUpCameraOutputs(width, height)
    configureTransform(width, height)
    val manager = cameraManager
    try {
      // Wait for camera to open - 2.5 seconds is sufficient
      if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
        onError("Failed to open camera", true)
        return
      }
      manager.openCamera(cameraId, stateCallback, backgroundHandler)
    } catch (e: CameraAccessException) {
      Timber.e(e.toString())
    } catch (e: InterruptedException) {
      onError("Interrupted while trying to lock camera opening.", true)
    }

  }

  /**
   * Closes the current [CameraDevice].
   */
  private fun closeCamera() {
    try {
      cameraOpenCloseLock.acquire()
      captureSession?.close()
      captureSession = null
      cameraDevice?.close()
      cameraDevice = null
      imageReader?.close()
      imageReader = null
    } catch (e: InterruptedException) {
      onError("Interrupted while trying to lock camera closing.", true)
    } finally {
      cameraOpenCloseLock.release()
    }
  }

  /**
   * Starts a background thread and its [Handler].
   */
  private fun startBackgroundThread() {
    backgroundThread = HandlerThread("CameraBackground").also { it.start() }
    backgroundHandler = Handler(backgroundThread?.looper)
  }

  /**
   * Stops the background thread and its [Handler].
   */
  private fun stopBackgroundThread() {
    backgroundThread?.quitSafely()
    try {
      backgroundThread?.join()
      backgroundThread = null
      backgroundHandler = null
    } catch (e: InterruptedException) {
      Timber.e(e.toString())
    }

  }

  /**
   * Creates a new [CameraCaptureSession] for camera preview.
   */
  private fun createCameraPreviewSession() {
    try {
      val texture = textureView.surfaceTexture

      // We configure the size of default buffer to be the size of camera preview we want.
      texture.setDefaultBufferSize(previewSize.width, previewSize.height)

      // This is the output Surface we need to start preview.
      val surface = Surface(texture)

      // We set up a CaptureRequest.Builder with the output Surface.
      previewRequestBuilder = cameraDevice!!.createCaptureRequest(
        CameraDevice.TEMPLATE_PREVIEW
      )

      when(flashMode) {
        FlashMode.ON  -> {
          previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
          previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
        }
        FlashMode.OFF -> {
          previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
          previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }
      }

      previewRequestBuilder.addTarget(surface)

      // Here, we create a CameraCaptureSession for camera preview.
      cameraDevice?.createCaptureSession(
        listOf(surface, imageReader?.surface),
        object : CameraCaptureSession.StateCallback() {

          override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
            // The camera is already closed
            if (cameraDevice == null) return

            // When the session is ready, we start displaying the preview.
            captureSession = cameraCaptureSession
            try {
              // Auto focus should be continuous for camera preview.
              previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
              )

              // Finally, we start displaying the camera preview.
              previewRequest = previewRequestBuilder.build()
              captureSession?.setRepeatingRequest(
                previewRequest, captureCallback, backgroundHandler)
            } catch (e: CameraAccessException) {
              Timber.e(e.toString())
            }

          }

          override fun onConfigureFailed(session: CameraCaptureSession) {
            onError("Camera configuring failed", true)
          }
        }, null
      )
    } catch (e: Exception) {
      onError("Failed to start camera session, please try again", true)
    }

  }

  /**
   * Configures the necessary [android.graphics.Matrix] transformation to `textureView`.
   * This method should be called after the camera preview size is determined in
   * setUpCameraOutputs and also the size of `textureView` is fixed.
   *
   * @param viewWidth  The width of `textureView`
   * @param viewHeight The height of `textureView`
   */
  private fun configureTransform(viewWidth: Int, viewHeight: Int) {
    val rotation = windowManager?.defaultDisplay?.rotation
    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()

    if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
      bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
      val scale = max(viewHeight.toFloat() / previewSize.height, viewWidth.toFloat() / previewSize.width)
      with(matrix) {
        setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        postScale(scale, scale, centerX, centerY)
        postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
      }
    } else if (Surface.ROTATION_180 == rotation) {
      matrix.postRotate(180f, centerX, centerY)
    }
    textureView.setTransform(matrix)
  }

  /**
   * Lock the focus as the first step for a still image capture.
   */
  private fun lockFocus() {
    try {
      // This is how to tell the camera to lock focus.
      previewRequestBuilder.set(
        CaptureRequest.CONTROL_AF_TRIGGER,
        CameraMetadata.CONTROL_AF_TRIGGER_START
      )

      // Tell #captureCallback to wait for the lock.
      state = STATE_WAITING_LOCK
      captureSession?.capture(
        previewRequestBuilder.build(), captureCallback,
        backgroundHandler
      )
    } catch (e: CameraAccessException) {
      Timber.e(e.toString())
    }
  }

  /**
   * Run the precapture sequence for capturing a still image. This method should be called when
   * we get a response in [.captureCallback] from [.lockFocus].
   */
  private fun runPrecaptureSequence() {
    try {
      // This is how to tell the camera to trigger.
      previewRequestBuilder.set(
        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
      )
      // Tell #captureCallback to wait for the precapture sequence to be set.
      state = STATE_WAITING_PRECAPTURE
      captureSession?.capture(
        previewRequestBuilder.build(), captureCallback,
        backgroundHandler
      )
    } catch (e: CameraAccessException) {
      Timber.e(e.toString())
    }

  }

  /**
   * Capture a still picture. This method should be called when we get a response in
   * [.captureCallback] from both [.lockFocus].
   */
  private fun captureStillPicture() {
    try {
      if (cameraDevice == null) return
      val rotation = windowManager?.defaultDisplay?.rotation ?: 0

      // This is the CaptureRequest.Builder that we use to take a picture.
      val captureBuilder = cameraDevice?.createCaptureRequest(
        CameraDevice.TEMPLATE_STILL_CAPTURE
      )?.apply {
        addTarget(imageReader?.surface!!)

        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        set(
          CaptureRequest.JPEG_ORIENTATION,
          (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360
        )

        // Use the same AE and AF modes as the preview.
        set(
          CaptureRequest.CONTROL_AF_MODE,
          CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
      }

      val captureCallback = object : CameraCaptureSession.CaptureCallback() {

        override fun onCaptureCompleted(
          session: CameraCaptureSession,
          request: CaptureRequest,
          result: TotalCaptureResult
        ) {
        }
      }

      captureSession?.apply {
        stopRepeating()
        abortCaptures()
        capture(captureBuilder?.build()!!, captureCallback, null)
      }
    } catch (e: CameraAccessException) {
      Timber.e(e.toString())
    }

  }

  /**
   * Unlock the focus. This method should be called when still image capture sequence is
   * finished.
   */
  private fun unlockFocus() {
    if (!::previewRequestBuilder.isInitialized) return

    try {
      // Reset the auto-focus trigger
      previewRequestBuilder.set(
        CaptureRequest.CONTROL_AF_TRIGGER,
        CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
      )

      captureSession?.capture(
        previewRequestBuilder.build(), captureCallback,
        backgroundHandler
      )
      // After this, the camera will go back to the normal state of preview.
      state = STATE_PREVIEW
      captureSession?.setRepeatingRequest(
        previewRequest, captureCallback,
        backgroundHandler
      )
    } catch (e: CameraAccessException) {
      Timber.e(e.toString())
    }
  }

  private var pictureBytes: ByteArray? = null

  companion object {
    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private val ORIENTATIONS = SparseIntArray()

    init {
      ORIENTATIONS.append(Surface.ROTATION_0, 90)
      ORIENTATIONS.append(Surface.ROTATION_90, 0)
      ORIENTATIONS.append(Surface.ROTATION_180, 270)
      ORIENTATIONS.append(Surface.ROTATION_270, 180)
    }

    private const val REQUEST_GALLERY = 111
    private const val REQUEST_CAMERA_PERMISSION = 222

    /**
     * Camera state: Showing camera preview.
     */
    private const val STATE_PREVIEW = 0

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private const val STATE_WAITING_LOCK = 1

    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private const val STATE_WAITING_PRECAPTURE = 2

    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private const val STATE_WAITING_NON_PRECAPTURE = 3

    /**
     * Camera state: Picture was taken.
     */
    private const val STATE_PICTURE_TAKEN = 4

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private const val MAX_PREVIEW_WIDTH = 1920

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private const val MAX_PREVIEW_HEIGHT = 1080

    /**
     * Given `choices` of `Size`s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as
     * the respective max size, and whose aspect ratio matches with the specified value. If such
     * size doesn't exist, choose the largest one that is at most as large as the respective max
     * size, and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended
     *                          output class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal `Size`, or an arbitrary one if none were big enough
     */
    @JvmStatic
    private fun chooseOptimalSize(
      choices: Array<Size>,
      textureViewWidth: Int,
      textureViewHeight: Int,
      maxWidth: Int,
      maxHeight: Int,
      aspectRatio: Size
    ): Size {

      // Collect the supported resolutions that are at least as big as the preview Surface
      val bigEnough = ArrayList<Size>()
      // Collect the supported resolutions that are smaller than the preview Surface
      val notBigEnough = ArrayList<Size>()
      val w = aspectRatio.width
      val h = aspectRatio.height
      for (option in choices) {
        if (option.width <= maxWidth && option.height <= maxHeight &&
          option.height == option.width * h / w
        ) {
          if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
            bigEnough.add(option)
          } else {
            notBigEnough.add(option)
          }
        }
      }

      // Pick the smallest of those big enough. If there is no one big enough, pick the
      // largest of those not big enough.
      return when {
        bigEnough.size > 0 -> Collections.min(bigEnough, CompareSizesByArea())
        notBigEnough.size > 0 -> max(notBigEnough, CompareSizesByArea())
        else -> {
          Timber.e("Couldn't find any suitable preview size")
          choices[0]
        }
      }
    }

    private const val ARG_CAMERA_MODE = "ARG_CAMERA_MODE"
    fun newInstance(context: Context, cameraMode: CameraMode): Intent {
      val intent = Intent(context, Camera2Activity::class.java)
      intent.putExtra(ARG_CAMERA_MODE, cameraMode)
      return intent
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_camera2)
    cameraMode = intent.getSerializableExtra(ARG_CAMERA_MODE) as CameraMode
    textureView = findViewById(R.id.texture)

    updateView()
  }

  private fun updateView() {
    backButton.setOnClickListener {
      super.onBackPressed()
    }

    if (pictureBytes != null) {
      cameraButton.visibility = GONE
      galleryButton.visibility = GONE
      flashButton.visibility = GONE

      previewText.visibility = VISIBLE
      ensureImageMessage.visibility = VISIBLE
      retakeButton.visibility = VISIBLE
      sendButton.visibility = VISIBLE

      retakeButton.setOnClickListener {
        onBackPressed()
      }

      sendButton.setOnClickListener {
        sendPictureToFirebase()
      }
    } else {
      previewText.visibility = GONE
      ensureImageMessage.visibility = GONE
      retakeButton.visibility = GONE
      sendButton.visibility = GONE

      cameraButton.visibility = VISIBLE
      galleryButton.visibility = VISIBLE
      flashButton.visibility = VISIBLE

      mainTitle.text = when (cameraMode) {
        CameraMode.ID_FRONT -> getString(R.string.front_of_id)
        CameraMode.ID_BACK -> getString(R.string.back_of_id)
        else -> getString(R.string.selfie_with_id)
      }

      imagePreview.setImageDrawable(null)
      textureView.visibility = VISIBLE

      cameraButton.setOnClickListener {
        if (autoFocusSupported) {
          lockFocus()
        } else {
          captureStillPicture()
        }
      }

      galleryButton.setOnClickListener {
        pickFromGallery()
      }

      setupFlashButton()
    }
  }

  private fun setupFlashButton() {
    if (cameraMode == CameraMode.ID_SELFIE) {
      flashButton.visibility = GONE
    } else {
      flashButton.visibility = VISIBLE
      flashButton.setOnClickListener {
        changeFlashMode()
      }
    }
  }

  private fun changeFlashMode() {
    when (flashMode) {
      FlashMode.OFF -> {
        flashMode = FlashMode.ON
        flashButton.setImageResource(R.drawable.ic_flash_on)
      }
      FlashMode.ON -> {
        flashMode = FlashMode.OFF
        flashButton.setImageResource(R.drawable.ic_flash_off)
      }
    }

    closeCamera()
    openCamera(textureView.width, textureView.height)
  }

  private fun pickFromGallery() {
    //Create an Intent with action as ACTION_PICK
    val intent = Intent(Intent.ACTION_PICK)
    // Sets the isAdded as image/*. This ensures only components of isAdded image are selected
    intent.type = "image/*"
    //We pass an extra array with the accepted mime types. This will ensure only components with these MIME types as targeted.
    val mimeTypes = arrayOf("image/jpeg", "image/png")
    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
    // Launching the Intent
    startActivityForResult(intent, REQUEST_GALLERY)
  }

  private fun sendPictureToFirebase() {
    pictureBytes?.let { bytes ->
      showUploadingView()
      Firebase.uploadBytes(bytes, cameraMode,
        OnSuccessListener {
          onFirebaseResult(it)
        }, OnFailureListener {
        onError(it.localizedMessage, false)
        hideUploadingView()
      })
    }
  }

  private fun onFirebaseResult(uri: Uri?) {
    if (uri != null && uri.toString().isNotEmpty()) {
      setResult(Activity.RESULT_OK, Intent().putExtra("url", uri.toString()))
      finish()
    } else {
      onError("Downloading URL is Null", false)
      hideUploadingView()
    }
  }

  override fun onBackPressed() {
    if (pictureBytes != null) {
      pictureBytes = null
      updateView()
      unlockFocus()
    } else {
      super.onBackPressed()
    }
  }

  private fun getBitesFromUri(uri: Uri?): ByteArray? {
    uri?.let {
      contentResolver.openInputStream(it)?.let { inputStream ->
        val bytes = inputStream.readBytes()
        inputStream.close()
        return bytes
      }
    } ?: return null
  }


  public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    // Result code is RESULT_OK only if the user selects an Image
    if (resultCode == Activity.RESULT_OK)
      when (requestCode) {
        REQUEST_GALLERY -> {
          //data.getData returns the content URI for the selected Image
          val picFromGallery = data?.data
          pictureBytes = getBitesFromUri(picFromGallery)
          imagePreview.setImageURI(picFromGallery)

          textureView.visibility = GONE

          updateView()
        }
      }
  }

  private fun showUploadingView() {
    ensureImageMessage.setText(R.string.uploading_photo)

    ensureImageMessage.visibility = VISIBLE
    progressBar.visibility = VISIBLE

    retakeButton.visibility = GONE
    sendButton.visibility = GONE
  }

  private fun hideUploadingView() {
    ensureImageMessage.setText(R.string.uploading_photo_error)

    progressBar.visibility = GONE

    retakeButton.visibility = VISIBLE
    sendButton.visibility = VISIBLE
  }

  private fun onError(message: String, finish: Boolean) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    if (finish) finish()
  }

  internal class CompareSizesByArea : Comparator<Size> {

    // We cast here to ensure the multiplications won't overflow
    override fun compare(lhs: Size, rhs: Size) =
      signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
  }
}