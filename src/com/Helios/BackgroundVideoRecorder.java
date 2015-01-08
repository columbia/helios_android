package com.Helios;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.location.Location;
import android.media.AudioManager;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.Toast;

import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3Client;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.location.LocationServices;

public class BackgroundVideoRecorder extends Service implements
		SurfaceHolder.Callback, MediaRecorder.OnInfoListener,
		ConnectionCallbacks, OnConnectionFailedListener {

	private WindowManager windowManager;
	private SurfaceView surfaceView;
	private Camera camera = null;
	private MediaRecorder mediaRecorder = null;
	private SurfaceHolder surfHolder = null;

	// flag to control if we use mobile data to upload
	private boolean WifiUploadOnly = true;
	private static boolean RECORD_AUDIO = false;

	private int NOTIFICATION_ID = 111;
	private final String TAG = "Helios_" + getClass().getSimpleName();

	public static final String REQUEST_TYPE = "REQUEST_TYPE";
	public static final String REQUEST_TYPE_STOP = "STOP_RECORDING";
	public static final String REQUEST_TYPE_START = "START_RECORDING";
	public static final String REQUEST_TYPE_PAUSE = "PAUSE_RECORDING";
	public static final String REQUEST_TYPE_PLAY = "RESTART_RECORDING";

	private String mEmail;
	private String token;
	private String outputFile;

	private Timer uploadTimer;
	private boolean VIDEO_RECORDER_ON = false;

	private AudioManager audioManager;
	private int[] audioStreams;
	private int[] audioStreamVolumes;

	// used for location services using new Location API
	private GoogleApiClient mGoogleApiClient;
	private Location mLocation;

	// used to log in to Amazon Web Services and create client object
	// to upload to S3
	CognitoCachingCredentialsProvider cognitoProvider;
	Map<String, String> logins = new HashMap<String, String>();
	AmazonS3Client s3Client;

	@Override
	public void onCreate() {

		// Create new SurfaceView, set its size to 1x1, move it to the top left
		// corner and set this service as a callback
		windowManager = (WindowManager) this
				.getSystemService(Context.WINDOW_SERVICE);
		surfaceView = new SurfaceView(this);
		LayoutParams layoutParams = new WindowManager.LayoutParams(1, 1,
				WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
				WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
				PixelFormat.TRANSLUCENT);
		layoutParams.gravity = Gravity.LEFT | Gravity.TOP;
		windowManager.addView(surfaceView, layoutParams);
		surfaceView.getHolder().addCallback(this);

		mGoogleApiClient = new GoogleApiClient.Builder(this)
				.addConnectionCallbacks(this)
				.addOnConnectionFailedListener(this)
				.addApi(LocationServices.API).build();

		getAudioManagerStreams();

	}

	public int onStartCommand(Intent intent, int flags, int startID) {
		// gets email and access token from calling activity
		mEmail = intent.getStringExtra(LoginActivity.EMAIL_MSG);
		token = intent.getStringExtra(LoginActivity.TOKEN_MSG);
		String requestType = intent.getStringExtra(REQUEST_TYPE);

		Log.v(TAG, "onStartCommand received request " + requestType);

		if (requestType.equals(REQUEST_TYPE_START)) {
			Log.i(TAG, "Started service for user " + mEmail);
			createStopPauseNotification();
			// this is called only on the first creation of this class
			// so onSurfaceCreated starts the video recording
			startUploadTimer(Config.UPLOAD_INTERVAL);
			mGoogleApiClient.connect();

			doCognitoLogin();
		}

		if (requestType.equals(REQUEST_TYPE_PAUSE)) {
			Log.i(TAG, "Paused recording for user " + mEmail);
			stopMediaRecorder();
			createStopPlayNotification();
			uploadVideo();
		}

		if (requestType.equals(REQUEST_TYPE_PLAY)) {
			Log.i(TAG, "Restarted recording for user " + mEmail);
			createStopPauseNotification();
			startRecordingVideo(surfHolder);
			startUploadTimer(Config.UPLOAD_INTERVAL);
		}

		if (requestType.equals(REQUEST_TYPE_STOP)) {
			Log.i(TAG, "Stopped service for user " + mEmail);
			// cancel timer and upload video
			stopMediaRecorder();
			uploadVideo();
			stopSelf();
		}

		return START_STICKY;
	}

	private void doCognitoLogin() {
		// log in with Cognito identity provider
		cognitoProvider = new CognitoCachingCredentialsProvider(this,
				Config.ACCOUNT_ID, Config.IDENTITY_POOL, Config.UNAUTH_ROLE_ARN,
				Config.AUTH_ROLE_ARN, Config.COGNITO_REGION);
		
		logins.put("accounts.google.com", token);
		cognitoProvider.withLogins(logins);
		cognitoProvider.getIdentityId();
		Log.i(TAG,
				"Got identity ID " + cognitoProvider.getCachedIdentityId());
		s3Client = new AmazonS3Client(cognitoProvider);
		Log.i(TAG, "Successfully initialized S3 client");
	}

	/**
	 * 
	 */
	private void uploadVideo() {
		if (mGoogleApiClient.isConnected())
			mLocation = LocationServices.FusedLocationApi
					.getLastLocation(mGoogleApiClient);
		else
			mLocation = null;
		new ImageUploader(this, new File(outputFile), s3Client, mLocation,
				cognitoProvider.getCachedIdentityId(), WifiUploadOnly).execute();
	}

	private void createStopPauseNotification() {

		PendingIntent stopIntent = PendingIntent
				.getService(this, 0, getIntent(REQUEST_TYPE_STOP),
						PendingIntent.FLAG_CANCEL_CURRENT);
		PendingIntent pauseIntent = PendingIntent.getService(this, 1,
				getIntent(REQUEST_TYPE_PAUSE),
				PendingIntent.FLAG_CANCEL_CURRENT);

		// Start foreground service to avoid unexpected kill
		Notification notification = new Notification.Builder(this)
				.setContentTitle("Helios Background Video Recorder")
				.setContentText("").setSmallIcon(R.drawable.eye)
				.addAction(R.drawable.pause, "Pause", pauseIntent)
				.addAction(R.drawable.stop, "Stop", stopIntent).build();
		startForeground(NOTIFICATION_ID, notification);
	}

	private void createStopPlayNotification() {

		PendingIntent stopIntent = PendingIntent
				.getService(this, 0, getIntent(REQUEST_TYPE_STOP),
						PendingIntent.FLAG_CANCEL_CURRENT);
		PendingIntent playIntent = PendingIntent
				.getService(this, 2, getIntent(REQUEST_TYPE_PLAY),
						PendingIntent.FLAG_CANCEL_CURRENT);

		// Start foreground service to avoid unexpected kill
		Notification notification = new Notification.Builder(this)
				.setContentTitle("Helios Background Video Recorder")
				.setContentText("").setSmallIcon(R.drawable.eye)
				.addAction(R.drawable.play, "Play", playIntent)
				.addAction(R.drawable.stop, "Stop", stopIntent).build();
		startForeground(NOTIFICATION_ID, notification);
	}

	private Intent getIntent(String requestType) {
		Intent intent = new Intent(this, BackgroundVideoRecorder.class);
		intent.putExtra(REQUEST_TYPE, requestType);
		intent.putExtra(LoginActivity.TOKEN_MSG, token);
		intent.putExtra(LoginActivity.EMAIL_MSG, mEmail);

		return intent;

	}

	// Method called right after Surface created (initializing and starting
	// MediaRecorder)
	@Override
	public void surfaceCreated(SurfaceHolder surfaceHolder) {
		surfHolder = surfaceHolder;
		startRecordingVideo(surfHolder);
	}

	private void startRecordingVideo(SurfaceHolder surfaceHolder) {
		String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss")
				.format(new java.util.Date().getTime());

		outputFile = Environment
				.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
				+ File.separator + "Helios_" + timeStamp + ".mp4";

		camera = getCameraInstance();
		if (camera == null) {
			Toast.makeText(this, "Camera unavailable or in use",
					Toast.LENGTH_LONG).show();
			createStopPlayNotification();
			stopMediaRecorder();
			return;
		}
		mediaRecorder = new MediaRecorder();
		camera.unlock();

		mediaRecorder.setCamera(camera);
		mediaRecorder.setOrientationHint(90);
		CamcorderProfile profile = getValidCamcorderProfile();

		if (RECORD_AUDIO) {
			mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
			mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

			mediaRecorder.setProfile(profile);
		} else {
			int targetFrameRate = 15;

			mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
			mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
			mediaRecorder.setVideoFrameRate(targetFrameRate);
			mediaRecorder.setVideoSize(profile.videoFrameWidth,
					profile.videoFrameHeight);
			mediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
			mediaRecorder.setVideoEncoder(profile.videoCodec);
		}

		mediaRecorder.setOutputFile(outputFile);
		Log.d(TAG, "Saving file to " + outputFile);
		mediaRecorder.setPreviewDisplay(surfaceHolder.getSurface());
		mediaRecorder.setMaxDuration(-1);
		mediaRecorder.setMaxFileSize(Config.MAX_VIDEO_FILE_SIZE);
		mediaRecorder.setOnInfoListener(this);

		try {
			mediaRecorder.prepare();
			startMediaRecorder();
			VIDEO_RECORDER_ON = true;
		} catch (IllegalStateException e) {
			Log.d(TAG,
					"IllegalStateException preparing MediaRecorder: "
							+ e.getMessage());
			stopMediaRecorder();
		} catch (IOException e) {
			Log.d(TAG, "IOException preparing MediaRecorder: " + e.getMessage());
			stopMediaRecorder();
		}
	}

	// Stop recording and remove SurfaceView
	@Override
	public void onDestroy() {

		Log.v(TAG, "BackgroundVideoRecorder Service is being destroyed");
		stopMediaRecorder();
		windowManager.removeView(surfaceView);
		if (mGoogleApiClient.isConnected())
			mGoogleApiClient.disconnect();
	}

	private void startMediaRecorder() {
		setMuteAll(); // mute beep from MediaRecorder starting

		mediaRecorder.start();
		/*
		 * TODO: below is there because the unMute gets executed before the
		 * MediaRecorder has started in its thread so we still hear the beep Bad
		 * practice to go to sleep on the main thread but we do it for now to
		 * avoid the constant beeping when MediaRecorder starts
		 */
		try {
			Thread.sleep(250);
		} catch (InterruptedException ie) {
		}
		unMuteAll();
	}

	private void stopMediaRecorder() {
		setMuteAll(); // mute beep from MediaRecorder stopping

		uploadTimer.cancel();
		try {
			if (VIDEO_RECORDER_ON) {
				mediaRecorder.stop();
				Log.v(TAG, "Stopped media recorder");
				mediaRecorder.release();
				camera.lock();
				camera.release();
				VIDEO_RECORDER_ON = false;
				Log.v(TAG, "Released camera and media recorder");
			}
		} catch (RuntimeException e) {
			// do nothing - happens if user pushed pause or stop button when
			// recording
			// was already stopped
		}
		unMuteAll(); // return volume to normal
	}

	@Override
	public void surfaceChanged(SurfaceHolder surfaceHolder, int format,
			int width, int height) {
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private Camera getCameraInstance() {
		Camera c = null;
		try {
			c = Camera.open(); // attempt to get a Camera instance
		} catch (Exception e) {
			// Camera is not available (in use or does not exist)
			Log.d(TAG, "Error opening camera");
		}
		return c; // returns null if camera is unavailable
	}

	private void startUploadTimer(long interval) {
		// set up timer to upload video every <interval> milliseconds
		// this is so that we don't breach file size limits on Picasa
		uploadTimer = new Timer();
		uploadTimer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				// first stop recording
				stopMediaRecorder();
				// releaseMediaRecorder();
				// then upload video in background AsyncTask
				uploadVideo();
				// now reinitialize MediaRecorder and Camera and start recording
				// video again
				startRecordingVideo(surfHolder);
			}
		}, interval, interval);
	}

	private CamcorderProfile getValidCamcorderProfile() {
		CamcorderProfile profile;

		if (CamcorderProfile
				.hasProfile(CamcorderProfile.QUALITY_TIME_LAPSE_720P)) {
			profile = CamcorderProfile
					.get(CamcorderProfile.QUALITY_TIME_LAPSE_720P);
			return profile;
		}

		if (CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_720P))
			profile = CamcorderProfile.get(CamcorderProfile.QUALITY_720P);
		else
			profile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);

		return profile;
	}

	public void onInfo(MediaRecorder mr, int what, int extra) {
		// called by MediaRecorder if we go over max file size
		if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
			Log.d(TAG, "MediaRecorder hit max file size");

			stopMediaRecorder();
			uploadVideo();

			startRecordingVideo(surfHolder);
			startUploadTimer(Config.UPLOAD_INTERVAL);
		} else { // log the event
			Log.d(TAG, " MediaRecorder.onInfo called with " + what + " extra "
					+ extra);
		}
	}

	// helper functions to mute and unmute volume
	// this prevents the constant beeping when MediaRecorder starts and stops

	private void getAudioManagerStreams() {
		// different versions of Android use different streams
		// so we have to mute them all
		audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		audioStreams = new int[] { AudioManager.STREAM_ALARM,
				AudioManager.STREAM_DTMF, AudioManager.STREAM_MUSIC,
				AudioManager.STREAM_RING, AudioManager.STREAM_SYSTEM,
				AudioManager.STREAM_VOICE_CALL };

		int numStreams = audioStreams.length;
		audioStreamVolumes = new int[numStreams];
		for (int r = 0; r < numStreams; r++) {
			audioStreamVolumes[r] = audioManager
					.getStreamVolume(audioStreams[r]);
			Log.v(TAG + "_getStreams", "Volume for stream " + r + " is "
					+ audioStreamVolumes[r]);
		}

	}

	private void setMuteAll() {

		Log.v(TAG, "Muting sounds");
		int numStreams = audioStreams.length;
		for (int r = 0; r < numStreams; r++) {
			audioManager.setStreamVolume(audioStreams[r], 0, 0);
		}
	}

	private void unMuteAll() {

		Log.v(TAG, "Re-enabling sounds");
		int numStreams = audioStreams.length;
		for (int r = 0; r < numStreams; r++) {
			Log.v(TAG + "_unMute", "resetting volume for stream " + r + " to "
					+ audioStreamVolumes[r]);
			audioManager.setStreamVolume(audioStreams[r],
					audioStreamVolumes[r], 0);
		}
	}

	/*
	 * Callback methods for Google Location Services
	 */

	@Override
	public void onConnected(Bundle connectionHint) {
		Log.v(TAG, "Location services connected");
	}

	/*
	 * Called by Google Play services if the connection to GoogleApiClient drops
	 * because of an error.
	 */
	public void onDisconnected() {
		Log.v(TAG, "Disconnected. Location information no longer available.");
	}

	@Override
	public void onConnectionSuspended(int cause) {
		// The connection to Google Play services was lost for some reason. We
		// call connect() to
		// attempt to re-establish the connection.
		Log.i(TAG, "Location services connection suspended");
		mGoogleApiClient.connect();
	}

	@Override
	public void onConnectionFailed(ConnectionResult connectionResult) {
		/*
		 * we simply notify user that Locations will not be recorded
		 */
		Log.e(TAG, "Error when connecting to Location Services "
				+ connectionResult.getErrorCode()
				+ " Location services not available");
		Toast.makeText(this, "Location services not available",
				Toast.LENGTH_LONG).show();
	}

}
