// Updated for X-Core
package dev.dev7.example;

import static android.content.Context.RECEIVER_EXPORTED;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_CONNECTION_STATE_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_DOWNLOAD_SPEED_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_DOWNLOAD_TRAFFIC_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_DURATION_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_UPLOAD_SPEED_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_UPLOAD_TRAFFIC_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_STATICS_BROADCAST_INTENT;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import dev.dev7.lib.v2ray.V2rayController;
import dev.dev7.lib.v2ray.utils.V2rayConfigs;
import dev.dev7.lib.v2ray.utils.V2rayConstants;

public class MainActivity extends AppCompatActivity {

    private Button connection;
    private TextView connection_speed, connection_traffic, connection_time, server_delay, connected_server_delay, connection_mode, core_version;
    private EditText v2ray_config;
    private SharedPreferences sharedPreferences;
    private BroadcastReceiver v2rayBroadCastReceiver;

    @SuppressLint({"SetTextI18n", "UnspecifiedRegisterReceiverFlag"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (savedInstanceState == null) {
            V2rayController.init(this, R.drawable.ic_launcher, "V2ray Android");
            connection = findViewById(R.id.btn_connection);
            connection_speed = findViewById(R.id.connection_speed);
            connection_time = findViewById(R.id.connection_duration);
            connection_traffic = findViewById(R.id.connection_traffic);
            server_delay = findViewById(R.id.server_delay);
            connection_mode = findViewById(R.id.connection_mode);
            connected_server_delay = findViewById(R.id.connected_server_delay);
            v2ray_config = findViewById(R.id.v2ray_config);
            core_version = findViewById(R.id.core_version);
        }

        core_version.setText(V2rayController.getCoreVersion());
        // initialize shared preferences for save or reload default config
        sharedPreferences = getSharedPreferences("conf", MODE_PRIVATE);
        // reload previous config to edit text
        v2ray_config.setText(sharedPreferences.getString("v2ray_config", getDefaultConfig()));
        connection.setOnClickListener(view -> {
            sharedPreferences.edit().putString("v2ray_config", v2ray_config.getText().toString()).apply();
            if (V2rayController.getConnectionState() == V2rayConstants.CONNECTION_STATES.DISCONNECTED) {
                V2rayController.startV2ray(this, "Test Server", v2ray_config.getText().toString(), null);
            } else {
                V2rayController.stopV2ray(this);
            }
        });


        // Check the connection delay of connected config.
        connected_server_delay.setOnClickListener(view -> {
            connected_server_delay.setText("connected server delay : measuring...");
            V2rayController.getConnectedV2rayServerDelay(this, delayResult -> runOnUiThread(() -> connected_server_delay.setText("connected server delay : " + delayResult + "ms")));
        });
        // Another way to check the connection delay of a config without connecting to it.
        server_delay.setOnClickListener(view -> {
            server_delay.setText("server delay : measuring...");
            new Thread(() -> {
                long delay = V2rayController.getV2rayServerDelay(v2ray_config.getText().toString());
                runOnUiThread(() -> server_delay.setText("server delay : " + delay + "ms"));
            }, "offline_delay_thread").start();
        });

        connection_mode.setOnClickListener(view -> {
            V2rayController.toggleConnectionMode();
            connection_mode.setText("connection mode : " + V2rayConfigs.serviceMode.toString());
        });

        // Check connection state when activity launch
        V2rayConstants.CONNECTION_STATES state = V2rayController.getConnectionState();
        if (state == null) {
            state = V2rayConstants.CONNECTION_STATES.DISCONNECTED;
        }
        switch (state) {
            case CONNECTED:
                connection.setText("CONNECTED");
                connected_server_delay.callOnClick();
                break;
            case DISCONNECTED:
                connection.setText("DISCONNECTED");
                break;
            case CONNECTING:
                connection.setText("CONNECTING");
                break;
            default:
                connection.setText("DISCONNECTED");
                break;
        }
        //I tested several different ways to send information from the connection process side
        // to other places (such as interfaces, AIDL and singleton ,...) apparently the best way
        // to send information is broadcast.
        // So v2ray library will be broadcast information with action V2RAY_CONNECTION_INFO.
        v2rayBroadCastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                runOnUiThread(() -> {
                    if (intent == null || intent.getExtras() == null) {
                        return;
                    }
                    connection_time.setText("connection time : " + intent.getExtras().getString(SERVICE_DURATION_BROADCAST_EXTRA, "00:00:00"));
                    connection_speed.setText("connection speed : " + intent.getExtras().getString(SERVICE_UPLOAD_SPEED_BROADCAST_EXTRA, "0B/s") + " | " + intent.getExtras().getString(SERVICE_DOWNLOAD_SPEED_BROADCAST_EXTRA, "0B/s"));
                    connection_traffic.setText("connection traffic : " + intent.getExtras().getString(SERVICE_UPLOAD_TRAFFIC_BROADCAST_EXTRA, "0B") + " | " + intent.getExtras().getString(SERVICE_DOWNLOAD_TRAFFIC_BROADCAST_EXTRA, "0B"));
                    connection_mode.setText("connection mode : " + V2rayConfigs.serviceMode.toString());
                    Object stateObj = intent.getExtras().getSerializable(SERVICE_CONNECTION_STATE_BROADCAST_EXTRA);
                    if (stateObj instanceof V2rayConstants.CONNECTION_STATES) {
                        switch ((V2rayConstants.CONNECTION_STATES) stateObj) {
                            case CONNECTED:
                                connection.setText("CONNECTED");
                                break;
                            case DISCONNECTED:
                                connection.setText("DISCONNECTED");
                                connected_server_delay.setText("connected server delay : wait for connection");
                                break;
                            case CONNECTING:
                                connection.setText("CONNECTING");
                                break;
                            default:
                                break;
                        }
                    }
                });
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(v2rayBroadCastReceiver, new IntentFilter(V2RAY_SERVICE_STATICS_BROADCAST_INTENT), RECEIVER_EXPORTED);
        } else {
            registerReceiver(v2rayBroadCastReceiver, new IntentFilter(V2RAY_SERVICE_STATICS_BROADCAST_INTENT));
        }
    }

    public static String getDefaultConfig() {
        return "";
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (v2rayBroadCastReceiver != null){
            unregisterReceiver(v2rayBroadCastReceiver);
        }
    }
}
