/*
    Copyright (C) 2014 Parrot SA

    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions
    are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in
      the documentation and/or other materials provided with the 
      distribution.
    * Neither the name of Parrot nor the names
      of its contributors may be used to endorse or promote products
      derived from this software without specific prior written
      permission.

    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
    "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
    LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
    FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
    COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
    INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
    BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
    OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED 
    AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
    OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
    OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
    SUCH DAMAGE.
*/
package com.parrot.arsdk.arsal;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;

import android.bluetooth.BluetoothAdapter;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import com.parrot.arsdk.arsal.ARSALPrint;

@TargetApi(18)
public class ARSALBLEManager
{
    private static String TAG = "ARSALBLEManager";
    
    private static final UUID ARSALBLEMANAGER_CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    
    private static final int ARSALBLEMANAGER_CONNECTION_TIMEOUT_SEC  = 5;
    private static final int GATT_INTERNAL_ERROR = 133;
    private static final int GATT_INTERRUPT_ERROR = 8;
    
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics;
    
    private Context context;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    
    private BluetoothDevice deviceBLEService;
    private BluetoothGatt activeGatt;
    
    private ARSALBLEManagerListener listener;
    
    private HashMap<String, ARSALManagerNotification> registeredNotificationCharacteristics = new HashMap<String, ARSALManagerNotification>();
    
    private Semaphore connectionSem;
    private Semaphore disconnectionSem;
    private Semaphore discoverServicesSem;
    private Semaphore discoverCharacteristicsSem;
    private Semaphore configurationSem;
    
    private ARSAL_ERROR_ENUM connectionError;
    private ARSAL_ERROR_ENUM discoverServicesError;
    private ARSAL_ERROR_ENUM discoverCharacteristicsError;
    private ARSAL_ERROR_ENUM configurationCharacteristicError;
    private ARSAL_ERROR_ENUM writeCharacteristicError;
    
    private boolean askDisconnection;
    private boolean isDiscoveringServices;
    private boolean isDiscoveringCharacteristics;
    private boolean isConfiguringCharacteristics;
    private boolean isDeviceConnected = false;
    
    public class ARSALManagerNotificationData
    {
        public BluetoothGattCharacteristic characteristic = null;
        public byte[] value = null;
        
        public ARSALManagerNotificationData(BluetoothGattCharacteristic _characteristic, byte[] _value)
        {
            this.characteristic = _characteristic;
            this.value = _value;
        }
    }
    
    public class ARSALManagerNotification
    {
        private Semaphore readCharacteristicSem = new Semaphore(0);
        private Lock readCharacteristicMutex = new ReentrantLock();
        List<BluetoothGattCharacteristic> characteristics = null;
        ArrayList<ARSALManagerNotificationData> notificationsArray = new ArrayList<ARSALManagerNotificationData>();
        
        public ARSALManagerNotification(List<BluetoothGattCharacteristic> characteristicsArray)
        {
            this.characteristics = characteristicsArray;
        }
        
        void addNotification(ARSALManagerNotificationData notificationData)
        {
            readCharacteristicMutex.lock();
            
            notificationsArray.add(notificationData);
            
            readCharacteristicMutex.unlock();
            
            synchronized (readCharacteristicSem) 
            {
                readCharacteristicSem.notify();
            }
        }
        
        int getAllNotification(List<ARSALManagerNotificationData> getNoticationsArray, int maxCount)
        {
            ArrayList<ARSALManagerNotificationData> removeNotifications = new ArrayList<ARSALManagerNotificationData>(); 
            
            readCharacteristicMutex.lock();
            
            for (int i=0; (i < maxCount) && (i < notificationsArray.size()); i++)
            {
                ARSALManagerNotificationData notificationData = notificationsArray.get(i);
                
                getNoticationsArray.add(notificationData);
                removeNotifications.add(notificationData);
            }
            
            for (int i=0; (i < removeNotifications.size()); i++)
            {
                notificationsArray.remove(removeNotifications.get(i));
            }
            
            readCharacteristicMutex.unlock();
            
            return getNoticationsArray.size();
        }
        
