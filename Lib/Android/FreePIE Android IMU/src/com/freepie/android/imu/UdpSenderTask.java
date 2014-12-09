package com.freepie.android.imu;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CyclicBarrier;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

public class UdpSenderTask implements SensorEventListener {

	private static final byte SEND_RAW = 0x01;
	private static final byte SEND_ORIENTATION = 0x02;
	private static final byte SEND_NONE = 0x00;
	
	float[] acc;
	float[] mag;
	float[] gyr;
	float[] imu;
	
	float[]  rotationVector;
	final float[] rotationMatrix = new float[16];
	
	DatagramSocket socket;
	InetAddress endPoint;
	int port;
	byte deviceIndex;
	boolean sendOrientation;
	boolean sendRaw;
	boolean debug;
	private int sampleRate;
	private IDebugListener debugListener;
	private SensorManager sensorManager;
	private IErrorHandler errorHandler;
		
	ByteBuffer buffer;
	CyclicBarrier sync;

	Thread worker;
	volatile boolean running;
    private boolean hasGyro;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    public void start(TargetSettings target, PowerManager.WakeLock wl, WifiManager.WifiLock nl) {
        wakeLock = wl;
        wifiLock = nl;

		deviceIndex = target.getDeviceIndex();
		sensorManager = target.getSensorManager();
		port = target.getPort();
		final String ip = target.getToIp();

		sendRaw = target.getSendRaw();
		sendOrientation = target.getSendOrientation();		
		sampleRate = target.getSampleRate();		
		debug = target.getDebug();
		debugListener = target.getDebugListener();
		errorHandler = target.getErrorHandler();			
				
		sync = new CyclicBarrier(2);		

		buffer = ByteBuffer.allocate(50);
		buffer.order(ByteOrder.LITTLE_ENDIAN);	
					
		running = true;
		worker = new Thread(new Runnable() { 
            public void run(){
        		try {	
        			endPoint = InetAddress.getByName(ip);
        			socket = new DatagramSocket();
        		}
        		catch(Exception e) {
        			errorHandler.error("Can't create endpoint", e.getMessage());
        		}
            	
        		while(running) {
        			try {
        			sync.await();
        			} catch(Exception e) {}
        			
        			Send();
        		}
        		try  {
        			socket.disconnect();
        		}
        		catch(Exception e)  {}
        	}
		});	
		worker.start();

        hasGyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
		
		if(sendRaw) {
			sensorManager.registerListener(this,
					sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
					sampleRate);
			if (hasGyro)
                sensorManager.registerListener(this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                        sampleRate);
			
			sensorManager.registerListener(this,
					sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
					sampleRate);
		}
		if(sendOrientation) {
            if (hasGyro)
                sensorManager.registerListener(this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
                        sampleRate);
            else {
                sensorManager.registerListener(this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                        sampleRate);
                sensorManager.registerListener(this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                        sampleRate);
            }
        }

        wifiLock.acquire();
        wakeLock.acquire();
	}
	
	private byte getFlagByte(boolean raw, boolean orientation) {
		return (byte)((raw ? SEND_RAW : SEND_NONE) | 
				(orientation ? SEND_ORIENTATION : SEND_NONE)); 
	}
	
	public void onSensorChanged(SensorEvent sensorEvent) {
        synchronized (this) {
            switch (sensorEvent.sensor.getType()) {
                case Sensor.TYPE_ACCELEROMETER:
                    acc = sensorEvent.values.clone();
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    mag = sensorEvent.values.clone();
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    gyr = sensorEvent.values.clone();
                    break;
                case Sensor.TYPE_ROTATION_VECTOR:
                    rotationVector = sensorEvent.values.clone();
                    break;
            }

            if (!hasGyro && gyr == null)
                gyr = new float[]{0, 0, 0};

            if (sendOrientation) {
                if (!hasGyro && mag != null && acc != null) {
                    float R[] = new float[9];
                    float I[] = new float[9];
                    boolean ret = SensorManager.getRotationMatrix(R, I, acc, mag);
                    if (ret) {
                        float[] ypr = new float[3];
                        SensorManager.getOrientation(R, ypr);
                        imu = ypr;
                    }
                } else {
                    if (rotationVector != null) {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector);
                        SensorManager.getOrientation(rotationMatrix, rotationVector);
                        imu = rotationVector;
                        rotationVector = null;
                    }
                }
            }

            if (debug && acc != null && gyr != null && mag != null)
                debugListener.debugRaw(acc, gyr, mag);

            if (debug && imu != null)
                debugListener.debugImu(imu);

            if (sendRaw && gyr != null && acc != null && mag != null || sendOrientation && imu != null)
                releaseSendThread();
        }
	}
	

	private void releaseSendThread() {
        sync.reset();
	}
	
	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {	
	}
	
	public void stop() {
        wakeLock.release();
        wifiLock.release();
		running = false;
		sensorManager.unregisterListener(this);		
		releaseSendThread();
	}

	private void Send() {
        DatagramPacket p;

        synchronized (this) {
            boolean raw = sendRaw && acc != null && gyr != null && mag != null;
            boolean orientation = sendOrientation && imu != null;

            if (!raw && !orientation) return;

            buffer.clear();

            buffer.put(deviceIndex);
            buffer.put(getFlagByte(raw, orientation));

            if (raw) {
                //Acc
                buffer.putFloat(acc[0]);
                buffer.putFloat(acc[1]);
                buffer.putFloat(acc[2]);

                //Gyro
                buffer.putFloat(gyr[0]);
                buffer.putFloat(gyr[1]);
                buffer.putFloat(gyr[2]);

                //Mag
                buffer.putFloat(mag[0]);
                buffer.putFloat(mag[1]);
                buffer.putFloat(mag[2]);

                acc = null;
                mag = null;
                gyr = null;
            }

            if (orientation) {
                buffer.putFloat(imu[0]);
                buffer.putFloat(imu[1]);
                buffer.putFloat(imu[2]);
                imu = null;
            }


            byte[] arr = buffer.array();
            if (endPoint == null)
                return;

            p = new DatagramPacket(arr, buffer.position(), endPoint, port);
        }
	    
	    try {
	    	socket.send(p);
	    }
	    catch(IOException w) {	    	
	    }
	}

	public void setDebug(boolean debug) {
		this.debug = debug;		
	}
}