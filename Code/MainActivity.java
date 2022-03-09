package com.sample.timelapse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.os.StatFs;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements SurfaceHolder.Callback, View.OnClickListener, Camera.PictureCallback,
		Camera.PreviewCallback {
	private MJPEGGenerator generator;

	Timer updateTimer = new Timer(); // Main shoot timer

	private static final int PERIODMIN = 2; // Seconds
	private static final int PERIODMAX = 1000; // Seconds

	private static final int FPSMIN = 2;
	private static final int FPSMAX = 30;

	int aviHeight = 0; // Dimensions of final video
	int aviWidth = 0;

	// Work mode
	// 0: Ready to start
	// 1: Capturing photos
	// 2: Ready to create video
	// 3: Create video
	private int workMode = 0;

	private int capturePeriod = 0;
	private int fps = 0;

	private Camera camera;
	private SurfaceHolder surfaceHolder;
	private SurfaceView preview;

	private static int LOGLEVEL = 2; // Set logging level
	private static boolean DEBUG = LOGLEVEL > 1;
	@SuppressWarnings("unused")
	private static boolean WARNING = LOGLEVEL > 0;

	public static final String PREFS_NAME = "MyPrefsFile"; // For save and restore preferences

	private static final String TAG = "MainActivity"; // Set logging tag

	int lastPicture = 0; // Current picture counter
	int lastVideo = 0; // Current video file counter

	int sWidth = 0; // Screen width
	int sHeight = 0; // Screen height
	int prevsWidth = 1; // Previous screen width (after previous onWindowFocusChanged)
	int prevsHeight = 1; // Previous screen height (after previous onWindowFocusChanged)

	int commentTextBottom = 0;
	int oldLandCommentTextBottom = 0;

	private TextView periodText;
	private TextView framerateText;
	private TextView totalsnapshotsText;

	private Button startButton; // "Start capture"
	private Button createButton; // "Create video"
	int nativeButtonColor = 0;

	private EditText periodEditText; // Period
	private TextView secondsText;

	private EditText fpsEditText; // Frame rate
	private TextView fpsText;

	private TextView modeText; // Show comments

	float roundOneDecimal(float toround) {
		DecimalFormat twoDForm = new DecimalFormat("#.#");
		return Float.valueOf(twoDForm.format(toround));
	}

	static String intToString(int num, int digits) {
		assert digits > 0 : "Invalid number of digits";

		char[] zeros = new char[digits]; // Create variable length array of zeros
		Arrays.fill(zeros, '0');

		DecimalFormat df = new DecimalFormat(String.valueOf(zeros)); // Format number as String

		return df.format(num);
	}

	public boolean isNum(String s) {
		try {
			Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return false;
		}
		return true;
	}

	private PowerManager.WakeLock wl; // Stop screen from dimming by enforcing wake lock

	@Override
	protected void onPause() {
		super.onPause(); // onPause method in the parent class

		if (DEBUG)
			Log.v(TAG, "onPause");

		surfaceHolder.removeCallback(this);
		if (camera != null) {
			camera.setPreviewCallback(null);
			camera.stopPreview();
			camera.release();
			camera = null;
		}

		preview.setVisibility(View.GONE);

		wl.release();
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState); // onCreate method in the parent class

		if (DEBUG)
			Log.v(TAG, "onCreate");

		requestWindowFeature(Window.FEATURE_NO_TITLE); // App without a title
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN); // App without a status bar

		setContentView(R.layout.activity_main); // Set user interface

		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "DoNotDimScreen");

		periodText = (TextView) findViewById(R.id.periodText); // Text fields
		framerateText = (TextView) findViewById(R.id.framerateText);
		totalsnapshotsText = (TextView) findViewById(R.id.totalsnapshotsText);

		startButton = (Button) findViewById(R.id.startButton); // Start capture button
		startButton.setOnClickListener(this);

		createButton = (Button) findViewById(R.id.createButton); // Create video button
		createButton.setOnClickListener(this);
		nativeButtonColor = createButton.getCurrentTextColor();
		createButton.setTextColor(Color.GRAY);

		periodEditText = (EditText) findViewById(R.id.periodEditText); // Period
		secondsText = (TextView) findViewById(R.id.secondsText);

		periodEditText.addTextChangedListener(new TextWatcher() {
			public void afterTextChanged(Editable s) {
				if (periodEditText.getText().toString().length() == 0)
					capturePeriod = 0;
				else {
					if (isNum(periodEditText.getText().toString().replace(',', '.'))) {
						float a = Float.valueOf(periodEditText.getText().toString().replace(',', '.'));
						capturePeriod = (int) a;
					} else
						Toast.makeText(MainActivity.this, periodEditText.getText().toString() + " - not a digit.",
								Toast.LENGTH_LONG).show();
				}
			}

			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}
		});

		fpsEditText = (EditText) findViewById(R.id.fpsEditText); // fps EditText
		fpsText = (TextView) findViewById(R.id.fpsText);

		fpsEditText.setOnFocusChangeListener(new OnFocusChangeListener() {
			public void onFocusChange(View v, boolean hasFocus) {
				if (!hasFocus) {
					// Hide soft keyboard after input
					InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
					imm.hideSoftInputFromWindow(fpsEditText.getWindowToken(), 0);
				}
			}
		});

		fpsEditText.addTextChangedListener(new TextWatcher() {
			public void afterTextChanged(Editable s) {
				if (fpsEditText.getText().toString().length() == 0)
					fps = 0;
				else {
					if (isNum(fpsEditText.getText().toString().replace(',', '.'))) {
						float a = Float.valueOf(fpsEditText.getText().toString().replace(',', '.'));
						fps = (int) a;
					} else
						Toast.makeText(MainActivity.this, fpsEditText.getText().toString() + " - not a digit.",
								Toast.LENGTH_LONG).show();
				}
			}

			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}
		});

		modeText = (TextView) findViewById(R.id.modeText); // Show comments

		SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0); // Restore preferences
		oldLandCommentTextBottom = settings.getInt("oldLandCommentTextBottom", 0);
	}

	@Override
	protected void onResume() {
		super.onResume(); // onResume method in the parent class

		if (DEBUG)
			Log.v(TAG, "onResume");

		preview = (SurfaceView) findViewById(R.id.mSurfaceView);

		if (camera == null) {
			camera = Camera.open();
			camera.startPreview();
		}

		surfaceHolder = preview.getHolder();
		surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		surfaceHolder.setSizeFromLayout();
		surfaceHolder.addCallback(this);

		preview.setVisibility(View.VISIBLE);

		wl.acquire();

		Size previewSize = camera.getParameters().getPreviewSize();
		aviHeight = previewSize.height;
		aviWidth = previewSize.width;

		modeText.setFocusableInTouchMode(true); // Set focus (and hide soft keyboard)
		modeText.requestFocus();
	}

	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		if (DEBUG)
			Log.v(TAG, "surfaceChanged");

		try {
			camera.setPreviewDisplay(surfaceHolder);
		} catch (IOException e) {
			Toast.makeText(MainActivity.this, "Error 1: " + e.toString(), Toast.LENGTH_LONG).show();
		}
		camera.startPreview();
	}

	public void surfaceCreated(SurfaceHolder holder) {
		if (DEBUG)
			Log.v(TAG, "surfaceCreated");

		try {
			camera.setPreviewDisplay(holder);
			camera.setPreviewCallback(this);
		} catch (IOException e) {
			Toast.makeText(MainActivity.this, "Error 2: " + e.toString(), Toast.LENGTH_LONG).show();
			camera.release();
			camera = null;
		}

		Size previewSize = camera.getParameters().getPreviewSize();
		float aspect = (float) previewSize.width / previewSize.height;

		int previewSurfaceWidth = preview.getWidth();

		LayoutParams lp = preview.getLayoutParams();

		// здесь корректируем размер отображаемого preview для ландшафтного вида, чтобы не было искажений
		// camera.setDisplayOrientation(0);
		lp.width = previewSurfaceWidth;
		lp.height = (int) (previewSurfaceWidth / aspect);

		preview.setLayoutParams(lp);
		camera.startPreview();
	}

	public void surfaceDestroyed(SurfaceHolder holder) {
		if (DEBUG)
			Log.v(TAG, "surfaceDestroyed");
	}

	@SuppressLint("NewApi")
	@SuppressWarnings("deprecation")
	void getDisplaySize() {
		try {
			if (Build.VERSION.SDK_INT >= 13) {
				Display display = getWindowManager().getDefaultDisplay();
				Point size = new Point();
				display.getSize(size);
				sWidth = size.x;
				sHeight = size.y;
			} else {
				Display display = getWindowManager().getDefaultDisplay();
				sWidth = display.getWidth();
				sHeight = display.getHeight();
			}
		} catch (Exception e) {
			Toast.makeText(MainActivity.this, "Error 3: " + e.toString(), Toast.LENGTH_LONG).show();
		}
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			getDisplaySize();

			if ((prevsWidth != sWidth) || (prevsHeight != sHeight)) { // If orientation changed
				commentTextBottom = modeText.getTop() + modeText.getHeight(); // Calculate magnification factor

				float heightRatio = 0;

				// Landscape
				heightRatio = (float) sHeight / (float) commentTextBottom;
				oldLandCommentTextBottom = commentTextBottom;

				if (heightRatio > 1)
					heightRatio = 0.7f * heightRatio;
				else
					heightRatio = heightRatio / 0.7f;

				// Adjust fonts
				periodText.setTextSize(TypedValue.COMPLEX_UNIT_PX, heightRatio * periodText.getTextSize());
				periodEditText.setTextSize(TypedValue.COMPLEX_UNIT_PX, heightRatio * periodEditText.getTextSize());
				secondsText.setTextSize(TypedValue.COMPLEX_UNIT_PX, heightRatio * secondsText.getTextSize());
				framerateText.setTextSize(TypedValue.COMPLEX_UNIT_PX, heightRatio * framerateText.getTextSize());
				fpsEditText.setTextSize(TypedValue.COMPLEX_UNIT_PX, heightRatio * fpsEditText.getTextSize());
				fpsText.setTextSize(TypedValue.COMPLEX_UNIT_PX, heightRatio * fpsText.getTextSize());
				totalsnapshotsText.setTextSize(TypedValue.COMPLEX_UNIT_PX, heightRatio * totalsnapshotsText.getTextSize());
				modeText.setTextSize(TypedValue.COMPLEX_UNIT_PX, heightRatio * modeText.getTextSize());

				// Some components have text size a little less
				startButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.8f * heightRatio * startButton.getTextSize());
				createButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.8f * heightRatio * createButton.getTextSize());

				// If user comment string not formed
				if (modeText.getText().equals(getResources().getString(R.string.longestComment)))
					modeText.setText(getString(R.string.modeText));
			}
			prevsWidth = sWidth;
			prevsHeight = sHeight;
		}
	}

	public void onClick(View v) {
		if (v == startButton) {
			if (workMode == 0) {
				if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED))
					Toast.makeText(MainActivity.this, "Please mount SD card", Toast.LENGTH_LONG).show();
				else if ((capturePeriod < PERIODMIN) || (capturePeriod > PERIODMAX))
					Toast.makeText(MainActivity.this,
							"Snapshots period should be " + PERIODMIN + " to " + PERIODMAX + " seconds", Toast.LENGTH_LONG)
							.show();
				else if ((fps < FPSMIN) || (fps > FPSMAX))
					Toast.makeText(MainActivity.this, "FPS should be " + FPSMIN + " to " + FPSMAX + " frames per second",
							Toast.LENGTH_LONG).show();
				else {
					if (updateTimer != null)
						updateTimer.cancel();

					try {
						updateTimer = new Timer();

						updateTimer.scheduleAtFixedRate(new TimerTask() {
							public void run() {
								if ((camera != null) && (workMode == 1)) {
									camera.takePicture(null, null, null, MainActivity.this);
								}
							}
						}, 0, capturePeriod * 1000);
					} catch (Exception e) {
						Toast.makeText(MainActivity.this, "Error 4: " + e.toString(), Toast.LENGTH_LONG).show();
					}

					// Delete all jpg's
					try {
						String sdPath = Environment.getExternalStorageDirectory().getPath() + "/TimeLapseFolder/";
						if (DEBUG)
							Log.v(TAG, "Delete jpg's sdPath = " + sdPath);

						File saveDir = new File(sdPath);

						if (saveDir.isDirectory()) {
							String[] children = saveDir.list();
							for (int i = 0; i < children.length; i++) {
								if (children[i].endsWith(".jpg"))
									new File(saveDir, children[i]).delete();
							}
						}
						saveDir.delete();
					} catch (Exception e) {
						Toast.makeText(MainActivity.this, "Error 5: " + e.toString(), Toast.LENGTH_LONG).show();
					}

					lastPicture = 0;
					workMode = 1;
					startButton.setText("Stop capture");
					modeText.setText("Work mode: capturing");
					totalsnapshotsText.setText("Total snapshots: " + String.valueOf(lastPicture));
				}
			} else if (workMode == 1) {
				workMode = 2;
				createButton.setTextColor(nativeButtonColor);
				startButton.setText("Start capture");
				startButton.setTextColor(Color.GRAY);
				modeText.setText("Work mode: ready to start");
			}
		}

		if (v == createButton) {
			if (workMode == 2) {
				if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED))
					Toast.makeText(MainActivity.this, "Please mount SD card", Toast.LENGTH_LONG).show();
				else if ((capturePeriod < PERIODMIN) || (capturePeriod > PERIODMAX))
					Toast.makeText(MainActivity.this,
							"Snapshots period should be " + PERIODMIN + " to " + PERIODMAX + " seconds", Toast.LENGTH_LONG)
							.show();
				else if ((fps < FPSMIN) || (fps > FPSMAX))
					Toast.makeText(MainActivity.this, "FPS should be " + FPSMIN + " to " + FPSMAX + " frames per second",
							Toast.LENGTH_LONG).show();
				else {
					workMode = 3;
					createButton.setTextColor(Color.GRAY);
					startButton.setTextColor(Color.GRAY);
					modeText.setText("Work mode: create video file, please wait");
					new CreateMovieInBackground().execute();
				}
			}
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState); // onSaveInstanceState method in the parent class

		if (DEBUG)
			Log.v(TAG, "onSaveInstanceState");

		SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
		SharedPreferences.Editor editor = settings.edit();
		editor.putInt("oldLandCommentTextBottom", oldLandCommentTextBottom);
		editor.commit();
	}

	@Override
	protected void onRestoreInstanceState(Bundle savedInstanceState) {
		super.onRestoreInstanceState(savedInstanceState); // onRestoreInstanceState method in the parent class

		if (DEBUG)
			Log.v(TAG, "onRestoreInstanceState");
	}

	public void onPictureTaken(byte[] paramArrayOfByte, Camera paramCamera) {
		new SaveInBackground().execute(paramArrayOfByte);

		if (DEBUG)
			Log.v(TAG, "onPictureTaken");

		// после того, как снимок сделан, показ превью отключается. необходимо включить его
		paramCamera.startPreview();

		totalsnapshotsText.setText("Total snapshots: " + String.valueOf(lastPicture));

		StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());

		long bytesAvailable = (long) stat.getBlockSize() * (long) stat.getAvailableBlocks();
		float megAvailable = bytesAvailable / (1024.f * 1024.f);

		modeText.setText("Work mode: capturing, " + String.valueOf(roundOneDecimal(megAvailable))
				+ " Mbyte available on SD card");
	}

	class SaveInBackground extends AsyncTask<byte[], String, String> {
		@Override
		protected String doInBackground(byte[]... arrayOfByte) {
			try {
				String sdPath = Environment.getExternalStorageDirectory().getPath() + "/TimeLapseFolder/";
				File saveDir = new File(sdPath);

				if (!saveDir.exists())
					saveDir.mkdirs();

				lastPicture++;

				String numWithZeroes = intToString(lastPicture, 7);
				String curjpg = sdPath + numWithZeroes + ".jpg";

				if (DEBUG)
					Log.v(TAG, "Save jpg sdPath = " + curjpg);

				FileOutputStream os = new FileOutputStream(curjpg);
				os.write(arrayOfByte[0]);
				os.close();
			} catch (Exception e) {
				Toast.makeText(MainActivity.this, "Error 6: " + e.toString(), Toast.LENGTH_LONG).show();
			}
			return (null);
		}
	}

	class CreateMovieInBackground extends AsyncTask<byte[], String, String> {

		protected void onProgressUpdate(String... values) {
			modeText.setText("Work mode: rendering " + values[0] + ".jpg");
		}

		protected void onPostExecute(String result) {
			workMode = 0;
			totalsnapshotsText.setText("Total snapshots: 0");
			lastPicture = 0;

			String sdPath = Environment.getExternalStorageDirectory().getPath() + "/TimeLapseFolder/";

			modeText.setText("Work mode:" + sdPath + "TimeLapseMovie" + intToString(lastVideo, 3) + ".avi is rendered");
			Handler handler = new Handler();
			handler.postDelayed(new Runnable() {
				public void run() {
					modeText.setText("Work mode: ready to start");
					startButton.setTextColor(nativeButtonColor);
				}
			}, 5000);
		}

		@Override
		protected String doInBackground(byte[]... arrayOfByte) {
			try {

				File videofile = null;

				String sdPath = Environment.getExternalStorageDirectory().getPath() + "/TimeLapseFolder/";

				// Choosing a name for the file
				do {
					lastVideo++;
					String curavi = sdPath + "TimeLapseMovie" + intToString(lastVideo, 3) + ".avi";

					if (DEBUG)
						Log.v(TAG, "AVI name = " + curavi);

					videofile = new File(curavi);
				} while (videofile.exists());

				generator = new MJPEGGenerator(videofile, aviWidth, aviHeight, fps, lastPicture);

				for (int addpic = 1; addpic <= lastPicture; addpic++) {
					String numWithZeroes = intToString(addpic, 7);
					String curjpg = sdPath + numWithZeroes + ".jpg";

					publishProgress(numWithZeroes);

					if (DEBUG)
						Log.v(TAG, "Rendering jpg sdPath = " + curjpg);

					Bitmap bmp = BitmapFactory.decodeFile(curjpg);
					generator.addImage(bmp);
				}

				// Delete all jpg's
				try {
					if (DEBUG)
						Log.v(TAG, "Delete jpg's sdPath = " + sdPath);

					File saveDir = new File(sdPath);

					if (saveDir.isDirectory()) {
						String[] children = saveDir.list();
						for (int i = 0; i < children.length; i++) {
							if (children[i].endsWith(".jpg"))
								new File(saveDir, children[i]).delete();
						}
					}
					saveDir.delete();
				} catch (Exception e) {
					Toast.makeText(MainActivity.this, "Error 7: " + e.toString(), Toast.LENGTH_LONG).show();
				}

				generator.finishAVI();

			} catch (Exception e) {
				Toast.makeText(MainActivity.this, "Error 8: " + e.toString(), Toast.LENGTH_LONG).show();
			}
			return "OK";
		}
	}

	public void onPreviewFrame(byte[] paramArrayOfByte, Camera paramCamera) {
	}
}