        /** Waits for a notification to occur, unless timeout expires first.
         * @param timeout timeout value in milliseconds. 0 means no timeout. 
         */
        boolean waitNotification(long timeout)
        {
            boolean ret = true;
            try
            {
                if (timeout == 0)
                {
                    readCharacteristicSem.acquire();
                }
                else
                {
                    readCharacteristicSem.tryAcquire(timeout, TimeUnit.MILLISECONDS);
                }
            }
            catch (InterruptedException e)
            {
                ret = false;
            }
            return ret;
        }
        
        boolean waitNotification()
        {
            return waitNotification(0);
        }
        
        void signalNotification()
        {
            readCharacteristicSem.release();
        }
    }

    private static class ARSALBLEManagerHolder 
    {
        private final static ARSALBLEManager instance = new ARSALBLEManager();
    }

    public static ARSALBLEManager getInstance(Context context) 
    {
        ARSALBLEManager manager = ARSALBLEManagerHolder.instance;
        manager.setContext(context);
        return manager;
    }

    private synchronized void setContext(Context context)
    {
        if (this.context == null) 
        {
            if (context == null)
            {
                throw new IllegalArgumentException("Context must not be null");
            }
            this.context = context;    
        }
        
        initialize();
    }
    
    public boolean initialize()
    {
        boolean result = true;
        
        if (bluetoothManager == null)
        {
            bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager == null)
            {
                ARSALPrint.e(TAG, "Unable to initialize BluetoothManager.");
                ARSALPrint.e(TAG, "initialize: Unable to initialize BluetoothManager.");
                result = false;
            }
        }
        
        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null)
        {
            ARSALPrint.e(TAG, "Unable to obtain a BluetoothAdapter.");
            ARSALPrint.e(TAG, "initialize: Unable to obtain a BluetoothAdapter.");
            result = false;
        }
        
        return result;
     }
    
    /**
     * Constructor
     */
    private ARSALBLEManager ()
    {
        this.context = null;
        this.deviceBLEService =  null;
        this.activeGatt = null;
        
        listener = null;
        
        connectionSem = new Semaphore (0);
        disconnectionSem = new Semaphore (0);
        discoverServicesSem = new Semaphore (0);
        discoverCharacteristicsSem = new Semaphore (0);
        configurationSem = new Semaphore (0);

        askDisconnection = false;
        isDiscoveringServices = false;
        isDiscoveringCharacteristics = false;
        isConfiguringCharacteristics = false;
        
        connectionError = ARSAL_ERROR_ENUM.ARSAL_OK;
        discoverServicesError = ARSAL_ERROR_ENUM.ARSAL_OK;
        discoverCharacteristicsError = ARSAL_ERROR_ENUM.ARSAL_OK;
        configurationCharacteristicError = ARSAL_ERROR_ENUM.ARSAL_OK;
    }
    
    /**
     * Destructor
     */
    public void finalize () throws Throwable
    {
        try
        {
            disconnect ();
        }
        finally
        {
            super.finalize ();
        }
    }
    
    public boolean isDeviceConnected()
    {
        boolean ret = false;
        
        synchronized (this) 
        {
            if (activeGatt != null && isDeviceConnected)
            {
                ret = true;
            }
        }
        
        return ret;
    }
    
    @TargetApi(18)
    public ARSAL_ERROR_ENUM connect (BluetoothDevice deviceBLEService)
    {
        ARSAL_ERROR_ENUM result = ARSAL_ERROR_ENUM.ARSAL_OK;
        synchronized (this)
        {
            /* if there is an active activeGatt, disconnecting it */
            if (activeGatt != null) 
            {
                disconnect();
            }
            
            /* reset */
            reset ();
            
            connectionError = ARSAL_ERROR_ENUM.ARSAL_OK;
            
            /* connection to the new activeGatt */
            ARSALBLEManager.this.deviceBLEService = bluetoothAdapter.getRemoteDevice(deviceBLEService.getAddress());
            
            BluetoothGatt connectionGatt = ARSALBLEManager.this.deviceBLEService.connectGatt (context, false, gattCallback);
            if(connectionGatt == null)
            {
                ARSALPrint.e(TAG, "connect (connectionGatt == null)");
                connectionError = ARSAL_ERROR_ENUM.ARSAL_ERROR_BLE_NOT_CONNECTED;
            }
            
            /* wait the connect semaphore*/
            try
            {
                boolean aquired = connectionSem.tryAcquire (ARSALBLEMANAGER_CONNECTION_TIMEOUT_SEC, TimeUnit.SECONDS);
                
                if (aquired)
                {
                    result = connectionError;
                }
                else
                {
                    /* Connection failed */
                    result = ARSAL_ERROR_ENUM.ARSAL_ERROR_BLE_NOT_CONNECTED;
                }
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
            
            if (activeGatt != null)
            {
                // TODO see
                connectionGatt = null;
            }
            else
            {
                /* Connection failed */
                if(connectionGatt != null)
                {
                    connectionGatt.close();
                    connectionGatt = null;
                }
            }
        }
        
        return result;
    }
    
    private void disconnectGatt()
    {
        if (bluetoothManager != null)
        {
            if (bluetoothManager.getConnectionState(activeGatt.getDevice(), BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED)
            {
                activeGatt.disconnect();
            }
            /*else
            {
                onDisconectGatt();
            }*/
        }
    }
    
    public void disconnect ()
    {
        //synchronized (this)
        //{
            if (activeGatt != null)
            {
                askDisconnection = true;
                
                disconnectGatt();
                
                ARSALPrint.d(TAG, "wait the disconnect Semaphore");
                /* wait the disconnect Semaphore*/
                try
                {
                    boolean acquire = disconnectionSem.tryAcquire (ARSALBLEMANAGER_CONNECTION_TIMEOUT_SEC, TimeUnit.SECONDS);
                    if(!acquire)
                    {
                        onDisconectGatt();
                    }
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
                
                askDisconnection = false;
            }
        //}
    }
    
    public ARSAL_ERROR_ENUM discoverBLENetworkServices ()
    {
        ARSAL_ERROR_ENUM result = ARSAL_ERROR_ENUM.ARSAL_OK;
        synchronized (this)
        {
            /* If there is an active Gatt, disconnect it */
            if (activeGatt != null)
            {
                isDiscoveringServices = true;
                discoverServicesError = ARSAL_ERROR_ENUM.ARSAL_OK;
                
                /* run the discovery of the activeGatt services */
                boolean discoveryRes = activeGatt.discoverServices();
                
                if (discoveryRes)
                {
                    /* wait the discoverServices semaphore*/
                    try
                    {
                        discoverServicesSem.acquire ();
                        result = discoverServicesError;
                    }
                    catch (InterruptedException e)
                    {
                        e.printStackTrace();
                        result = ARSAL_ERROR_ENUM.ARSAL_ERROR;
                    }
                }
                else
                {
                    result = ARSAL_ERROR_ENUM.ARSAL_ERROR;
                }
                
                isDiscoveringServices = false;
            }
            else
            {
                result = ARSAL_ERROR_ENUM.ARSAL_ERROR_BLE_NOT_CONNECTED;
            }
        }
        
        return result;
    }
    
    public BluetoothGatt getGatt ()
    {
        return activeGatt;
    }
    
    public void setListener(ARSALBLEManagerListener listener)
    {
        this.listener = listener;
    }
    
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback()
    {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState)
        {
            ARSALPrint.w(TAG, "onConnectionStateChange : status = " + status + " newState = " + newState);

            if (newState == BluetoothProfile.STATE_DISCONNECTED)
            {
                if ((activeGatt != null) && (gatt == activeGatt))
                {
                    isDeviceConnected = false;
                    onDisconectGatt();
                }
                else
                {
                    ARSALPrint.w(TAG, "Disconnection of another gatt");
                    gatt.close();
                }
            }

            switch (status)
            {
                case BluetoothGatt.GATT_SUCCESS:
                    if (newState == BluetoothProfile.STATE_CONNECTED)
                    {
                        activeGatt = gatt;
                        isDeviceConnected = true;
                        connectionError = ARSAL_ERROR_ENUM.ARSAL_OK;
                        
                        /* post a connect Semaphore */
                        connectionSem.release();
                    }
                    break;

                case GATT_INTERNAL_ERROR:
                    ARSALPrint.e(TAG, "On connection state change: GATT_INTERNAL_ERROR (133 status) newState:" + newState);
                    connectionError = ARSAL_ERROR_ENUM.ARSAL_ERROR_BLE_STACK;
                    /* post a connect Semaphore */
                    connectionSem.release();   
                    break;

                /* triggered when pull out the battery of delos ( special for Android 5.0+ ) */
                case GATT_INTERRUPT_ERROR:
                    ARSALPrint.e(TAG, "On connection state change: GATT_INTERRUPT_ERROR (8 status) newState:" + newState);
                    connectionError = ARSAL_ERROR_ENUM.ARSAL_ERROR_BLE_CONNECTION;
                    break;

                case BluetoothGatt.GATT_FAILURE:
                    ARSALPrint.w(TAG, "On connection state change: GATT_FAILURE newState:" + newState);
                    /* post a connect Semaphore */
                    connectionSem.release();
                    break;

                default:
                    ARSALPrint.e(TAG, "unknown status : " + status);
                    break;
            }
        }
        
        @Override
        // New services discovered
        public void onServicesDiscovered (BluetoothGatt gatt, int status)
        {
            if (status != BluetoothGatt.GATT_SUCCESS)
            {
                ARSALPrint.w(TAG, "On services discovered state:" + status);
                
                /* the discovery is not successes */
                discoverServicesError = ARSAL_ERROR_ENUM.ARSAL_ERROR_BLE_SERVICES_DISCOVERING;
            }
            discoverServicesSem.release();
        }
        
        @Override
        /* Result of a characteristic read operation */
        public void onCharacteristicRead (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
        {
            //Do Nothing
        }
        
        @Override
        public void onDescriptorRead (BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status)
        {
            //Do Nothing
        }
        
        @Override
        public void onDescriptorWrite (BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status)
        {
            /* check the status */
            if (status != BluetoothGatt.GATT_SUCCESS)
            {
                configurationCharacteristicError = ARSAL_ERROR_ENUM.ARSAL_ERROR_BLE_CHARACTERISTIC_CONFIGURING;
            }
            
            /* post a configuration Semaphore */
            configurationSem.release();
        }
        
        @Override
        /* Characteristic notification */
        /*public void onCharacteristicChanged (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic)
        {
            readCharacteristicMutex.lock();
            characteristicNotifications.add(characteristic);
            readCharacteristicMutex.unlock();
            
            // post a readCharacteristic Semaphore 
            readCharacteristicSem.release();
        }*/
        
        public void onCharacteristicChanged (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic)
        {
            ARSALManagerNotification foundNotification = null;
            
            Set<String> keys = registeredNotificationCharacteristics.keySet();
            for (String key : keys) 
            {
                ARSALManagerNotification notification = registeredNotificationCharacteristics.get(key);

                for (BluetoothGattCharacteristic characteristicItem : notification.characteristics)
                {
                    if (characteristicItem.getUuid().toString().contentEquals(characteristic.getUuid().toString()))
                    {
                        foundNotification = notification;
                        break;
                    }
                }
                
                if (foundNotification != null)
                {
                    break;
                }
            }
            
            if (foundNotification != null)
            {
                byte[] value = characteristic.getValue();
                byte[] newValue = null;
                if (value != null)
                {
                    newValue = new byte[value.length];
                    System.arraycopy(value, 0, newValue, 0, value.length);
                }
                else
                {
                    newValue = new byte[0];
                }
                ARSALManagerNotificationData notificationData = new ARSALManagerNotificationData(characteristic, newValue);
                foundNotification.addNotification(notificationData);
                foundNotification.signalNotification();
            }
        }
        
        //Characteristic.WRITE_TYPE_NO_RESPONSE dosen't have reply 
        /*@Override
        public void onCharacteristicWrite (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
        {
             //ARSALPrint.d(TAG, "onCharacteristicWrite " + status);
        }*/
    };
    
    public ARSAL_ERROR_ENUM setCharacteristicNotification (BluetoothGattService service, BluetoothGattCharacteristic characteristic)
    {
        ARSAL_ERROR_ENUM result = ARSAL_ERROR_ENUM.ARSAL_OK; 
        synchronized (this)
        {
            BluetoothGatt localActiveGatt = activeGatt;
            
            /* If there is an active Gatt, disconnect it */
            if(localActiveGatt != null)
            {
                isConfiguringCharacteristics = true;
                configurationCharacteristicError = ARSAL_ERROR_ENUM.ARSAL_OK;
                
                boolean notifSet = localActiveGatt.setCharacteristicNotification (characteristic, true);
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(ARSALBLEMANAGER_CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID);
                boolean valueSet = descriptor.setValue (BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean descriptorWriten = localActiveGatt.writeDescriptor (descriptor);
                /* wait the configuration semaphore*/
                try
                {
                    configurationSem.acquire ();
                    result = configurationCharacteristicError;
                    
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                    result = ARSAL_ERROR_ENUM.ARSAL_ERROR;
                }
                
                isConfiguringCharacteristics = false;
            }
            else
            {
                result = ARSAL_ERROR_ENUM.ARSAL_ERROR_BLE_NOT_CONNECTED;
            }
        }
        
        return result;
    }
    
    public boolean writeData (byte data[], BluetoothGattCharacteristic characteristic)
    {
        boolean result = false;
        
        BluetoothGatt localActiveGatt = activeGatt;
        if ((localActiveGatt != null) && (characteristic != null) && (data != null))
        {
            characteristic.setValue(data);
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            result = localActiveGatt.writeCharacteristic(characteristic);
        }
        
        return result;
    }
    
    //public void registerNotificationCharacteristics(ArrayList<BluetoothGattCharacteristic> characteristicsArray, String readCharacteristicKey)
    public void registerNotificationCharacteristics(List<BluetoothGattCharacteristic> characteristicsArray, String readCharacteristicKey)
    {
        this.registeredNotificationCharacteristics.put(readCharacteristicKey, new ARSALManagerNotification(characteristicsArray));
    }
    
    public boolean unregisterNotificationCharacteristics(String readCharacteristicKey)
    {
        boolean result = false;
        ARSALManagerNotification notification = this.registeredNotificationCharacteristics.get(readCharacteristicKey);
        if (notification != null)
        {
            result = true;
            registeredNotificationCharacteristics.remove(notification);
        }
        
        return result;
    }
    
    public boolean cancelReadNotification(String readCharacteristicKey)
    {
        boolean result = false;
        ARSALManagerNotification notification =  this.registeredNotificationCharacteristics.get(readCharacteristicKey);
        if (notification != null)
        {
            result = true;
            notification.signalNotification();
        }
        
        return result;
    }
    
    public boolean readData(BluetoothGattCharacteristic characteristic)
    {
        return activeGatt.readCharacteristic(characteristic);
    }
        
    /** Read notification data. Block until data is received or timeout occurs.
     * @param notificationsArray a list where the received data will be put.
     * @param maxCount maximum number of notifications to receive.
     * @param readCharacteristicKey the characteristic to read notifications from.
     * @param timeout the maximum time in milliseconds to wait for data before failing.
     * 0 means infinite timeout.
     */
    public boolean readDataNotificationData (List<ARSALManagerNotificationData> notificationsArray, int maxCount, String readCharacteristicKey, long timeout)
    {
        boolean result = false;
        ARSALManagerNotification notification =  this.registeredNotificationCharacteristics.get(readCharacteristicKey);
        if (notification != null)
        {
            notification.waitNotification(timeout);
            
            if (notification.notificationsArray.size() > 0)
            {
                notification.getAllNotification(notificationsArray, maxCount);
                result = true;
            }
        }
        
        return result;
    }
    
    public boolean readDataNotificationData (List<ARSALManagerNotificationData> notificationsArray, int maxCount, String readCharacteristicKey)
    {
        return readDataNotificationData(notificationsArray, maxCount, readCharacteristicKey, 0);
    }

    /*public boolean readData (List<BluetoothGattCharacteristic> characteristicArray)
    {
        boolean result = false;
        
        // wait the readCharacteristic semaphore
        try
        {
            readCharacteristicSem.acquire ();
            
            if  (characteristicNotifications.size() > 0)
            {
                readCharacteristicMutex.lock();
                
                characteristicArray.addAll(characteristicNotifications);
                characteristicNotifications.clear();
                
                readCharacteristicMutex.unlock();
                
                result = true;
            }
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
        
        return result;
    }*/
    
    public void unlock ()
    {
        /* post all Semaphore to unlock the all the functions */
        connectionSem.release();
        configurationSem.release();
        
        discoverServicesSem.release();
        discoverCharacteristicsSem.release();
    
        //readCharacteristicSem.release();
        
        for (String key : registeredNotificationCharacteristics.keySet())
        {
            ARSALManagerNotification notification = registeredNotificationCharacteristics.get(key);
            notification.signalNotification();
        }
        
        /* disconnectionSem is not post because:
         * if the connection is fail, disconnect is not call.
         * if the connection is successful, the BLE callback is always called.
         * the disconnect function is called after the join of the network threads.
         */
    }
    
    public void reset ()
    {
        synchronized (this)
        {
            /* reset all Semaphores */
            
            while (connectionSem.tryAcquire() == true)
            {
                /* Do nothing*/
            }
            
            while (disconnectionSem.tryAcquire() == true)
            {
                /* Do nothing*/
            }
            
            while (discoverServicesSem.tryAcquire() == true)
            {
                /* Do nothing*/
            }
            
            while (discoverCharacteristicsSem.tryAcquire() == true)
            {
                /* Do nothing*/
            }
            
            /*while (readCharacteristicSem.tryAcquire() == true)
            {*/
                /* Do nothing*/
            /*}*/
            
            while (configurationSem.tryAcquire() == true)
            {
                /* Do nothing*/
            }
            
            for (String key : registeredNotificationCharacteristics.keySet())
            {
                ARSALManagerNotification notification = registeredNotificationCharacteristics.get(key);
                notification.signalNotification();
            }
            registeredNotificationCharacteristics.clear();
            
            //TODO
            closeGatt();
        }
    }
    
    private void onDisconectGatt()
    {
        ARSALPrint.d(TAG, "activeGatt disconnected" );
        
        /* Post disconnectionSem only if the disconnect is asked */
        if (askDisconnection)
        {
            disconnectionSem.release();
        }
        
        /* if activePeripheral is discovering services */
        if (isDiscoveringServices)
        {
            discoverServicesError = ARSAL_ERROR_ENUM.ARSAL_ERROR_BLE_NOT_CONNECTED;
            discoverServicesSem.release();
        }
        
        /* if activePeripheral is discovering Characteristics */
        if (isDiscoveringCharacteristics)
        {
            discoverCharacteristicsError = ARSAL_ERROR_ENUM.ARSAL_ERROR_BLE_NOT_CONNECTED;
            discoverCharacteristicsSem.release();
        }
        
        /* if activePeripheral is configuring Characteristics */
        if (isConfiguringCharacteristics)
        {
            configurationCharacteristicError = ARSAL_ERROR_ENUM.ARSAL_ERROR_BLE_NOT_CONNECTED;
            configurationSem.release();
        }
        
        /* Notify listener */
        if (!askDisconnection)
        {
            if (listener != null)
            {
                listener.onBLEDisconnect();
            }
        }
    }
    
    private void closeGatt ()
    {
        if (activeGatt != null)
        {
            activeGatt.close();
            activeGatt = null;
        }
    }
}
