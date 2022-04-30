package studio.prin.android.bleswitch;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;
import java.util.UUID;

import cn.com.heaton.blelibrary.ble.Ble;
import cn.com.heaton.blelibrary.ble.callback.BleConnectCallback;
import cn.com.heaton.blelibrary.ble.callback.BleScanCallback;
import cn.com.heaton.blelibrary.ble.callback.BleWriteCallback;
import cn.com.heaton.blelibrary.ble.model.BleDevice;
import cn.com.heaton.blelibrary.ble.utils.ByteUtils;
import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    /**
     * 蓝牙权限。
     */
    private static final String[] BLE_PERMISSIONS = new String[]{
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    };

    /**
     * Android 12 及以上版本的蓝牙权限。
     */
    @RequiresApi(api = Build.VERSION_CODES.S)
    private static final String[] ANDROID_12_BLE_PERMISSIONS = new String[]{
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    };

    /**
     * 对应遥控开关的产品 ID。
     */
    private static final String PRODUCT_ID = "WW0001";

    /**
     * 代表开关大灯的遥控指令。
     *
     * FE01 - 固定头部
     * 0006 - 后面的所有指令长度除二
     * 3201 - 产品指令前缀
     * 01   - irType
     * 807F - irAddr
     * 12   - irCMD（大灯 12，氛围灯 08，还有其他定时指令）
     */
    private static final String RC_COMMAND = "FE010006320101807F12";

    private BleDevice bleDevice;
    private Ble<BleDevice> ble;
    private Button btnScan;
    private Button btnToggle;
    private TextView tvStatus;

    private UUID serviceId;
    private UUID writeCharacteristicId;

    private final BleConnectCallback<BleDevice> connectCallback = new BleConnectCallback<BleDevice>() {
        @Override
        public void onConnectionChanged(BleDevice device) {
            logToast("onConnectionChanged: " + device.getConnectionState());

            if (device.isDisconnected()) {
                bleDevice = null;
                updateStatus("Disconnected.");
            }
        }

        @Override
        public void onConnectFailed(BleDevice device, int errorCode) {
            super.onConnectFailed(device, errorCode);
            logToast("onConnectFailed:" + errorCode, Log.ERROR);
        }

        @Override
        public void onConnectCancel(BleDevice device) {
            super.onConnectCancel(device);
            logToast("onConnectCancel: " + device.getBleName(), Log.ERROR);
        }

        @Override
        public void onServicesDiscovered(BleDevice device, BluetoothGatt gatt) {
            super.onServicesDiscovered(device, gatt);
            List<BluetoothGattService> gattServices = gatt.getServices();
            logToast("onServicesDiscovered: " + gattServices.toString());

            // 在 BLE 设备的所有服务中，获取可写的特征值和服务 UUID
            for (BluetoothGattService service: gattServices) {
                List<BluetoothGattCharacteristic> gattCharacteristics = service.getCharacteristics();

                for (BluetoothGattCharacteristic chara : gattCharacteristics) {
                    if ((chara.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                        serviceId = service.getUuid();
                        writeCharacteristicId = chara.getUuid();

                        Ble.options()
                            .setUuidService(serviceId)
                            .setUuidWriteCha(writeCharacteristicId);
                    }
                }
            }
        }

        @Override
        public void onReady(BleDevice device) {
            super.onReady(device);
            Log.d(TAG, "onReady: " + device.toString());
            updateStatus("Connected!\n\n" + device.toString());
        }
    };

    private final BleScanCallback<BleDevice> scanCallback = new BleScanCallback<BleDevice>() {
        @Override
        public void onLeScan(final BleDevice device, int rssi, byte[] scanRecord) {
            Log.d(TAG, "onLeScan: " + device.toString());
            updateStatus("Scanning...\n\n" + device.toString());

            String name = device.getBleName();
            if (name != null && name.contains(PRODUCT_ID)) {
                ble.stopScan();
                bleDevice = device;
                updateStatus("Connecting...\n\n" + device.toString());
                ble.connect(bleDevice, connectCallback);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            logToast("onScanFailed: " + errorCode, Log.ERROR);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initBle();
        requestBlePermissions();

        ble = Ble.getInstance();
        btnScan = findViewById(R.id.btn_scan);
        btnToggle = findViewById(R.id.btn_toggle);
        tvStatus = findViewById(R.id.tv_status);

        // 扫描并连接设备，或断开连接
        btnScan.setOnClickListener(v -> {
            if (bleDevice == null || !bleDevice.isConnected()) {
                ble.disconnectAll();
                ble.startScan(scanCallback);
            } else {
                ble.disconnect(bleDevice, connectCallback);
            }
        });

        // 开关灯
        btnToggle.setOnClickListener(v -> {
            if (bleDevice == null || !bleDevice.isConnected()) {
                logToast("No device connected");
                return;
            }

            if (serviceId == null || writeCharacteristicId == null) {
                logToast("Service or characteristic not available.");
                return;
            }

            toggleSwitch(bleDevice);
        });
    }

    /**
     * 初始化蓝牙框架。
     *
     * @see <a href="https://github.com/aicareles/Android-BLE">Android-BLE</a>
     */
    private void initBle() {
        Ble.options()
            .setLogBleEnable(true) // 设置是否输出打印蓝牙日志
            .setThrowBleException(true) // 设置是否抛出蓝牙异常
            .setLogTAG("AndroidBLE") // 设置全局蓝牙操作日志 TAG
            .setAutoConnect(false) // 设置是否自动连接
            .setIgnoreRepeat(false) // 设置是否过滤扫描到的设备（已扫描到的不会再次扫描）
            .setConnectFailedRetryCount(3) // 连接异常时（如蓝牙协议栈错误）重新连接次数
            .setConnectTimeout(10 * 1000) // 设置连接超时时长
            .setScanPeriod(12 * 1000) // 设置扫描时长
            .setMaxConnectNum(7) // 最大连接数量
            .create(this.getApplication(), new Ble.InitCallback() {
                @Override
                public void success() {
                    logToast("初始化成功");
                }

                @Override
                public void failed(int failedCode) {
                    logToast("初始化失败：" + failedCode, Log.ERROR);
                }
            });
    }

    /**
     * 写入代表开关灯的特征值。
     *
     * @param device 蓝牙设备
     */
    public void toggleSwitch(BleDevice device) {
        byte[] data = ByteUtils.hexStr2Bytes(RC_COMMAND);
        ble.writeByUuid(device, data, serviceId, writeCharacteristicId, new BleWriteCallback<BleDevice>() {
            @Override
            public void onWriteSuccess(BleDevice device, BluetoothGattCharacteristic characteristic) {
                logToast("onWriteSuccess: " + characteristic.toString());
            }

            @Override
            public void onWriteFailed(BleDevice device, int failedCode) {
                super.onWriteFailed(device, failedCode);
                logToast("onWriteFailed: " + failedCode, Log.ERROR);
            }
        });
    }

    /**
     * 记录日志并提示。
     *
     * @param text 提示文本
     * @param priority ASSERT|DEBUG|ERROR|INFO|VERBOSE|WARN
     */
    public void logToast(String text, int priority) {
        Log.println(priority, TAG, text);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void logToast(String text) {
        logToast(text, Log.INFO);
    }

    public void updateStatus(String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tvStatus.setText(text);
            }
        });
    }

    /**
     * 请求蓝牙权限。
     */
    @SuppressLint("NewApi")
    public void requestBlePermissions() {
        String[] permission = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ?
                ANDROID_12_BLE_PERMISSIONS : BLE_PERMISSIONS;

        if (!EasyPermissions.hasPermissions(this, permission)) {
            EasyPermissions.requestPermissions(this, "Hi", 42, permission);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }
}
