package edu.uml.cs.isense.datawalk_v2;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Criteria;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.GpsStatus.Listener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import edu.uml.cs.isense.comm.API;
import edu.uml.cs.isense.datawalk_v2.dialogs.DataRateDialog;
import edu.uml.cs.isense.datawalk_v2.dialogs.ForceStop;
import edu.uml.cs.isense.datawalk_v2.dialogs.NoGps;
import edu.uml.cs.isense.datawalk_v2.dialogs.ViewData;
import edu.uml.cs.isense.objects.RProject;
import edu.uml.cs.isense.proj.Setup;
import edu.uml.cs.isense.queue.QDataSet;
import edu.uml.cs.isense.queue.QueueLayout;
import edu.uml.cs.isense.queue.UploadQueue;
import edu.uml.cs.isense.supplements.ObscuredSharedPreferences;
import edu.uml.cs.isense.supplements.OrientationManager;
import edu.uml.cs.isense.waffle.Waffle;

/**
 * Behaves as the main driver for the iSENSE Data Walk App. Coordinates and
 * records geolocation data.
 * 
 * @author Rajia
 */
public class DataWalk extends Activity implements LocationListener,
		SensorEventListener, Listener {

	/* UI Related Globals */
	private TextView loggedInAs;
	private TextView nameTxtBox;
	private TextView timeElapsedBox;
	private TextView pointsUploadedBox;
	private TextView expNumBox;
	private TextView rateBox;
	private TextView latLong;
	private Button startStop;

	/* Manager Controlling Globals */
	private LocationManager mLocationManager;
	private Vibrator vibrator;
	private MediaPlayer mMediaPlayer;
	private API api;
	private UploadQueue uq;
	private SensorManager mSensorManager;
	private Location loc;
	private Timer recordTimer;
	private Timer gpsTimer;
	private Waffle w;

	/* iSENSE API Globals and Constants */
	private final String DEFAULT_USERNAME = "mobile";
	private final String DEFAULT_PASSWORD = "mobile";
	private final String DEFAULT_PROJECT = "13";

	private String loginName = "";
	private String loginPass = "";
	private String projectID = "13";
	private String projectURL = "";
	private String dataSetName = "";
	private String baseprojectURL = "http://rsense-dev.cs.uml.edu/projects/";
	private int dataSetID = -1;
	public static final String USERNAME_KEY = "username";
	public static final String PASSWORD_KEY = "password";

	/* Manage Work Flow Between Activities */
	public static Context mContext;
	public static String firstName = "";
	public static String lastInitial = "";

	/* Manage Work Flow Within DataWalk.java */
	private boolean running = false;
	private boolean gpsWorking = false;
	private boolean useMenu = true;

	/* Recording Globals */
	private float accel[];
	private JSONArray dataSet;

	/* Dialog Identity Constants */
	private final int DIALOG_VIEW_DATA = 2;
	private final int DIALOG_NO_GPS = 3;
	private final int DIALOG_FORCE_STOP = 4;
	private final int QUEUE_UPLOAD_REQUESTED = 5;
	private final int RESET_REQUESTED = 6;
	private final int NAME_REQUESTED = 7;
	private final int EXPERIMENT_REQUESTED = 8;

	/* Timer Related Globals and Constants */
	private final int TIMER_LOOP = 1000;
	private final int DEFAULT_INTERVAL = 10000;
	private int mInterval = DEFAULT_INTERVAL;
	private int elapsedMillis = 0;
	private int dataPointCount = 0;
	private int timerTick = 0;
	private int waitingCounter = 0;

	/**
	 * Called when the application is created for the first time.
	 */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		// Save current context
		mContext = this;

		// Initialize all the managers.
		initManagers();

		// Gets first name and last initial the first time
		if (firstName.equals("") || lastInitial.equals("")) {
			startActivityForResult(
					new Intent(mContext, EnterNameActivity.class),
					NAME_REQUESTED);
		}

		// Set the initial default projectID in preferences
		SharedPreferences mPrefs = getSharedPreferences("PROJID", 0);
		SharedPreferences.Editor mEdit = mPrefs.edit();
		mEdit.putString("project_id", DEFAULT_PROJECT).commit();

		// Initialize main UI elements
		startStop = (Button) findViewById(R.id.startStop);
		timeElapsedBox = (TextView) findViewById(R.id.timeElapsed);
		pointsUploadedBox = (TextView) findViewById(R.id.pointCount);
		expNumBox = (TextView) findViewById(R.id.expNumBx);
		loggedInAs = (TextView) findViewById(R.id.loginStatus);
		nameTxtBox = (TextView) findViewById(R.id.NameStatus);
		rateBox = (TextView) findViewById(R.id.RateBx);
		latLong = (TextView) findViewById(R.id.myLocation);
		pointsUploadedBox.setText("Points Recorded: " + dataPointCount);
		timeElapsedBox.setText("Time Elapsed: " + timerTick + " seconds");

		// Attempt to login with saved credentials, otherwise try default
		// credentials
		new AttemptLoginTask().execute();

		/* Starts the code for the main button. */
		startStop.setOnLongClickListener(new OnLongClickListener() {

			@Override
			public boolean onLongClick(View arg0) {

				// Vibrate and beep
				vibrator.vibrate(300);
				mMediaPlayer.setLooping(false);
				mMediaPlayer.start();

				// Handles when you press the button to STOP recording
				if (running) {

					// No longer recording so set menu flag to enabled
					running = false;
					useMenu = true;

					// Reset the text on the main button
					startStop.setText(getString(R.string.startPrompt));

					// Cancel the recording timer
					recordTimer.cancel();

					// Tell the user recording has stopped
					w.make("Finished recording data! Click on Upload to publish data to iSENSE.",
							Waffle.LENGTH_LONG, Waffle.IMAGE_CHECK);

					// Create the name of the session using the entered name and
					// the current time
					SimpleDateFormat sdf = new SimpleDateFormat(
							"MM/dd/yyyy, HH:mm:ss", Locale.US);
					Date dt = new Date();
					String dateString = sdf.format(dt);
					dataSetName = firstName + " " + lastInitial + ". - "
							+ dateString;

					// Get user's project #, or the default if there is none
					// saved
					SharedPreferences prefs = getSharedPreferences("PROJID", 0);
					projectID = prefs.getString("project_id", DEFAULT_PROJECT);

					// Set the project URL for view data
					projectURL = baseprojectURL + projectID + "/data_sets/";

					// Save the newest DataSet to the Upload Queue if it has at
					// least 1 point
					QDataSet ds = new QDataSet(QDataSet.Type.DATA, dataSetName,
							"Data Points: " + dataPointCount, projectID,
							dataSet.toString(), null);
					if (dataPointCount > 0)
						uq.addDataSetToQueue(ds);
					else {
						w.make("Data not saved because no points were recorded.",
								Waffle.LENGTH_LONG, Waffle.IMAGE_X);
					}

					// Re-enable rotation in the main activity
					OrientationManager.enableRotation(DataWalk.this);

					// Handles when you press the button to START recording
				} else {

					// Recording so set menu flag to disabled
					useMenu = false;
					running = true;

					// Reset the main UI text boxes
					pointsUploadedBox.setText("Points Recorded: " + "0");
					timeElapsedBox.setText("Time Elapsed:" + " 0 seconds");

					// Reset the number of data points and the current dataSet
					// ID
					dataPointCount = 0;
					dataSetID = -1;

					// Prevent the screen from turning off and prevent rotation
					getWindow().addFlags(
							WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
					OrientationManager.disableRotation(DataWalk.this);

					// Record and update the UI as necessary
					runRecordingTimer();

					// Change the text on the main button
					startStop.setText(getString(R.string.stopPrompt));

				}

				return running;

			}

		});

	}// ends onCreate

	/**
	 * Is called every time this activity is paused. For example, whenever a new
	 * dialog is launched.
	 */
	@Override
	public void onPause() {
		super.onPause();

		// Stop recording
		if (recordTimer != null)
			recordTimer.cancel();
		recordTimer = null;

		// Stop updating GPS
		if (gpsTimer != null)
			gpsTimer.cancel();
		gpsTimer = null;
	}

	/**
	 * Called whenever this application is left, like when the user switches
	 * apps using the task manager.
	 */
	@Override
	public void onStop() {
		super.onStop();

		// Stops the GPS and accelerometer when the application is not
		// recording.
		if (!running) {
			if (mLocationManager != null)
				mLocationManager.removeUpdates(DataWalk.this);

			if (mSensorManager != null)
				mSensorManager.unregisterListener(DataWalk.this);
		}
	}

	/**
	 * Called whenever this activity is called from within the application, like
	 * from the login dialog.
	 */
	@Override
	public void onResume() {
		super.onResume();

		// Get the last know recording interval
		mInterval = Integer.parseInt(getSharedPreferences("RecordingPrefs", 0)
				.getString("DataUploadRate", "10000"));

		// Rebuild the upload queue
		if (uq != null)
			uq.buildQueueFromFile();

		// Check to see if the recording was canceled while running
		if (running) {
			Intent i = new Intent(DataWalk.this, ForceStop.class);
			startActivityForResult(i, DIALOG_FORCE_STOP);
		}

		// Restart the GPS counter
		if (mLocationManager != null)
			initLocationManager();
		if (gpsTimer == null)
			waitingForGPS();

		// Update the text in the text boxes on the main UI
		expNumBox.setText("Project Number: " + projectID);
		if (mInterval == 1000) {
			rateBox.setText("Data Recorded Every: 1 second");
		} else if (mInterval == 60000) {
			rateBox.setText("Data Recorded Every: 1 Minute");
		} else {
			rateBox.setText("Data Recorded Every: " + mInterval / 1000
					+ " seconds");
		}

	}// ends onResume

	/**
	 * Handles application behavior on back press.
	 */
	@Override
	public void onBackPressed() {
		// Allows there user to leave via back only when not recording.
		if (running) {
			w.make("Cannot exit via BACK while recording data; use HOME instead.",
					Waffle.LENGTH_LONG, Waffle.IMAGE_WARN);
		} else {
			super.onBackPressed();
		}
	}

	/**
	 * Receives location updates from the location manager.
	 */
	@Override
	public void onLocationChanged(Location location) {
		if (location.getLatitude() != 0 && location.getLongitude() != 0) {
			loc = location;
			gpsWorking = true;
		} else {
			gpsWorking = false;
		}
	}

	/**
	 * Location manager not receiving updates anymore.
	 */
	@Override
	public void onProviderDisabled(String provider) {
		gpsWorking = false;
	}

	/**
	 * Location manager starts receiving updates.
	 */
	@Override
	public void onProviderEnabled(String provider) {
	}

	/**
	 * Called when the provider status changes.
	 */
	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {

	}

	/**
	 * Used to find out what version of Android you are using.
	 * 
	 * @return API level of current device
	 */
	public static int getApiLevel() {
		return android.os.Build.VERSION.SDK_INT;
	}

	/**
	 * Turns the action bar menu on and off.
	 * 
	 * @return Whether or not the menu was prepared successfully.
	 */
	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		if (useMenu) {
			menu.getItem(0).setEnabled(true);
			menu.getItem(1).setEnabled(true);
		} else {
			menu.getItem(0).setEnabled(false);
			menu.getItem(1).setEnabled(false);
		}
		return true;
	}

	/**
	 * Tries to login then launches the upload queue uploading activity.
	 */
	private void manageUploadQueue() {

		// Attempt to login with saved credentials, otherwise try default
		// credentials
		new AttemptLoginTask().execute();

		// If the queue isn't empty, launch the activity. Otherwise tell the
		// user the queue is empty.
		if (!uq.emptyQueue()) {
			Intent i = new Intent().setClass(mContext, QueueLayout.class);
			i.putExtra(QueueLayout.PARENT_NAME, uq.getParentName());
			startActivityForResult(i, QUEUE_UPLOAD_REQUESTED);
		} else {
			w.make("No Data to Upload.", Waffle.LENGTH_LONG, Waffle.IMAGE_WARN);
		}
	}

	/**
	 * Sets up the locations manager so that it request GPS permission if
	 * necessary and gets only the most accurate points.
	 */
	private void initLocationManager() {

		// Set the criteria to points with fine accuracy
		Criteria criteria = new Criteria();
		criteria.setAccuracy(Criteria.ACCURACY_FINE);

		// Start the GPS listener
		mLocationManager.addGpsStatusListener(this);

		// Check if GPS is enabled. If not, direct user to their settings.
		if (mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
			mLocationManager.requestLocationUpdates(
					mLocationManager.getBestProvider(criteria, true), 0, 0,
					DataWalk.this);
		else {
			Intent i = new Intent(DataWalk.this, NoGps.class);
			startActivityForResult(i, DIALOG_NO_GPS);
		}

		// Save new GPS points in our loc variable
		loc = new Location(mLocationManager.getBestProvider(criteria, true));
	}

	/**
	 * Starts a timer that displays gps points when they are found and the
	 * waiting for gps loop when they are not.
	 */
	private void waitingForGPS() {

		// Creates the new timer to update the main UI every second
		gpsTimer = new Timer();
		gpsTimer.scheduleAtFixedRate(new TimerTask() {

			@Override
			public void run() {
				runOnUiThread(new Runnable() {

					@Override
					public void run() {

						// Show the GPS coordinate on the main UI, else continue
						// with our loop.
						if (gpsWorking) {
							latLong.setText("Latitude: " + loc.getLatitude()
									+ "\nLongitude: " + loc.getLongitude());
						} else {
							switch (waitingCounter % 5) {
							case (0):
								latLong.setText(R.string.noLocation0);
								break;
							case (1):
								latLong.setText(R.string.noLocation1);
								break;
							case (2):
								latLong.setText(R.string.noLocation2);
								break;
							case (3):
								latLong.setText(R.string.noLocation3);
								break;
							case (4):
								latLong.setText(R.string.noLocation4);
								break;
							}
							waitingCounter++;
						}
					}
				});
			}
		}, 0, TIMER_LOOP);
	}

	/**
	 * Initializes managers.
	 */
	private void initManagers() {

		// Waffles
		w = new Waffle(mContext);

		// iSENSE API
		api = API.getInstance(mContext);
		api.useDev(true);

		// Upload Queue
		uq = new UploadQueue("data_walk", mContext, api);
		uq.buildQueueFromFile();

		// Vibrator for Long Click
		vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

		// GPS
		mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		initLocationManager();
		waitingForGPS();

		// Sensors
		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

		// Beep sound
		mMediaPlayer = MediaPlayer.create(this, R.raw.beep);

	}

	/**
	 * Catches the returns from other activities back to DataWalk.java
	 */
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {

		// If the user hits yes, launch a web page to view their data on iSENSE,
		// else do nothing.
		if (requestCode == DIALOG_VIEW_DATA) {

			if (resultCode == RESULT_OK) {
				projectURL += dataSetID + "?embed=true";
				Intent i = new Intent(Intent.ACTION_VIEW);
				i.setData(Uri.parse(projectURL));
				startActivity(i);
			}

			// If a new project has been selected, check to see if it is
			// actually valid.
		} else if (requestCode == EXPERIMENT_REQUESTED) {
			if (resultCode == RESULT_OK) {
				SharedPreferences prefs = getSharedPreferences("PROJID", 0);
				projectID = prefs.getString("project_id", null);

				if (api.hasConnectivity()) {
					new GetProjectTask().execute();
				}
			}

			// If the user hit yes, bring them to GPS settings.
		} else if (requestCode == DIALOG_NO_GPS) {
			if (resultCode == RESULT_OK) {
				startActivity(new Intent(
						Settings.ACTION_LOCATION_SOURCE_SETTINGS));
			}

			// The user left the app while it was running, so press the main
			// button to stop recording.
		} else if (requestCode == DIALOG_FORCE_STOP) {
			if (resultCode == RESULT_OK) {
				startStop.performLongClick();
			}

			// If the user uploaded data, offer to show the data on iSENSE
		} else if (requestCode == QUEUE_UPLOAD_REQUESTED) {
			uq.buildQueueFromFile();
			if (resultCode == RESULT_OK) {
				if (data != null) {
					dataSetID = data.getIntExtra(
							QueueLayout.LAST_UPLOADED_DATA_SET_ID, -1);
					if (dataSetID != -1) {
						Intent i = new Intent(DataWalk.this, ViewData.class);
						startActivityForResult(i, DIALOG_VIEW_DATA);
					}
				}
			}

			// Return of EnterNameActivity
		} else if (requestCode == NAME_REQUESTED) {

			// the user entered a valid name so update the main UI
			if (resultCode == RESULT_OK) {
				nameTxtBox.setText("Name: " + firstName + " " + lastInitial);
			} else {
				// Calls the enter name activity if a name has not yet been
				// entered.
				if (firstName.equals("") || lastInitial.equals("")) {
					startActivityForResult(new Intent(mContext,
							EnterNameActivity.class), NAME_REQUESTED);
					w.make("You must enter your name before starting to record data.",
							Waffle.LENGTH_SHORT, Waffle.IMAGE_X);
				}
			}

			// Resets iSENSE and recording variables to their defaults.
		} else if (requestCode == RESET_REQUESTED) {

			if (resultCode == RESULT_OK) {
				
				// Set variables to default
				mInterval = DEFAULT_INTERVAL;
				loginName = DEFAULT_USERNAME;
				loginPass = DEFAULT_PASSWORD;
				firstName = "";
				lastInitial = "";
				projectID = DEFAULT_PROJECT;

				//TODO
				SharedPreferences prefs = getSharedPreferences("PROJID", 0);
				SharedPreferences.Editor mEdit = prefs.edit();
				mEdit.putString("project_id", DEFAULT_PROJECT);
				mEdit.commit();
				
				w.make("Settings have been reset to default.",
						Waffle.LENGTH_SHORT);
				
				startActivityForResult(new Intent(mContext,
						EnterNameActivity.class), NAME_REQUESTED);

			}
		} else if (requestCode == LOGIN_STATUS_REQUESTED) {
			if (resultCode == RESULT_OK) {
				final SharedPreferences mPrefs = new ObscuredSharedPreferences(
						DataWalk.mContext,
						DataWalk.mContext.getSharedPreferences("USER_INFO",
								Context.MODE_PRIVATE));

				loginName = mPrefs.getString(DataWalk.USERNAME_KEY,
						DEFAULT_USERNAME);
				loginPass = mPrefs.getString(DataWalk.PASSWORD_KEY,
						DEFAULT_USERNAME);

				loggedInAs.setText(getResources().getString(
						R.string.logged_in_as)
						+ " " + loginName);
			} else {
				loggedInAs.setText(getResources().getString(
						R.string.logged_in_as));
			}

		} else if (requestCode == SPINNER_STARTED) {
			if (resultCode == RESULT_OK) {

			}

		}
	}//

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {

	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
			accel[0] = event.values[0];
			accel[1] = event.values[1];
			accel[2] = event.values[2];
			accel[3] = (float) Math.sqrt((float) (Math.pow(accel[0], 2)
					+ Math.pow(accel[1], 2) + Math.pow(accel[2], 2)));
		}
	}

	// Rajia's created Menu...
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.menu, menu);
		return true;
	}

	public static final int LOGIN_STATUS_REQUESTED = 45;
	public static final int SPINNER_STARTED = 23;

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {

		case R.id.Upload:
			manageUploadQueue();
			return true;
		case R.id.reset:
			Intent i = new Intent(mContext, Reset.class);
			startActivityForResult(i, RESET_REQUESTED);
			return true;
		case R.id.login:
			startActivityForResult(new Intent(this, LoginIsense.class),
					LOGIN_STATUS_REQUESTED);
			return true;
		case R.id.NameChange:
			startActivityForResult(new Intent(this, EnterNameActivity.class),
					NAME_REQUESTED);
			return true;
		case R.id.DataUploadRate:
			startActivity(new Intent(this, DataRateDialog.class));
			return true;
		case R.id.ExpNum:
			Intent setup = new Intent(this, Setup.class);
			startActivityForResult(setup, EXPERIMENT_REQUESTED);
			return true;
		case R.id.About:
			startActivity(new Intent(this, About.class));
			return true;
		case R.id.help:
			startActivity(new Intent(this, Help.class));
			return true;
		}
		return false;
	}// Ends on options item selected

	public class AttemptLoginTask extends AsyncTask<Void, Integer, Void> {

		boolean connect = false;
		boolean success = false;

		@Override
		protected void onPreExecute() {
			super.onPreExecute();

			final SharedPreferences mPrefs = new ObscuredSharedPreferences(
					DataWalk.mContext, DataWalk.mContext.getSharedPreferences(
							"USER_INFO", Context.MODE_PRIVATE));

			loginName = mPrefs.getString(DataWalk.USERNAME_KEY,
					DEFAULT_USERNAME);
			loginPass = mPrefs.getString(DataWalk.PASSWORD_KEY,
					DEFAULT_PASSWORD);
		}

		@Override
		protected Void doInBackground(Void... arg0) {
			if (api.hasConnectivity()) {
				connect = true;
				success = api.createSession(loginName, loginPass);
			} else {
				connect = false;
			}

			return null;
		}

		@Override
		protected void onPostExecute(Void result) {
			super.onPostExecute(result);

			if (connect) {
				if (success) {
					final SharedPreferences mPrefs = new ObscuredSharedPreferences(
							DataWalk.mContext,
							DataWalk.mContext.getSharedPreferences("USER_INFO",
									Context.MODE_PRIVATE));

					SharedPreferences.Editor mEditor = mPrefs.edit();
					mEditor.putString(DataWalk.USERNAME_KEY, loginName);
					mEditor.putString(DataWalk.PASSWORD_KEY, loginName);
					mEditor.commit();

					loggedInAs.setText(getResources().getString(
							R.string.logged_in_as)
							+ " " + loginName);

				} else {
					if (loginName.length() == 0 || loginPass.length() == 0)
						startActivityForResult(new Intent(mContext,
								LoginIsense.class), LOGIN_STATUS_REQUESTED);
				}
			} else {
				w.make("Cannot connect to internet. Please check network settings.",
						Waffle.LENGTH_LONG, Waffle.IMAGE_X);
				loggedInAs.setText(getResources().getString(
						R.string.logged_in_as));

			}

		}

	}

	/**
	 * Checks to see if the last entered project number is valid.
	 * 
	 * @author Rajia
	 */
	public class GetProjectTask extends AsyncTask<Void, Integer, Void> {

		RProject proj;

		@Override
		protected Void doInBackground(Void... arg0) {

			// Get the project from iSENSE
			proj = api.getProject(Integer.parseInt(projectID));

			return null;
		}

		@Override
		protected void onPostExecute(Void result) {
			super.onPostExecute(result);

			// If the project is invalid, tell the user and launch the project
			// picking dialog.
			if (proj.name == null || proj.name.equals("")) {
				w.make("Project Number Invalid! Please enter a new one.",
						Waffle.LENGTH_LONG, Waffle.IMAGE_X);

				startActivityForResult(new Intent(mContext, Setup.class),
						EXPERIMENT_REQUESTED);
			}
		}

	}

	@Override
	public void onGpsStatusChanged(int event) {
		GpsStatus status = mLocationManager.getGpsStatus(null);
		int count = 0;
		Iterable<GpsSatellite> sats = status.getSatellites();

		for (Iterator<GpsSatellite> i = sats.iterator(); i.hasNext(); i.next()) {
			count++;
		}

		if (count < 4)
			gpsWorking = false;
	}

	/**
	 * Runs the main timer that records data and updates the main UI every
	 * second.
	 */
	void runRecordingTimer() {

		// Start the sensor manager so we can get accelerometer data
		mSensorManager.registerListener(DataWalk.this,
				mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				SensorManager.SENSOR_DELAY_FASTEST);

		// Prepare new containers where our recorded values will be stored
		dataSet = new JSONArray();
		accel = new float[4];

		// Reset timer variables
		final long startTime = System.currentTimeMillis();
		elapsedMillis = 0;
		timerTick = 0;

		// Creates a new timer that runs every second
		recordTimer = new Timer();
		recordTimer.scheduleAtFixedRate(new TimerTask() {

			public void run() {

				// Increase the timerTick count
				timerTick++;

				// Update the main UI with the correct number of seconds
				runOnUiThread(new Runnable() {

					@Override
					public void run() {
						if (timerTick == 1) {
							timeElapsedBox.setText("Time Elapsed: " + timerTick
									+ " second");
						} else {
							timeElapsedBox.setText("Time Elapsed: " + timerTick
									+ " seconds");
						}
					}
				});

				// Every n seconds which is determined by interval
				// (not including time 0)
				if ((timerTick % (mInterval / 1000)) == 0 && timerTick != 0) {

					// Prepare a new row of data
					JSONObject dataJSON = new JSONObject();

					// Determine how long you've been recording for
					elapsedMillis += mInterval;
					long time = startTime + elapsedMillis;

					try {

						// Store new values into JSON Object
						dataJSON.put("0", "u " + time);
						dataJSON.put("1", accel[3]);
						dataJSON.put("2", loc.getLatitude());
						dataJSON.put("3", loc.getLongitude());

						// Save this data point if GPS says it has a lock
						if (gpsWorking) {
							dataSet.put(dataJSON);

							// Updated the number of points recorded here and on
							// the main UI
							dataPointCount++;
							runOnUiThread(new Runnable() {

								@Override
								public void run() {
									pointsUploadedBox
											.setText("Points Recorded: "
													+ dataPointCount);
								}

							});
						}

					} catch (JSONException e) {
						e.printStackTrace();
					}
				}
			}

		}, 0, TIMER_LOOP);

	}

}
