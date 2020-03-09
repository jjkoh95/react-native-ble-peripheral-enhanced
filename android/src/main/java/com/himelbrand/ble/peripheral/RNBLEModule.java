package com.himelbrand.ble.peripheral;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;


/**
 * {@link NativeModule} that allows JS to open the default browser
 * for an url.
 */
public class RNBLEModule extends ReactContextBaseJavaModule {

    private ReactApplicationContext reactContext;

    private BluetoothManager mBluetoothManager;

    private BluetoothAdapter mBluetoothAdapter;

    private BluetoothGattServer mGattServer;

    private BluetoothLeAdvertiser advertiser;

    private boolean advertising = false;

    private HashMap<String, BluetoothGattService> servicesMap = new HashMap<>();

    private HashSet<BluetoothDevice> mBluetoothDevices = new HashSet<>();

    public RNBLEModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;

        mBluetoothManager = (BluetoothManager) reactContext.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager != null ? mBluetoothManager.getAdapter() : null;
    }

    @Override
    public String getName() {
        return "BLEPeripheral";
    }

    @ReactMethod
    public void setName(String name) {
        if (mBluetoothAdapter != null) {
            mBluetoothAdapter.setName(name);
            Log.i("RNBLEModule", "name set to " + name);
        }
    }

    @ReactMethod
    public void isSupported(final Promise promise) {
        promise.resolve(mBluetoothAdapter != null && mBluetoothAdapter.isMultipleAdvertisementSupported());
    }

    @ReactMethod
    public void addService(String uuid, Boolean primary) {
        if (!this.servicesMap.containsKey(uuid)) {
            final UUID serviceUUID = UUID.fromString(uuid);
            int type = primary ? BluetoothGattService.SERVICE_TYPE_PRIMARY : BluetoothGattService.SERVICE_TYPE_SECONDARY;
            final BluetoothGattService tempService = new BluetoothGattService(serviceUUID, type);
            this.servicesMap.put(uuid, tempService);
            Log.i("RNBLEModule", "Added service " + uuid);
        } else {
            // NotifyJS
            // alertJS("service " + uuid + " already there");
        }
    }

    @ReactMethod
    public void addCharacteristicToService(String serviceUUID, String uuid, Integer permissions, Integer properties, String data) {
        final UUID characteristicUUID = UUID.fromString(uuid);
        final byte[] byteData = data.getBytes(StandardCharsets.UTF_8);

        final BluetoothGattCharacteristic tempChar = new BluetoothGattCharacteristic(characteristicUUID, properties, permissions);
        tempChar.setValue(byteData);

        this.servicesMap.get(serviceUUID).addCharacteristic(tempChar);
        Log.i("RNBLEModule", "Added characteristic to service");
    }

    @ReactMethod
    public void start(final Promise promise) {
        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a dialog requesting user permission to enable Bluetooth.
        if (mBluetoothAdapter == null) {
            promise.reject("EUNAVAILABLE", "BluetoothAdapter is not available.");
            return;
        }

        if (!mBluetoothAdapter.isEnabled()) {
            // Make sure bluetooth is enabled.
            final Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            reactContext.startActivityForResult(enableBtIntent, 1, new Bundle());
            // Reject start promise - user required to attempt start again after enabling BT
            promise.reject("EDISABLED", "BluetoothAdapter is disabled.");
            return;
        }

        mGattServer = mBluetoothManager.openGattServer(reactContext, new BluetoothGattServerCallback() {
            @Override
            public void onConnectionStateChange(BluetoothDevice device, final int status, int newState) {
                super.onConnectionStateChange(device, status, newState);
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        mBluetoothDevices.add(device);
                    } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        mBluetoothDevices.remove(device);
                    }
                } else {
                    mBluetoothDevices.remove(device);
                }
            }

            @Override
            public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                    BluetoothGattCharacteristic characteristic) {
                super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
                if (offset != 0) {
                    mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
                            /* value (optional) */ null);
                    return;
                }
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                        offset, characteristic.getValue());
            }

            @Override
            public void onNotificationSent(BluetoothDevice device, int status) {
                super.onNotificationSent(device, status);
            }

            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                     BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded,
                                                     int offset, byte[] value) {
                super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite,
                        responseNeeded, offset, value);
                characteristic.setValue(value);
                WritableMap map = Arguments.createMap();
                WritableArray data = Arguments.createArray();
                for (byte b : value) {
                    data.pushInt((int) b);
                }
                map.putArray("data", data);
                map.putString("device", device.toString());
                if (responseNeeded) {
                    mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                }
            }
        });

        for (BluetoothGattService service : this.servicesMap.values()) {
            mGattServer.addService(service);
        }

        if (advertiser == null) {
            advertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        }

        final AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .build();

        final AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(true)
                .setIncludeDeviceName(true);

        for (BluetoothGattService service : this.servicesMap.values()) {
            dataBuilder.addServiceUuid(new ParcelUuid(service.getUuid()));
        }

        final AdvertiseData data = dataBuilder.build();
        Log.i("RNBLEModule", data.toString());

        advertiser.startAdvertising(settings, data, new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
                advertising = true;
                promise.resolve("Success, Started Advertising");
            }

            @Override
            public void onStartFailure(int errorCode) {
                Log.e("RNBLEModule", "Advertising onStartFailure: " + errorCode);
                advertising = false;

                String description;
                switch (errorCode) {
                    case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                        description = "ADVERTISE_FAILED_FEATURE_UNSUPPORTED";
                        break;
                    case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                        description = "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS";
                        break;
                    case ADVERTISE_FAILED_ALREADY_STARTED:
                        description = "ADVERTISE_FAILED_ALREADY_STARTED";
                        break;
                    case ADVERTISE_FAILED_DATA_TOO_LARGE:
                        description = "ADVERTISE_FAILED_DATA_TOO_LARGE";
                        break;
                    case ADVERTISE_FAILED_INTERNAL_ERROR:
                        description = "ADVERTISE_FAILED_INTERNAL_ERROR";
                        break;
                    default:
                        description = "UNKNOWN";

                }

                promise.reject("Advertising onStartFailure: " + errorCode + " - " + description);

                super.onStartFailure(errorCode);
            }
        });
    }

    @ReactMethod
    public void stop(final Promise promise) {
        if (mGattServer != null) {
            mGattServer.close();
        }

        mBluetoothDevices = new HashSet<>();

        if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled() && advertiser != null) {
            // If stopAdvertising() gets called before close() a null
            // pointer exception is raised.
            advertiser.stopAdvertising(new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    super.onStartSuccess(settingsInEffect);
                    advertising = true;
                    promise.resolve("Success, Stopped Advertising");
                }

                @Override
                public void onStartFailure(int errorCode) {
                    Log.e("RNBLEModule", "Advertising onStopFailure: " + errorCode);
                    advertising = false;

                    String description;
                    switch (errorCode) {
                        case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                            description = "ADVERTISE_FAILED_FEATURE_UNSUPPORTED";
                            break;
                        case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                            description = "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS";
                            break;
                        case ADVERTISE_FAILED_ALREADY_STARTED:
                            description = "ADVERTISE_FAILED_ALREADY_STARTED";
                            break;
                        case ADVERTISE_FAILED_DATA_TOO_LARGE:
                            description = "ADVERTISE_FAILED_DATA_TOO_LARGE";
                            break;
                        case ADVERTISE_FAILED_INTERNAL_ERROR:
                            description = "ADVERTISE_FAILED_INTERNAL_ERROR";
                            break;
                        default:
                            description = "UNKNOWN";
                    }

                    promise.reject("Advertising onStopFailure: " + errorCode + " - " + description);

                    super.onStartFailure(errorCode);
                }
            });
        }
    }

    @ReactMethod
    public void sendNotificationToDevices(String serviceUUID, String charUUID, String data) {
        BluetoothGattCharacteristic characteristic = servicesMap.get(serviceUUID).getCharacteristic(UUID.fromString(charUUID));

        final byte[] byteData = data.getBytes(StandardCharsets.UTF_8);
        characteristic.setValue(byteData);

        boolean indicate = (characteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_INDICATE)
                == BluetoothGattCharacteristic.PROPERTY_INDICATE;

        for (BluetoothDevice device : mBluetoothDevices) {
            // true for indication (acknowledge) and false for notification (un-acknowledge).
            mGattServer.notifyCharacteristicChanged(device, characteristic, indicate);
        }
    }

    @ReactMethod
    public void isAdvertising(Promise promise) {
        promise.resolve(this.advertising);
    }
}
