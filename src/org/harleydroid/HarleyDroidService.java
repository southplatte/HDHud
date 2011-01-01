package org.harleydroid;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

public class HarleyDroidService extends Service
{
	private static final boolean D = true;
	private static final String TAG = "HarleyDroidService";
	private static final boolean EMULATOR = false;
	
	private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	
	private static final int AT_TIMEOUT = 2000;
	private static final int ATZ_TIMEOUT = 5000;
	private static final int ATMA_TIMEOUT = 2000;
	
	private static final int MAX_ERRORS = 10;

	private final IBinder binder = new HarleyDroidServiceBinder();
	private HarleyData mHD;
	private NotificationManager mNM;
	private Handler mHandler = null;
	private ThreadELM mThread = null;
	private OutputStream mLog = null;

	@Override
	public void onCreate() {
		super.onCreate();
		if (D) Log.d(TAG, "onCreate()");
		
		mHD = new HarleyData();
		mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
		CharSequence text = getText(R.string.notification_start);
		Notification notification = new Notification(R.drawable.stat_notify_car_mode, text, System.currentTimeMillis());
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, HarleyDroid.class), 0);
		notification.setLatestEventInfo(this, getText(R.string.notification_label), text, contentIntent);
		mNM.notify(R.string.notification_label, notification);
	}

	public void onDestroy() {
		super.onDestroy();
		if (D) Log.d(TAG, "onDestroy()");
		
		mNM.cancel(R.string.notification_label);
	}

	@Override
	public IBinder onBind(Intent intent) {
		if (D) Log.d(TAG, "onBind()");
		
		return binder;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (D) Log.d(TAG, "onStartCommand()");
		
	    return START_STICKY;
	}
	
	public void setHandler(Handler handler) {
		if (D) Log.d(TAG, "setHandler()");
		
		mHandler = handler;
		mHD.setHandler(handler);
	}
	
	public void startService(BluetoothDevice dev, File logFile) {
		if (D) Log.d(TAG, "startService()");
	
		// open logfile if possible
		if (logFile != null) {
			try {
				mLog = new FileOutputStream(logFile, true);
				String header = "Starting at " + 
				   new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) +
				   "\n"; 
				mLog.write(header.getBytes());
			} catch (IOException e) {
				Log.d(TAG, "Logfile open " + e);
			}
		}
		
		mThread = new ThreadELM(dev);
		mThread.start();
	}
	
	public void stopService() {
		if (D) Log.d(TAG, "stopService()");
		
		if (mLog != null) {
			try {
				mLog.close();
			} catch (IOException e) {
			}
		}
		mThread.stop = true;
		mThread = null;
	}
	
	public boolean isRunning() {
		if (D) Log.d(TAG, "isRunning()");

		return (mThread != null); 
	}
	
	public class HarleyDroidServiceBinder extends Binder {
		HarleyDroidService getService() {
			return HarleyDroidService.this;
		}
	}

	private class ThreadELM extends Thread {
		private BluetoothDevice mDevice;
		private BufferedReader mIn;
		private OutputStream mOut;
		private BluetoothSocket mSock;
		private Timer mTimer;
		boolean stop = false;
		
		class CancelTimer extends TimerTask {
				public void run() {
					if (D) Log.d(TAG, "CANCEL AT " + System.currentTimeMillis());
					try {
						mSock.close();
					} catch (IOException e) {
					}
				}
		};
		
		public ThreadELM(BluetoothDevice device) {
			mDevice = device;
			mTimer = new Timer();
		}

		private String readLine(long timeout) throws IOException {
			CancelTimer t = new CancelTimer();
			mTimer.schedule(t, timeout); 
			if (D) Log.d(TAG, "READLINE AT " + System.currentTimeMillis());
			String line = mIn.readLine();
			t.cancel();
			if (D) Log.d(TAG, "read (" + line.length() + "): " + line);
			if (mLog != null) {
				mLog.write(line.getBytes());
				mLog.write('\n');
			}
			return line;
		}
		
		private void writeLine(String line) throws IOException {
			line += "\r";
			if (D) Log.d(TAG, "write: " + line);
			mOut.write(line.getBytes());
	    	mOut.flush();
		}
		
		private void chat(String send, String expect, long timeout) throws IOException {
			String line = null;
			writeLine(send);
			long start = System.currentTimeMillis();
			while (timeout > 0) {
				line = readLine(timeout);
				long now = System.currentTimeMillis();
				if (line.indexOf(expect) != -1)
					return;
				timeout -= (now - start);
				start = now;
			}
			throw new IOException("timeout");
		}
		
		public void run() {
			int errors = 0;
    		int cnt = 0;
    	
    		if (!EMULATOR) {
    			try {
    				if (D) Log.d(TAG, "started");
    				mSock = mDevice.createRfcommSocketToServiceRecord(MY_UUID);
    				mSock.connect();
    				if (D) Log.d(TAG, "connected");
    				mIn = new BufferedReader(new InputStreamReader(mSock.getInputStream()), 128);
    				mOut = mSock.getOutputStream();
    			} catch (IOException e1) {
    				try {
    					mSock.close();
    				} catch (IOException e2) {
    				}
    				mHandler.obtainMessage(HarleyDroid.STATUS_ERROR, -1, -1).sendToTarget();
    				stopSelf();
    				return;
    			}
    			
    			try {
    				chat("AT", "", AT_TIMEOUT);
    				try {
    					Thread.sleep(1000);
    				} catch (InterruptedException e2) {
    				}
    				chat("ATZ", "ELM327", ATZ_TIMEOUT);
    				chat("ATE1", "OK", AT_TIMEOUT);
    				chat("ATH1", "OK", AT_TIMEOUT);
    				chat("ATSP2", "OK", AT_TIMEOUT);
    				chat("ATMA", "", AT_TIMEOUT);
    			} catch (IOException e1) {
    				mHandler.obtainMessage(HarleyDroid.STATUS_ERRORAT, -1, -1).sendToTarget();
    				stopSelf();
    				return;
    			}
    		} // !EMULATOR

    		if (D) Log.d(TAG, "ready");
    		mHandler.obtainMessage(HarleyDroid.STATUS_CONNECTED, -1, -1).sendToTarget();
    			
    		while (!stop) {
    			String line;
    				
    			if (EMULATOR) {
    	   			try {
        				Thread.sleep(1000);
        			} catch (InterruptedException e1) {
        			}
    				line = "28 1B 10 02 10 74 4C";
    			}
    			else {
    				try {
    					line = readLine(ATMA_TIMEOUT);
    				} catch (IOException e1) {
    					mHandler.obtainMessage(HarleyDroid.STATUS_NODATA, -1, -1).sendToTarget();
    					stop = true;
    					break;
    				}
    			}
    				
    			try {
    				J1850.parse(line.getBytes(), mHD);
    				errors = 0;
    			} catch (Exception e1) {
    				if (D) Log.d(TAG, "Error: " + e1.getMessage());
    				++errors;
    				if (errors > MAX_ERRORS) {
    					mHandler.obtainMessage(HarleyDroid.STATUS_TOOMANYERRORS, -1, -1).sendToTarget();
    					stop = true;
    					break;	
    				}
    			}
    			mHandler.obtainMessage(HarleyDroid.UPDATE_CLUTCH, cnt++, -1).sendToTarget();
    		}
    		if (!EMULATOR) {
    			try {
    				writeLine("");
    			} catch (IOException e1) {
    			}
    			try {
    				mSock.close();
    			} catch (IOException e3) {
    			}
    		}
    		stopSelf();
		}
	}
}