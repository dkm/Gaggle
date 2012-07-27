package com.geeksville.location.baro;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Observable;
import java.util.Set;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.geeksville.android.PreferenceUtil;
import com.geeksville.location.IBarometerClient;
import com.geeksville.util.LinearRegression;

/**
 * A client for the FlyNet bluetooth vario
 * 
 * @author relet
 */

public class BBBarometerClient extends Observable implements
    IBarometerClient, Runnable {

  private static final String TAG = "BBBarometerClient";

  // / What device name do we look for?
  // private static final String myName = "FlyNet";
  // / What device type
  private static final int myClass = 0;

  // Commands recognized by the FlyNet device
  private final String CMD_PRESSURE    = "_PRS";
  private final String CMD_BATTERY     = "_BAT";

  @SuppressWarnings("unused")
  private final String CMD_DEVICENAME  = "_USR";
  
  // Our extensions
  private final String CMD_VZ          = "__VZ";

  /** A unique ID for our app */
//  private UUID uuid = UUID.fromString("b00d0c47-899b-4484-810a-5b27a514e906");

  private BluetoothDevice device;
  private Thread thread;

  private float batPercentage, pressure, altitude;
  private boolean isCharging = false;
  private float verticalSpeed;

  // / true if we've been set based on the GPS
  private boolean isCalibrated = false;

  // / Defaults to 1013.25 hPa
  private float reference = SensorManager.PRESSURE_STANDARD_ATMOSPHERE;

  public BBBarometerClient(Context context) {
    this.device = findDevice();

    // We do all the real work in a background thread, so we don't stall and can
    // handle reboots of the bluetooth device

    // FIXME this burns too much power, we should instead only create our reader
    // thread in addObserver, then
    // shut it down gracefully when the number of observers drops to zero. This
    // will have the nice effect of only talking
    // to the bluetooth baro when we actually need its data.
    thread = new Thread(this, "BB");
    thread.setDaemon(true);
    thread.start();
  }

  static public boolean isAvailable() {
    BluetoothDevice found = findDevice();
    Log.d(TAG, "Found devices: " + found);
    return found != null;
  }

  private static BluetoothDevice findDevice() {
    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    if (adapter != null && adapter.isEnabled()) {
      Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
	  Log.d(TAG, "# BT paired " +  pairedDevices.size());

      for (BluetoothDevice device : pairedDevices) {
    	  Log.d(TAG,
                  "Found paired to " + device.getName() + "@" + device.getAddress() + " which has device ID " + device.getBluetoothClass().getDeviceClass());
        if ((device.getBluetoothClass().getDeviceClass() == myClass)) {
          Log.d(TAG,
              "Connected to " + device.getName() + "@" + device.getAddress() + " which has device ID " + device.getBluetoothClass().getDeviceClass());
          //if (device.getName().startsWith(myName))
          return device;
        }
      }
    }

    return null;
  }

  @Override
  public void setAltitude(float meters) {
    // float p0 = 1013.25f; // Pressure at sea level (hPa)
    // float p = p0 * (float) Math.pow((1 - meters / 44330), 5.255);
    float p0 = pressure / (float) Math.pow((1 - meters / 44330), 5.255);

    reference = p0;
    altitude = SensorManager.getAltitude(reference, pressure);

    Log.w(TAG, "Setting baro reference to " + reference + " alt=" + meters);
    isCalibrated = true;
  }

  @Override
  public float getAltitude() {
    return altitude;
  }

  public float getPressure() {
    return pressure;
  }
  public float getBattery() {
    return Float.NaN;
    // FIXME - if we know the battery size, we can calculate this from the percentage.
  }
  public float getBatteryPercent() {
    return batPercentage;
  }
  public boolean isCharging() {
    return isCharging;
  }

  @Override
  public float getVerticalSpeed() {
	  return verticalSpeed;
  }
 
  @Override
  public void improveLocation(Location l) {
    if (isCalibrated)
      l.setAltitude(altitude);
  }

  private void handleMessage(String m) {
    if (m.length()>4) {
      String cmd = m.substring(0,4);
      if (cmd.equals(CMD_PRESSURE)) {
        // "_PRS 17CBA\n" corresponds to 0x17CBA Pa
        pressure = Integer.parseInt(m.substring(7,m.length()), 16) / 100.f;
        //altitude = SensorManager.getAltitude(reference, pressure);
        //Log.d(TAG, "-> pressure = " + pressure + "\t altitude = "+altitude);
      } else if(cmd.equals(CMD_VZ)) {
    	  verticalSpeed = Integer.parseInt(m.substring(5,m.length())) / 100.f;
      } else if (cmd.equals(CMD_BATTERY)) {
        // "_BAT 9\n" corresponds to 90%
        // "_BAT *\n" signals charging status
        if (m.charAt(5) == '*') {
          isCharging = true;
        } else {
          batPercentage = Integer.parseInt(m.substring(5,6), 16) / 16.f;
          isCharging = false; // FIXME - may need a timeout if it actually alternates with the * message when charging
        }
      } 
 
      // Tell the GUI/audio vario we have new state
      setChanged();
      notifyObservers(pressure);
    }
  }

  /** The background thread that talks to device */
  @Override
  public void run() {
    BluetoothSocket socket = null;
    Log.d(TAG, "Using BB Vario");
    //Toast.makeText(context, "Using FlyNet Vario", Toast.LENGTH_LONG) /* FIXME - can't create handler inside thread */
    //    .show();
 
    while (true) {
      Log.d(TAG, "Reconnecting to BB Vario");
      try {
        //socket = device.createRfcommSocketToServiceRecord(uuid); /* NOTE - does not work in Android 2.1/2.2 */
        BluetoothDevice hxm = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(device.getAddress());
        Method m;
        try {
          m = hxm.getClass().getMethod("createRfcommSocket", new Class[]{int.class});
          socket = (BluetoothSocket)m.invoke(hxm, Integer.valueOf(1));
        } catch (Exception e) {
          Log.d(TAG, "Error while creating socket", e);
        }

 
        // Connect the device through the socket. This will block
        // until it succeeds or throws an exception
        socket.connect();
        Log.d(TAG, "Connected to BB vario");
 
        // Read messages
        BufferedReader reader = new BufferedReader(new InputStreamReader(
            socket.getInputStream()));
 
        String line;
        while ((line = reader.readLine()) != null){
        	Log.d(TAG, "Got:" + line);
        	handleMessage(line);
        }
 
        reader.close();
 
        Log.d(TAG, "Disconnected from BB Vario");
 
        socket.close();
        socket = null;
      } catch (IOException connectException) {
        // close the socket and get out
        Log.d(TAG, "Error while connecting", connectException);
        try {
          if (socket != null)
            socket.close();
        } catch (IOException closeException) {
          // Ignore errors on close
        }
      }
    }
  }
}
